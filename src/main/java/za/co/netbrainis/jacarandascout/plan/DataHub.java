package za.co.netbrainis.jacarandascout.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.netbrainis.jacarandascout.ScoutProperties;
import za.co.netbrainis.jacarandascout.core.DayForecast;
import za.co.netbrainis.jacarandascout.core.DemoData;
import za.co.netbrainis.jacarandascout.core.LatLon;
import za.co.netbrainis.jacarandascout.core.Observation;
import za.co.netbrainis.jacarandascout.data.INaturalistClient;
import za.co.netbrainis.jacarandascout.data.OpenMeteoClient;
import za.co.netbrainis.jacarandascout.data.SnapshotStore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live open data → on-disk snapshot → synthetic demo data, in that order.
 * Every result says which tier it came from.
 */
@Component
public class DataHub {

    private static final Logger log = LoggerFactory.getLogger(DataHub.class);
    static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    public record Sourced<T>(T data, String source) {}

    private final INaturalistClient inat;
    private final OpenMeteoClient weather;
    private final SnapshotStore snapshots;
    private final ScoutProperties props;

    public DataHub(INaturalistClient inat, OpenMeteoClient weather, SnapshotStore snapshots, ScoutProperties props) {
        this.inat = inat;
        this.weather = weather;
        this.snapshots = snapshots;
        this.props = props;
    }

    public Sourced<List<Observation>> observations(LatLon at, LocalDate today) {
        if (!props.offline()) {
            try {
                List<Observation> live = inat.recentSightings(at.lat(), at.lon(), today.minusDays(props.lookbackDays()), today);
                snapshots.saveObservations(mergeWithSnapshot(live, today));
                return new Sourced<>(live, "inaturalist-live");
            } catch (RuntimeException e) {
                log.warn("iNaturalist unreachable: {}", e.getMessage());
            }
        }
        var snap = snapshots.loadObservations();
        if (snap.isPresent()) {
            LocalDate savedOn = LocalDate.ofInstant(snap.get().savedAt(), SAST);
            LocalDate cutoff = (savedOn.isBefore(today) ? savedOn : today).minusDays(props.lookbackDays());
            double radiusM = props.searchRadiusKm() * 1000;
            List<Observation> nearby = snap.get().data().stream()
                    .filter(o -> !o.observedOn().isBefore(cutoff))
                    .filter(o -> o.position().metersTo(at) <= radiusM)
                    .toList();
            if (!nearby.isEmpty()) {
                return new Sourced<>(nearby, "snapshot " + savedOn);
            }
        }
        return new Sourced<>(DemoData.observations(today), "demo-synthetic");
    }

    /** All-time tree locations (any year). No demo fallback: made-up trees would mislead the bloom model. */
    public Sourced<List<Observation>> trees(LatLon at) {
        if (!props.offline()) {
            try {
                List<Observation> live = inat.knownTrees(at.lat(), at.lon());
                Map<Long, Observation> byId = new LinkedHashMap<>();
                snapshots.loadTrees().ifPresent(s -> s.data().forEach(o -> byId.put(o.id(), o)));
                live.forEach(o -> byId.put(o.id(), o));
                snapshots.saveTrees(new ArrayList<>(byId.values()));
                return new Sourced<>(live, "inaturalist-live");
            } catch (RuntimeException e) {
                log.warn("iNaturalist trees unreachable: {}", e.getMessage());
            }
        }
        double radiusM = props.searchRadiusKm() * 1000;
        return snapshots.loadTrees()
                .map(s -> new Sourced<>(s.data().stream().filter(o -> o.position().metersTo(at) <= radiusM).toList(),
                        "snapshot " + LocalDate.ofInstant(s.savedAt(), SAST)))
                .orElseGet(() -> new Sourced<>(List.of(), "none"));
    }

    public Sourced<Map<Integer, Integer>> histogram(LatLon at) {
        if (!props.offline()) {
            try {
                Map<Integer, Integer> live = inat.floweringWeekHistogram(at.lat(), at.lon());
                if (!live.isEmpty()) {
                    snapshots.saveHistogram(live);
                    return new Sourced<>(live, "inaturalist-live");
                }
            } catch (RuntimeException e) {
                log.warn("iNaturalist histogram unreachable: {}", e.getMessage());
            }
        }
        return snapshots.loadHistogram()
                .map(s -> new Sourced<>(s.data(), "snapshot"))
                .orElseGet(() -> new Sourced<>(DemoData.weekHistogram(), "demo-synthetic"));
    }

    public Sourced<List<DayForecast>> forecast(LatLon at, LocalDate today) {
        if (!props.offline()) {
            try {
                List<DayForecast> live = weather.sevenDay(at.lat(), at.lon());
                if (!live.isEmpty()) {
                    snapshots.saveForecast(live);
                    return new Sourced<>(live, "open-meteo-live");
                }
            } catch (RuntimeException e) {
                log.warn("Open-Meteo unreachable: {}", e.getMessage());
            }
        }
        var snap = snapshots.loadForecast();
        if (snap.isPresent()) {
            List<DayForecast> upcoming = snap.get().data().stream().filter(d -> !d.date().isBefore(today)).toList();
            if (!upcoming.isEmpty()) {
                return new Sourced<>(upcoming, "snapshot");
            }
        }
        return new Sourced<>(DemoData.forecast(today), "demo-synthetic");
    }

    /** Keep sightings from earlier syncs (e.g. Pretoria and Joburg) so offline planning works in both. */
    private List<Observation> mergeWithSnapshot(List<Observation> live, LocalDate today) {
        Map<Long, Observation> byId = new LinkedHashMap<>();
        LocalDate keepFrom = today.minusDays(60);
        snapshots.loadObservations().ifPresent(s -> s.data().stream()
                .filter(o -> !o.observedOn().isBefore(keepFrom))
                .forEach(o -> byId.put(o.id(), o)));
        live.forEach(o -> byId.put(o.id(), o));
        return new ArrayList<>(byId.values());
    }
}
