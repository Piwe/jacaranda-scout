package za.co.netbrainis.jacarandascout.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLogicTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void haversineMatchesKnownDistance() {
        // Johannesburg CBD to Pretoria CBD is roughly 54 km as the crow flies
        double m = new LatLon(-26.2041, 28.0473).metersTo(new LatLon(-25.7479, 28.2293));
        assertTrue(m > 50_000 && m < 56_000, "got " + m);
    }

    @Test
    void recentFloweringSightingsWeighMore() {
        BloomScorer s = new BloomScorer(5);
        Observation fresh = obs(1, -26.17, 28.03, 0, true);
        Observation old = obs(2, -26.17, 28.03, 5, true);
        Observation unannotated = obs(3, -26.17, 28.03, 0, false);
        assertEquals(1.0, s.weight(fresh, TODAY), 1e-9);
        assertEquals(0.5, s.weight(old, TODAY), 1e-9);
        assertEquals(0.5, s.weight(unannotated, TODAY), 1e-9);
        assertEquals(0.2, s.weight(fresh, TODAY, 0.0), 1e-9);
    }

    @Test
    void clustersNearbySightingsAndKeepsDistantOnesApart() {
        List<Observation> obs = List.of(
                obs(1, -26.1720, 28.0260, 0, true),
                obs(2, -26.1722, 28.0263, 1, true),
                obs(3, -26.1718, 28.0258, 2, true),
                obs(4, -25.7445, 28.2105, 0, true));
        BloomScorer s = new BloomScorer(5);
        List<Hotspot> hs = new HotspotClusterer(300).cluster(obs, o -> s.weight(o, TODAY));
        assertEquals(2, hs.size());
        assertEquals(3, hs.get(0).sightings());
        assertEquals("Westcliff, Johannesburg", hs.get(0).label());
        assertEquals(TODAY, hs.get(0).lastSeen());
    }

    @Test
    void loopStaysWithinBudgetAndPrefersRewardingStops() {
        LatLon start = new LatLon(-26.1700, 28.0300);
        List<Hotspot> candidates = List.of(
                hotspot("near-strong", -26.1720, 28.0262, 10),
                hotspot("near-weak", -26.1650, 28.0300, 1),
                hotspot("far", -25.7445, 28.2105, 50));
        LoopPlanner planner = new LoopPlanner(1.3, 6);
        List<Hotspot> tour = planner.plan(start, 3000, candidates);
        assertFalse(tour.isEmpty());
        assertTrue(tour.stream().noneMatch(h -> h.id().equals("far")));
        assertEquals("near-strong", tour.stream().max((a, b) -> Double.compare(a.score(), b.score())).get().id());
        assertTrue(planner.estimateMeters(start, tour) <= 3000);
    }

    @Test
    void twoOptRemovesCrossings() {
        LatLon start = new LatLon(0, 0);
        // four corners of a square; the optimal loop is the perimeter
        List<Hotspot> corners = List.of(
                hotspot("a", 0, 0.01, 5), hotspot("b", 0.01, 0.01, 5), hotspot("c", 0.01, 0, 5));
        List<Hotspot> tour = new LoopPlanner(1.0, 6).plan(start, 10_000, corners);
        assertEquals(3, tour.size());
        double perimeter = 4 * new LatLon(0, 0).metersTo(new LatLon(0, 0.01));
        assertTrue(LoopPlanner.loopLength(start, tour) < perimeter * 1.01);
    }

    @Test
    void emptyTourWhenNothingReachable() {
        List<Hotspot> tour = new LoopPlanner(1.3, 6).plan(new LatLon(-26.17, 28.03), 2000,
                List.of(hotspot("far", -25.74, 28.21, 10)));
        assertTrue(tour.isEmpty());
    }

    @Test
    void seasonCurveFindsPeakAndPhase() {
        Map<Integer, Integer> hist = DemoData.weekHistogram();
        assertEquals("peak", SeasonCurve.assess(hist, 43).phase());
        assertEquals(43, SeasonCurve.assess(hist, 43).peakWeek());
        assertEquals("building", SeasonCurve.assess(hist, 40).phase());
        assertEquals("fading", SeasonCurve.assess(hist, 46).phase());
        assertEquals("early", SeasonCurve.assess(hist, 20).phase());
        assertEquals("unknown", SeasonCurve.assess(new HashMap<>(), 43).phase());
        assertEquals(-2, SeasonCurve.circularDelta(51, 0 + 53));
    }

    @Test
    void stormCreatesUrgencyAndBestDayIsDry() {
        BestDayPicker.Outlook o = BestDayPicker.assess(DemoData.forecast(TODAY));
        assertNotNull(o.stormWarning());
        assertTrue(o.stormWarning().contains("Friday"), o.stormWarning()); // TODAY+3 is a Friday
        assertTrue(o.best().score() > 0.8);
        assertTrue(o.best().reason().startsWith("dry"));
        assertNull(BestDayPicker.assess(List.of(new DayForecast(TODAY, 0, 0, 10, 25))).stormWarning());
    }

    @Test
    void heuristicIntentParsing() {
        TripIntent t = TripIntent.heuristic("Easy 5k run from Zoo Lake this Saturday");
        assertEquals("run", t.activity());
        assertEquals(5.0, t.distanceKm(), 1e-9);
        assertEquals("Zoo Lake", t.startPlace());

        TripIntent w = TripIntent.heuristic("short stroll with the dog");
        assertEquals("walk", w.activity());
        assertEquals(3.0, w.distanceKm(), 1e-9);
        assertNull(w.startPlace());

        TripIntent c = TripIntent.heuristic("120 km bike ride from here");
        assertEquals("cycle", c.activity());
        assertEquals(TripIntent.MAX_KM, c.distanceKm(), 1e-9);
        assertNull(c.startPlace());
    }

    @Test
    void gpxIsEscapedAndContainsTrack() {
        String gpx = Gpx.write("Bloom loop <Westcliff & co>",
                List.of(new Gpx.Waypoint("Stop 1", "Look up & left", -26.17, 28.02)),
                List.of(new LatLon(-26.17, 28.02), new LatLon(-26.171, 28.021)),
                Instant.parse("2026-10-20T06:00:00Z"));
        assertTrue(gpx.contains("Bloom loop &lt;Westcliff &amp; co&gt;"));
        assertTrue(gpx.contains("<wpt lat=\"-26.170000\" lon=\"28.020000\">"));
        assertEquals(2, gpx.split("<trkpt ").length - 1);
    }

    @Test
    void knownTreeCountsLessThanAFreshSighting() {
        assertEquals(0.36, BloomScorer.treeWeight(0.9), 1e-9);
        assertEquals(0.0, BloomScorer.treeWeight(-0.2), 1e-9);
        assertEquals(BloomScorer.TREE_WEIGHT, BloomScorer.treeWeight(1.5), 1e-9);
        assertTrue(BloomScorer.treeWeight(1.0) < new BloomScorer(5).weight(obs(1, -26.17, 28.03, 0, true), TODAY));
    }

    @Test
    void demoDataIsLabelledAndDeterministic() {
        List<Observation> a = DemoData.observations(TODAY);
        List<Observation> b = DemoData.observations(TODAY);
        assertEquals(a, b);
        assertTrue(a.stream().allMatch(o -> o.place().endsWith("(demo)")));
    }

    private static Observation obs(long id, double lat, double lon, int ageDays, boolean flowering) {
        return new Observation(id, lat, lon, TODAY.minusDays(ageDays), flowering, null,
                "Westcliff, Johannesburg, Gauteng, South Africa", null);
    }

    private static Hotspot hotspot(String id, double lat, double lon, double score) {
        return new Hotspot(id, lat, lon, score, 1, TODAY, id, null, List.of());
    }
}
