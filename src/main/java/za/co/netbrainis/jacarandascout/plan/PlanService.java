package za.co.netbrainis.jacarandascout.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.netbrainis.jacarandascout.ScoutProperties;
import za.co.netbrainis.jacarandascout.ai.BloomPredictor;
import za.co.netbrainis.jacarandascout.ai.BloomVision;
import za.co.netbrainis.jacarandascout.ai.FieldGuideWriter;
import za.co.netbrainis.jacarandascout.ai.IntentParser;
import za.co.netbrainis.jacarandascout.ai.OllamaClient;
import za.co.netbrainis.jacarandascout.core.BestDayPicker;
import za.co.netbrainis.jacarandascout.core.BloomScorer;
import za.co.netbrainis.jacarandascout.core.DayForecast;
import za.co.netbrainis.jacarandascout.core.Gpx;
import za.co.netbrainis.jacarandascout.core.Hotspot;
import za.co.netbrainis.jacarandascout.core.HotspotClusterer;
import za.co.netbrainis.jacarandascout.core.LatLon;
import za.co.netbrainis.jacarandascout.core.LoopPlanner;
import za.co.netbrainis.jacarandascout.core.Observation;
import za.co.netbrainis.jacarandascout.core.Route;
import za.co.netbrainis.jacarandascout.core.SeasonCurve;
import za.co.netbrainis.jacarandascout.core.TripIntent;
import za.co.netbrainis.jacarandascout.data.StreetRouter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final String ATTRIBUTION =
            "Sightings: iNaturalist contributors (CC licences). Map & routing data: © OpenStreetMap contributors (ODbL). "
                    + "Weather: Open-Meteo.com (CC BY 4.0).";

    private final ScoutProperties props;
    private final DataHub data;
    private final IntentParser intents;
    private final FieldGuideWriter writer;
    private final BloomVision vision;
    private final BloomPredictor bloomModel;
    private final OllamaClient ollama;
    private final StreetRouter router;
    private final PlanStore store;

    public PlanService(ScoutProperties props, DataHub data, IntentParser intents, FieldGuideWriter writer,
                       BloomVision vision, BloomPredictor bloomModel, OllamaClient ollama, StreetRouter router,
                       PlanStore store) {
        this.props = props;
        this.data = data;
        this.intents = intents;
        this.writer = writer;
        this.vision = vision;
        this.bloomModel = bloomModel;
        this.ollama = ollama;
        this.router = router;
        this.store = store;
    }

    public Plan plan(PlanRequest req) {
        long t0 = System.currentTimeMillis();
        LocalDate today = LocalDate.now(DataHub.SAST);

        IntentParser.Parsed parsed = intents.parse(req.query());
        TripIntent intent = parsed.intent();
        LatLon start = router.geocode(intent.startPlace())
                .orElseGet(() -> req.lat() != null && req.lon() != null
                        ? new LatLon(req.lat(), req.lon())
                        : new LatLon(props.defaultLat(), props.defaultLon()));

        DataHub.Sourced<List<Observation>> obs = data.observations(start, today);
        Map<Long, Double> bloom = vision.rate(obs.data());
        TreePrior trees = treePrior(start, obs, today);
        List<Observation> candidates = new ArrayList<>(trees.trees());
        if (!trees.replacesDemo()) {
            candidates.addAll(obs.data());
        }
        BloomScorer scorer = new BloomScorer(props.halfLifeDays());
        List<Hotspot> hotspots = new HotspotClusterer(props.clusterRadiusM())
                .cluster(candidates, o -> trees.chance().containsKey(o.id())
                        ? BloomScorer.treeWeight(trees.chance().get(o.id()))
                        : scorer.weight(o, today, bloom.getOrDefault(o.id(), Double.NaN)));

        LoopPlanner planner = new LoopPlanner(props.detourFactor(), props.maxStops());
        List<Hotspot> tour = planner.plan(start, intent.distanceKm() * 1000, hotspots);
        if (tour.isEmpty() && !hotspots.isEmpty()) {
            // nothing fits the budget: offer an out-and-back to the closest strong spot
            hotspots.stream()
                    .filter(h -> h.score() >= hotspots.get(0).score() * 0.25)
                    .min(Comparator.comparingDouble(h -> h.position().metersTo(start)))
                    .ifPresent(tour::add);
        }

        tour = distinctLabels(tour);

        List<LatLon> waypoints = new ArrayList<>();
        waypoints.add(start);
        tour.forEach(h -> waypoints.add(h.position()));
        waypoints.add(start);
        Route route = router.route(waypoints, intent.activity());
        double routeKm = route.meters() / 1000.0;

        SeasonCurve.Status season = SeasonCurve.assess(data.histogram(start).data(), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        DataHub.Sourced<List<DayForecast>> forecast = data.forecast(start, today);
        BestDayPicker.Outlook outlook = BestDayPicker.assess(forecast.data());

        FieldGuideWriter.Narrative story = writer.write(intent, tour, routeKm, season, outlook, trees.startChance(), today);

        List<Plan.Stop> stops = new ArrayList<>();
        for (int i = 0; i < tour.size(); i++) {
            Hotspot h = tour.get(i);
            stops.add(new Plan.Stop(i + 1, h.label(), h.lat(), h.lon(), h.score(), h.sightings(), h.lastSeen(),
                    h.photoUrl(), story.cues().get(i)));
        }
        List<double[]> geometry = route.geometry().stream().map(p -> new double[]{p.lat(), p.lon()}).toList();
        List<Plan.HotspotPin> pins = hotspots.stream().limit(80)
                .map(h -> new Plan.HotspotPin(h.lat(), h.lon(), h.score(), h.sightings(), h.label())).toList();

        String model = parsed.byModel() || story.byModel()
                ? "ollama " + ollama.model()
                : "templates (no local model reachable)";
        Plan plan = new Plan(
                UUID.randomUUID().toString().substring(0, 8),
                Instant.now(),
                req.query(),
                intent.activity(),
                intent.distanceKm(),
                Math.round(routeKm * 10) / 10.0,
                (int) Math.round(routeKm * intent.paceMinPerKm()),
                start.lat(), start.lon(),
                story.headline(), story.briefing(),
                stops, geometry, pins, season, outlook.days(), outlook.stormWarning(), trees.startChance(),
                new Plan.Sources(trees.replacesDemo() ? "known trees (" + trees.source() + ")" : obs.source(),
                        forecast.source(), route.source(), model, !bloom.isEmpty(), trees.model(), ATTRIBUTION));
        store.save(plan);
        log.info("Plan {}: {} stops, {} km, {} ms (data={}, model={})", plan.id(), stops.size(), plan.routeKm(),
                System.currentTimeMillis() - t0, obs.source(), model);
        return plan;
    }

    /**
     * Trees mapped in any year, each with the bloom model's chance it's flowering today.
     * The last point sent is the start, which gives the area-level chance for the briefing.
     *
     * @param replacesDemo true when real trees + model replace synthetic demo sightings
     */
    record TreePrior(List<Observation> trees, Map<Long, Double> chance, Double startChance, String model,
                     String source, boolean replacesDemo) {
        static final TreePrior NONE = new TreePrior(List.of(), Map.of(), null, null, null, false);
    }

    private TreePrior treePrior(LatLon start, DataHub.Sourced<List<Observation>> recent, LocalDate today) {
        if (!bloomModel.enabled()) {
            return TreePrior.NONE;
        }
        // a tree with a fresh sighting is already counted at full strength
        java.util.Set<Long> recentIds = new java.util.HashSet<>();
        recent.data().forEach(o -> recentIds.add(o.id()));
        DataHub.Sourced<List<Observation>> known = data.trees(start);
        List<Observation> trees = known.data().stream().filter(o -> !recentIds.contains(o.id())).toList();

        List<LatLon> points = new ArrayList<>();
        trees.forEach(o -> points.add(o.position()));
        points.add(start);
        return bloomModel.predict(points, today).map(p -> {
            Map<Long, Double> chance = new java.util.HashMap<>();
            for (int i = 0; i < trees.size(); i++) {
                chance.put(trees.get(i).id(), p.probabilities().get(i));
            }
            String model = p.model() + " on " + trees.size() + " known trees"
                    + (p.weather().isBlank() ? "" : ", weather " + p.weather());
            boolean replacesDemo = !trees.isEmpty() && "demo-synthetic".equals(recent.source());
            return new TreePrior(trees, chance, p.probabilities().get(trees.size()), model, known.source(), replacesDemo);
        }).orElse(TreePrior.NONE);
    }

    /** Several clusters in one suburb share a label; number them so cues stay unambiguous. */
    static List<Hotspot> distinctLabels(List<Hotspot> tour) {
        Map<String, Long> totals = new java.util.HashMap<>();
        tour.forEach(h -> totals.merge(h.label(), 1L, Long::sum));
        Map<String, Integer> seen = new java.util.HashMap<>();
        List<Hotspot> out = new ArrayList<>();
        for (Hotspot h : tour) {
            if (totals.get(h.label()) > 1) {
                int n = seen.merge(h.label(), 1, Integer::sum);
                out.add(new Hotspot(h.id(), h.lat(), h.lon(), h.score(), h.sightings(), h.lastSeen(),
                        h.label() + " · spot " + n, h.photoUrl(), h.observationIds()));
            } else {
                out.add(h);
            }
        }
        return out;
    }

    public String gpx(Plan plan) {
        List<Gpx.Waypoint> wpts = plan.stops().stream()
                .map(s -> new Gpx.Waypoint("Stop " + s.index() + ": " + s.label(), s.cue(), s.lat(), s.lon()))
                .toList();
        List<LatLon> track = plan.geometry().stream().map(p -> new LatLon(p[0], p[1])).toList();
        return Gpx.write(plan.headline(), wpts, track, plan.createdAt());
    }

    /** Pre-download data for Johannesburg and Pretoria while on Wi-Fi. */
    public Map<String, String> sync() {
        LocalDate today = LocalDate.now(DataHub.SAST);
        LatLon jhb = new LatLon(props.defaultLat(), props.defaultLon());
        LatLon pta = new LatLon(-25.7545, 28.2230);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("johannesburg", data.observations(jhb, today).source());
        out.put("pretoria", data.observations(pta, today).source());
        out.put("histogram", data.histogram(jhb).source());
        out.put("forecast", data.forecast(jhb, today).source());
        for (Map.Entry<String, LatLon> city : Map.of("johannesburg", jhb, "pretoria", pta).entrySet()) {
            List<Observation> trees = data.trees(city.getValue()).data();
            out.put(city.getKey() + "Trees", trees.size() + " known trees");
            // warms the sidecar's weather cache so bloom chances still work offline
            bloomModel.predict(List.of(city.getValue()), today)
                    .ifPresent(p -> out.put(city.getKey() + "BloomChance", Math.round(p.probabilities().get(0) * 100) + "%"));
        }
        return out;
    }
}
