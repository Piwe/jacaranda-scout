package za.co.netbrainis.jacarandascout.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import za.co.netbrainis.jacarandascout.ScoutProperties;
import za.co.netbrainis.jacarandascout.core.Observation;

import java.net.URI;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional: most sightings aren't annotated "Flowering". A local vision model looks at each photo
 * and rates how much purple bloom it shows, so a green tree photographed yesterday doesn't count
 * as much as a tree in full flower. The photos go to localhost only.
 */
@Component
public class BloomVision {

    private static final Logger log = LoggerFactory.getLogger(BloomVision.class);

    private static final String PROMPT = """
            Look at this photo of a jacaranda tree. How much of the tree's canopy is covered in purple/lilac
            flowers right now? Answer JSON: {"bloom": 0-10} where 0 = no flowers (green or bare),
            5 = partly flowering, 10 = completely covered in purple.""";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("bloom", Map.of("type", "integer", "minimum", 0, "maximum", 10)),
            "required", List.of("bloom"));

    private final OllamaClient ollama;
    private final RestClient http;
    private final ScoutProperties props;

    public BloomVision(OllamaClient ollama, @Qualifier("openDataRestClient") RestClient http, ScoutProperties props) {
        this.ollama = ollama;
        this.http = http;
        this.props = props;
    }

    public boolean enabled() {
        return props.vision().enabled() && ollama.enabled() && !props.offline();
    }

    /** Observation id → bloom intensity 0..1 for the newest sightings that have photos. */
    public Map<Long, Double> rate(List<Observation> observations) {
        Map<Long, Double> out = new HashMap<>();
        if (!enabled()) {
            return out;
        }
        List<Observation> withPhotos = observations.stream()
                .filter(o -> o.photoUrl() != null)
                .sorted(Comparator.comparing(Observation::observedOn).reversed())
                .limit(props.vision().maxPhotos())
                .toList();
        for (Observation o : withPhotos) {
            try {
                byte[] img = http.get().uri(URI.create(o.photoUrl())).retrieve().body(byte[].class);
                if (img == null) {
                    continue;
                }
                Optional<JsonNode> r = ollama.visionJson(props.vision().model(), PROMPT, img, SCHEMA);
                if (r.isEmpty()) {
                    break; // model not available: stop trying
                }
                int bloom = Math.max(0, Math.min(10, r.get().path("bloom").asInt(5)));
                out.put(o.id(), bloom / 10.0);
            } catch (RuntimeException e) {
                log.debug("Skipping photo {}: {}", o.photoUrl(), e.getMessage());
            }
        }
        log.info("Vision model rated {} sighting photos", out.size());
        return out;
    }
}
