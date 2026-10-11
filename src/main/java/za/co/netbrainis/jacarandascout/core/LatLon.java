package za.co.netbrainis.jacarandascout.core;

/** A WGS84 coordinate. */
public record LatLon(double lat, double lon) {

    private static final double EARTH_RADIUS_M = 6_371_000d;

    /** Great-circle distance in metres (haversine). */
    public double metersTo(LatLon other) {
        double phi1 = Math.toRadians(lat);
        double phi2 = Math.toRadians(other.lat);
        double dPhi = Math.toRadians(other.lat - lat);
        double dLambda = Math.toRadians(other.lon - lon);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Total length of a polyline in metres. */
    public static double pathMeters(java.util.List<LatLon> path) {
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).metersTo(path.get(i));
        }
        return total;
    }
}
