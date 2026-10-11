package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ranks the next days for a bloom walk. Highveld spring thunderstorms (rain + gusts) strip
 * jacarandas fast, so a storm in the forecast creates urgency: go before it.
 */
public final class BestDayPicker {

    public record DayScore(LocalDate date, String day, double score, String reason) {}

    public record Outlook(List<DayScore> days, DayScore best, String stormWarning) {}

    private BestDayPicker() {}

    public static Outlook assess(List<DayForecast> forecast) {
        List<DayScore> days = new ArrayList<>();
        String stormWarning = null;
        for (DayForecast f : forecast) {
            double score = 1.0
                    - 0.5 * clamp(f.precipMm() / 8.0)
                    - 0.2 * clamp(f.precipProbPct() / 100.0)
                    - 0.2 * clamp((f.gustKmh() - 35) / 35.0)
                    - 0.2 * clamp((f.tMaxC() - 30) / 6.0);
            score = Math.round(clamp(score) * 100) / 100.0;

            List<String> reasons = new ArrayList<>();
            if (f.precipMm() < 0.5 && f.precipProbPct() < 30) {
                reasons.add("dry");
            } else {
                reasons.add(String.format(Locale.ROOT, "%.0f mm rain (%.0f%%)", f.precipMm(), f.precipProbPct()));
            }
            if (f.gustKmh() >= 45) {
                reasons.add(String.format(Locale.ROOT, "gusts %.0f km/h", f.gustKmh()));
            }
            if (f.tMaxC() >= 30) {
                reasons.add(String.format(Locale.ROOT, "hot %.0f°C, go early", f.tMaxC()));
            } else {
                reasons.add(String.format(Locale.ROOT, "%.0f°C", f.tMaxC()));
            }
            String day = f.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            days.add(new DayScore(f.date(), day, score, String.join(", ", reasons)));

            if (stormWarning == null && (f.precipMm() >= 10 || f.gustKmh() >= 60)) {
                stormWarning = "Storm risk on " + f.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                        + ": heavy rain or wind will knock blooms down. Go before then.";
            }
        }
        Optional<DayScore> best = days.stream()
                .max(Comparator.comparingDouble(DayScore::score).thenComparing(DayScore::date, Comparator.reverseOrder()));
        return new Outlook(days, best.orElse(null), stormWarning);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
