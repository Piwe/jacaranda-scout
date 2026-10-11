package za.co.netbrainis.jacarandascout.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import za.co.netbrainis.jacarandascout.ScoutProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin client for a local Ollama server (https://ollama.com, MIT-licensed) running open-weight models.
 * Uses Ollama's structured outputs: we pass a JSON schema and the model's tokens are constrained to it,
 * which is what makes a 3B model reliable enough for this job.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient http;
    private final ScoutProperties props;
    private final ObjectMapper mapper;

    public OllamaClient(@Qualifier("ollamaRestClient") RestClient http, ScoutProperties props, ObjectMapper mapper) {
        this.http = http;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return props.ollama().enabled();
    }

    public String model() {
        return props.ollama().model();
    }

    /** Lists locally pulled models; empty when Ollama isn't running. */
    public List<String> installedModels() {
        try {
            JsonNode tags = http.get().uri("/api/tags").retrieve().body(JsonNode.class);
            return tags.path("models").findValuesAsText("name");
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Chat completion constrained to {@code schema}. Empty when the model is unavailable or returns junk. */
    public Optional<JsonNode> chatJson(String system, String user, Map<String, Object> schema) {
        if (!enabled()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.ollama().model());
        body.put("stream", false);
        body.put("format", schema);
        body.put("options", Map.of("temperature", 0.4, "num_ctx", 4096));
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        return call("/api/chat", body).map(r -> r.path("message").path("content").asText("")).flatMap(this::parse);
    }

    /** Single-image prompt to a local vision model (e.g. gemma3:4b, qwen2.5vl:3b, llava). */
    public Optional<JsonNode> visionJson(String model, String prompt, byte[] image, Map<String, Object> schema) {
        if (!enabled()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("format", schema);
        body.put("prompt", prompt);
        body.put("images", List.of(Base64.getEncoder().encodeToString(image)));
        body.put("options", Map.of("temperature", 0.0));
        return call("/api/generate", body).map(r -> r.path("response").asText("")).flatMap(this::parse);
    }

    private Optional<JsonNode> call(String path, Map<String, Object> body) {
        try {
            long t0 = System.currentTimeMillis();
            JsonNode res = http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            log.info("Ollama {} {} answered in {} ms", body.get("model"), path, System.currentTimeMillis() - t0);
            return Optional.ofNullable(res);
        } catch (RuntimeException e) {
            log.warn("Ollama unavailable at {} ({}). Falling back to templates.", props.ollama().baseUrl(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> parse(String content) {
        try {
            return Optional.of(mapper.readTree(content));
        } catch (Exception e) {
            log.warn("Model returned non-JSON: {}", content.length() > 200 ? content.substring(0, 200) : content);
            return Optional.empty();
        }
    }
}
