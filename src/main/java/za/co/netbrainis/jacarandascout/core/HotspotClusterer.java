package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Greedy leader clustering: strongest sightings seed clusters, weaker ones join the nearest
 * cluster centroid within {@code radiusMeters}. Cheap, deterministic, and good enough for
 * a few hundred points on a laptop.
 */
public final class HotspotClusterer {

    private final double radiusMeters;

    public HotspotClusterer(double radiusMeters) {
        this.radiusMeters = radiusMeters;
    }

    public List<Hotspot> cluster(List<Observation> observations, ToDoubleFunction<Observation> weight) {
        List<Observation> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingDouble(weight).reversed()
                .thenComparingLong(Observation::id));

        List<Builder> clusters = new ArrayList<>();
        for (Observation o : sorted) {
            double w = weight.applyAsDouble(o);
            if (w <= 0) {
                continue;
            }
            Builder nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Builder c : clusters) {
                double d = c.centroid().metersTo(o.position());
                if (d <= radiusMeters && d < nearestDist) {
                    nearest = c;
                    nearestDist = d;
                }
            }
            if (nearest == null) {
                nearest = new Builder();
                clusters.add(nearest);
            }
            nearest.add(o, w);
        }

        List<Hotspot> result = new ArrayList<>();
        for (Builder b : clusters) {
            result.add(b.build());
        }
        result.sort(Comparator.comparingDouble(Hotspot::score).reversed().thenComparing(Hotspot::id));
        return result;
    }

    /** "Westcliff, Johannesburg, Gauteng, South Africa" becomes "Westcliff, Johannesburg". */
    static String shortLabel(String place) {
        if (place == null || place.isBlank()) {
            return null;
        }
        String[] parts = place.split(",");
        String first = parts[0].trim();
        if (parts.length > 1 && first.length() < 30) {
            return first + ", " + parts[1].trim();
        }
        return first;
    }

    private static final class Builder {
        private double sumW;
        private double sumLat;
        private double sumLon;
        private LocalDate lastSeen;
        private String photo;
        private double photoWeight = -1;
        private final List<Long> ids = new ArrayList<>();
        private final Map<String, Double> labelWeights = new HashMap<>();

        LatLon centroid() {
            return new LatLon(sumLat / sumW, sumLon / sumW);
        }

        void add(Observation o, double w) {
            sumW += w;
            sumLat += o.lat() * w;
            sumLon += o.lon() * w;
            ids.add(o.id());
            if (lastSeen == null || o.observedOn().isAfter(lastSeen)) {
                lastSeen = o.observedOn();
            }
            if (o.photoUrl() != null && w > photoWeight) {
                photo = o.photoUrl();
                photoWeight = w;
            }
            String label = shortLabel(o.place());
            if (label != null) {
                labelWeights.merge(label, w, Double::sum);
            }
        }

        Hotspot build() {
            LatLon c = centroid();
            String label = labelWeights.entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(String.format(java.util.Locale.ROOT, "Bloom spot %.4f, %.4f", c.lat(), c.lon()));
            String id = String.format(java.util.Locale.ROOT, "h%.4f_%.4f", c.lat(), c.lon());
            double score = Math.round(sumW * 100.0) / 100.0;
            return new Hotspot(id, c.lat(), c.lon(), score, ids.size(), lastSeen, label, photo, List.copyOf(ids));
        }
    }
}
