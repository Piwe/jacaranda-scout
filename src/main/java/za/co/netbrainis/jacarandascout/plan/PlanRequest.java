package za.co.netbrainis.jacarandascout.plan;

/**
 * @param query free text, e.g. "5k run from Zoo Lake"
 * @param lat   device latitude, or null
 * @param lon   device longitude, or null
 */
public record PlanRequest(String query, Double lat, Double lon) {}
