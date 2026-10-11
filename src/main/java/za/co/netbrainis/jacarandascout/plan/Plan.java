package za.co.netbrainis.jacarandascout.plan;

import za.co.netbrainis.jacarandascout.core.BestDayPicker;
import za.co.netbrainis.jacarandascout.core.SeasonCurve;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the phone needs for the outing. Small enough to cache for offline use.
 * {@code bloomChance} is the bloom model's 0..1 chance that jacarandas at the start are flowering today (null without it).
 */
public record Plan(
        String id,
        Instant createdAt,
        String query,
        String activity,
        double targetKm,
        double routeKm,
        int durationMin,
        double startLat,
        double startLon,
        String headline,
        String briefing,
        List<Stop> stops,
        List<double[]> geometry,
        List<HotspotPin> hotspots,
        SeasonCurve.Status season,
        List<BestDayPicker.DayScore> days,
        String stormWarning,
        Double bloomChance,
        Sources sources) {

    public record Stop(int index, String label, double lat, double lon, double score, int sightings,
                       LocalDate lastSeen, String photoUrl, String cue) {}

    public record HotspotPin(double lat, double lon, double score, int sightings, String label) {}

    /**
     * Where each piece came from, shown in the UI so the demo is honest.
     *
     * @param data    inaturalist-live | snapshot (date) | demo-synthetic
     * @param weather open-meteo-live | snapshot | demo-synthetic
     * @param routing osrm-openstreetmap | straight-line-estimate
     * @param model   e.g. "ollama qwen2.5:3b" or "templates (no local model)"
     * @param bloomModel e.g. "tabpfn-local on 412 known trees", or null when the sidecar isn't running
     */
    public record Sources(String data, String weather, String routing, String model, boolean visionUsed,
                          String bloomModel, String attribution) {}
}
