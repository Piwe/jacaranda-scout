package za.co.netbrainis.jacarandascout.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import za.co.netbrainis.jacarandascout.ScoutProperties;
import za.co.netbrainis.jacarandascout.core.LatLon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asks the local TabPFN sidecar ({@code bloom-model/server.py}) how likely a jacaranda is to be
 * flowering at each point on a date. TabPFN learned this from every phenology-annotated jacaranda
 * on iNaturalist plus the weather before each sighting. Runs on localhost, so it works offline.
 */
@Component
public class BloomPredictor {

    private static final Logger log = LoggerFactory.getLogger(BloomPredictor.class);

    /** @param model e.g. "tabpfn-local"; @param weather open-meteo-live | cached | partial | none */
    public record Prediction(List<Double> probabilities, String model, String weather) {}

    private final RestClient http;
    private final ScoutProperties props;

    public BloomPredictor(@Qualifier("bloomModelRestClient") RestClient http, ScoutProperties props) {
        this.http = http;
        this.props = props;
    }

    public boolean enabled() {
        return props.bloomModel().enabled();
    }

    public Optional<Prediction> predict(List<LatLon> points, LocalDate date) {
        if (!enabled() || points.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> body = points.stream()
                .map(p -> Map.<String, Object>of("lat", p.lat(), "lon", p.lon(), "date", date.toString()))
                .toList();
        try {
            JsonNode r = http.post().uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", body))
                    .retrieve()
                    .body(JsonNode.class);
            if (r == null || r.path("probabilities").size() != points.size()) {
                return Optional.empty();
            }
            List<Double> probs = new ArrayList<>();
            r.path("probabilities").forEach(v -> probs.add(v.asDouble()));
            return Optional.of(new Prediction(probs, r.path("model").asText("tabpfn"), r.path("weather").asText("")));
        } catch (RuntimeException e) {
            log.warn("Bloom model unreachable at {}: {}", props.bloomModel().baseUrl(), e.getMessage());
            return Optional.empty();
        }
    }

    /** For /api/status: is the sidecar up, and what was it trained on? */
    public Optional<JsonNode> health() {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(http.get().uri("/health").retrieve().body(JsonNode.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
