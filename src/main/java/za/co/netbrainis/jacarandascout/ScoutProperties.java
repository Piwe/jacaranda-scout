package za.co.netbrainis.jacarandascout;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables live in application.yml under {@code scout.*}.
 *
 * @param offline when true, never touch the network: use the saved snapshot or demo data
 */
@ConfigurationProperties(prefix = "scout")
public record ScoutProperties(
        String taxonName,
        double defaultLat,
        double defaultLon,
        int lookbackDays,
        double searchRadiusKm,
        double clusterRadiusM,
        double halfLifeDays,
        double detourFactor,
        int maxStops,
        String dataDir,
        String userAgent,
        boolean offline,
        int httpTimeoutSeconds,
        Ollama ollama,
        Vision vision,
        Routing routing,
        BloomModel bloomModel) {

    /** Local open-weight model served by Ollama. Swap {@code model} for any chat model you've pulled. */
    public record Ollama(boolean enabled, String baseUrl, String model, int timeoutSeconds) {}

    /** Optional: let a local vision model look at sighting photos and rate how purple they are. */
    public record Vision(boolean enabled, String model, int maxPhotos) {}

    /**
     * Optional TabPFN sidecar ({@code bloom-model/}): predicts the chance a jacaranda is flowering at a
     * place and date, so trees mapped in earlier years count even when nobody photographed them this week.
     */
    public record BloomModel(boolean enabled, String baseUrl, int timeoutSeconds) {}

    /** OSRM-compatible routers (FOSSGIS public instances by default, or your own self-hosted OSRM). */
    public record Routing(boolean enabled, String footUrl, String bikeUrl, String geocodeUrl) {}
}
