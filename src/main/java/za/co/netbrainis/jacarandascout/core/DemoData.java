package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * SYNTHETIC sightings around well-known jacaranda suburbs of Johannesburg and Pretoria.
 * Used only when there is no network and no saved snapshot, so the app still demos offline.
 * Every label is suffixed "(demo)" so it is never mistaken for real observations.
 */
public final class DemoData {

    private record Seed(String name, double lat, double lon, int count, int freshness) {}

    private static final List<Seed> SEEDS = List.of(
            new Seed("Westcliff, Johannesburg", -26.1722, 28.0262, 14, 2),
            new Seed("Parkview, Johannesburg", -26.1628, 28.0305, 10, 3),
            new Seed("Zoo Lake, Johannesburg", -26.1570, 28.0405, 8, 4),
            new Seed("Emmarentia, Johannesburg", -26.1605, 28.0095, 9, 3),
            new Seed("Melville, Johannesburg", -26.1765, 28.0085, 7, 5),
            new Seed("Saxonwold, Johannesburg", -26.1610, 28.0480, 6, 6),
            new Seed("Parktown, Johannesburg", -26.1800, 28.0420, 5, 8),
            new Seed("Arcadia, Pretoria", -25.7445, 28.2105, 12, 2),
            new Seed("Union Buildings, Pretoria", -25.7400, 28.2122, 9, 3),
            new Seed("Groenkloof, Pretoria", -25.7725, 28.2085, 8, 4),
            new Seed("Brooklyn, Pretoria", -25.7700, 28.2360, 7, 5),
            new Seed("Muckleneuk, Pretoria", -25.7640, 28.2190, 6, 6));

    private DemoData() {}

    public static List<Observation> observations(LocalDate today) {
        Random rnd = new Random(20261006L);
        List<Observation> out = new ArrayList<>();
        long id = 9_000_000L;
        for (Seed s : SEEDS) {
            for (int i = 0; i < s.count(); i++) {
                double dLat = rnd.nextGaussian() * 0.0011;  // ~120 m: a few tree-lined blocks
                double dLon = rnd.nextGaussian() * 0.0012;
                int age = Math.max(0, (int) Math.round(Math.abs(rnd.nextGaussian()) * s.freshness()));
                boolean flowering = rnd.nextDouble() < 0.85;
                out.add(new Observation(id++, s.lat() + dLat, s.lon() + dLon, today.minusDays(age), flowering,
                        null, s.name() + " (demo)", null));
            }
        }
        return out;
    }

    /** Synthetic multi-year week-of-year histogram peaking in mid/late October (week 43). */
    public static Map<Integer, Integer> weekHistogram() {
        Map<Integer, Integer> m = new HashMap<>();
        for (int w = 1; w <= 53; w++) {
            double z = (w - 43) / 2.2;
            int v = (int) Math.round(120 * Math.exp(-0.5 * z * z));
            if (v > 0) {
                m.put(w, v);
            }
        }
        return m;
    }

    /** Neutral, clearly-fake forecast for offline demos. */
    public static List<DayForecast> forecast(LocalDate today) {
        double[][] p = {{0, 10, 18, 27}, {0, 5, 22, 28}, {2, 40, 30, 26}, {14, 80, 62, 24}, {1, 30, 25, 23}, {0, 10, 20, 25}, {0, 5, 18, 27}};
        List<DayForecast> out = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            out.add(new DayForecast(today.plusDays(i), p[i][0], p[i][1], p[i][2], p[i][3]));
        }
        return out;
    }
}
