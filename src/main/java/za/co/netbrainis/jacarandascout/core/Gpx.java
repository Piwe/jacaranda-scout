package za.co.netbrainis.jacarandascout.core;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Minimal GPX 1.1 writer: waypoints with spoken-cue text plus one track. Opens in OsmAnd, Garmin, Strava, etc. */
public final class Gpx {

    public record Waypoint(String name, String description, double lat, double lon) {}

    private Gpx() {}

    public static String write(String name, List<Waypoint> waypoints, List<LatLon> track, Instant time) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"Jacaranda Scout\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <metadata>\n");
        sb.append("    <name>").append(esc(name)).append("</name>\n");
        sb.append("    <time>").append(time).append("</time>\n");
        sb.append("  </metadata>\n");
        for (Waypoint w : waypoints) {
            sb.append(String.format(Locale.ROOT, "  <wpt lat=\"%.6f\" lon=\"%.6f\">\n", w.lat(), w.lon()));
            sb.append("    <name>").append(esc(w.name())).append("</name>\n");
            if (w.description() != null) {
                sb.append("    <desc>").append(esc(w.description())).append("</desc>\n");
            }
            sb.append("  </wpt>\n");
        }
        sb.append("  <trk>\n    <name>").append(esc(name)).append("</name>\n    <trkseg>\n");
        for (LatLon p : track) {
            sb.append(String.format(Locale.ROOT, "      <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", p.lat(), p.lon()));
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n");
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
