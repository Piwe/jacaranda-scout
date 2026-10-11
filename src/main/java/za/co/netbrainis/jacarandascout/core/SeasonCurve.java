package za.co.netbrainis.jacarandascout.core;

import java.util.Map;

/**
 * Places "this week" on the multi-year bloom curve built from a week-of-year histogram of
 * flowering sightings (every year of iNaturalist data in the area).
 */
public final class SeasonCurve {

    public record Status(String phase, int peakWeek, int currentWeek, double progress, String note) {}

    private SeasonCurve() {}

    public static Status assess(Map<Integer, Integer> weekCounts, int currentWeek) {
        int total = weekCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total < 10) {
            return new Status("unknown", 0, currentWeek, 0,
                    "Not enough historical sightings to place this week on the bloom curve.");
        }

        // circular 3-week smoothing so a single noisy week doesn't win
        int peakWeek = 1;
        double peakValue = -1;
        for (int w = 1; w <= 53; w++) {
            double v = count(weekCounts, wrap(w - 1)) + 2.0 * count(weekCounts, w) + count(weekCounts, wrap(w + 1));
            if (v > peakValue) {
                peakValue = v;
                peakWeek = w;
            }
        }

        // progress measured over the half-year centred on the peak, so the season doesn't wrap at New Year
        int seasonStart = wrap(peakWeek - 26);
        double cumulative = 0;
        double seasonTotal = 0;
        for (int i = 0; i < 53; i++) {
            int w = wrap(seasonStart + i);
            seasonTotal += count(weekCounts, w);
        }
        for (int i = 0; i < 53; i++) {
            int w = wrap(seasonStart + i);
            cumulative += count(weekCounts, w);
            if (w == currentWeek) {
                break;
            }
        }
        double progress = seasonTotal == 0 ? 0 : cumulative / seasonTotal;
        int delta = circularDelta(currentWeek, peakWeek);

        String phase;
        String note;
        if (progress < 0.03) {
            phase = "early";
            note = "Too early: most trees are still green.";
        } else if (progress > 0.97) {
            phase = "over";
            note = "The season has mostly finished. Look for late bloomers and purple carpets.";
        } else if (Math.abs(delta) <= 1) {
            phase = "peak";
            note = "Peak week on the historical curve. Go now.";
        } else if (delta < 0) {
            phase = "building";
            note = "Building toward peak in about " + (-delta) + " week" + (delta == -1 ? "" : "s") + ".";
        } else {
            phase = "fading";
            note = "Past peak by " + delta + " week" + (delta == 1 ? "" : "s") + ". Blooms are thinning and fallen flowers carpet the pavements.";
        }
        return new Status(phase, peakWeek, currentWeek, Math.round(progress * 100) / 100.0, note);
    }

    private static int count(Map<Integer, Integer> m, int w) {
        return m.getOrDefault(w, 0);
    }

    private static int wrap(int w) {
        int r = ((w - 1) % 53 + 53) % 53;
        return r + 1;
    }

    /** current - peak, wrapped into [-26, 26]. */
    static int circularDelta(int current, int peak) {
        int d = current - peak;
        while (d > 26) d -= 53;
        while (d < -26) d += 53;
        return d;
    }
}
