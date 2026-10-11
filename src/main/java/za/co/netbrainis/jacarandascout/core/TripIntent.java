package za.co.netbrainis.jacarandascout.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the user asked for, e.g. "easy 4k walk from Zoo Lake with the dog".
 *
 * @param activity   walk | run | cycle
 * @param startPlace free-text place name to geocode, or null to use the device location
 */
public record TripIntent(String activity, double distanceKm, String startPlace, String notes) {

    public static final double MIN_KM = 1;
    public static final double MAX_KM = 30;

    private static final Pattern DISTANCE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|k\\b|kilomet)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("\\b(?:from|start(?:ing)? at|near|around)\\s+([A-Za-z][A-Za-z' .-]{2,40}?)(?=[,.!?]|\\s+(?:with|and|on|this|tomorrow|today|for|in)\\b|$)",
            Pattern.CASE_INSENSITIVE);

    /** Normalises values, e.g. from an LLM, into safe ranges. */
    public TripIntent normalised() {
        String a = normaliseActivity(activity);
        double d = distanceKm > 0 ? distanceKm : defaultDistance(a);
        d = Math.max(MIN_KM, Math.min(MAX_KM, d));
        String place = startPlace == null || startPlace.isBlank() || "null".equalsIgnoreCase(startPlace.trim())
                ? null : startPlace.trim();
        return new TripIntent(a, d, place, notes);
    }

    /** Rule-based parse used when no local model is running. */
    public static TripIntent heuristic(String text) {
        String t = text == null ? "" : text;
        String lower = t.toLowerCase(Locale.ROOT);
        String activity = lower.contains("cycl") || lower.contains("bike") || lower.contains("ride") ? "cycle"
                : lower.contains("run") || lower.contains("jog") ? "run"
                : "walk";
        double km = 0;
        Matcher m = DISTANCE.matcher(t);
        if (m.find()) {
            km = Double.parseDouble(m.group(1).replace(',', '.'));
        }
        String place = null;
        Matcher f = FROM.matcher(t);
        if (f.find()) {
            place = f.group(1).trim();
            if (place.equalsIgnoreCase("here") || place.equalsIgnoreCase("home") || place.equalsIgnoreCase("me")) {
                place = null;
            }
        }
        return new TripIntent(activity, km, place, null).normalised();
    }

    static String normaliseActivity(String a) {
        if (a == null) {
            return "walk";
        }
        String l = a.toLowerCase(Locale.ROOT);
        if (l.startsWith("run") || l.startsWith("jog")) return "run";
        if (l.startsWith("cycl") || l.startsWith("bik") || l.startsWith("ride")) return "cycle";
        return "walk";
    }

    static double defaultDistance(String activity) {
        return switch (activity) {
            case "run" -> 5;
            case "cycle" -> 15;
            default -> 3;
        };
    }

    /** Minutes per km used for time estimates. */
    public double paceMinPerKm() {
        return switch (activity) {
            case "run" -> 6.5;
            case "cycle" -> 3.5;
            default -> 13; // ambling, looking up at trees
        };
    }
}
