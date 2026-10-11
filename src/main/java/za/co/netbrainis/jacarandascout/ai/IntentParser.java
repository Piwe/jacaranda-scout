package za.co.netbrainis.jacarandascout.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import za.co.netbrainis.jacarandascout.core.TripIntent;

import java.util.List;
import java.util.Map;

/** Free text ("slow 4k with the dog from Zoo Lake") to a structured trip, via the local model. */
@Component
public class IntentParser {

    private static final String SYSTEM = """
            You turn a person's request for an outdoor jacaranda-viewing outing in Gauteng, South Africa,
            into JSON. Fields:
            - activity: "walk", "run" or "cycle"
            - distanceKm: number, the total loop distance they want; 0 if they did not say
            - startPlace: a suburb, park or landmark they want to start from, or "" if they said here/home/nothing
            - notes: up to 12 words of other wishes (pace, dog, kids, shade, coffee), or ""
            Convert time to distance if needed: walking ~5 km/h, running ~10 km/h, cycling ~18 km/h.
            Answer with JSON only.""";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "activity", Map.of("type", "string", "enum", List.of("walk", "run", "cycle")),
                    "distanceKm", Map.of("type", "number"),
                    "startPlace", Map.of("type", "string"),
                    "notes", Map.of("type", "string")),
            "required", List.of("activity", "distanceKm", "startPlace", "notes"));

    private final OllamaClient ollama;

    public IntentParser(OllamaClient ollama) {
        this.ollama = ollama;
    }

    public Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return new Parsed(TripIntent.heuristic(""), false);
        }
        return ollama.chatJson(SYSTEM, text, SCHEMA)
                .map(j -> new Parsed(fromJson(j), true))
                .orElseGet(() -> new Parsed(TripIntent.heuristic(text), false));
    }

    private static TripIntent fromJson(JsonNode j) {
        return new TripIntent(
                j.path("activity").asText("walk"),
                j.path("distanceKm").asDouble(0),
                j.path("startPlace").asText(""),
                j.path("notes").asText("")).normalised();
    }

    public record Parsed(TripIntent intent, boolean byModel) {}
}
