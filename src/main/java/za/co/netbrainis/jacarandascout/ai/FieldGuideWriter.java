package za.co.netbrainis.jacarandascout.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import za.co.netbrainis.jacarandascout.core.BestDayPicker;
import za.co.netbrainis.jacarandascout.core.Hotspot;
import za.co.netbrainis.jacarandascout.core.SeasonCurve;
import za.co.netbrainis.jacarandascout.core.TripIntent;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the short briefing and the spoken cue for each stop. The model only gets numbers we
 * computed ourselves (grounding), and is told to stay off the screen: cues are meant to be heard
 * through one earphone while you look up at the trees.
 */
@Component
public class FieldGuideWriter {

    public record Narrative(String headline, String briefing, List<String> cues, boolean byModel) {}

    private static final String SYSTEM = """
            You are a warm, concise Highveld field guide helping someone see jacaranda blossom in Gauteng.
            You get FACTS as JSON. Use only those facts: never invent street names, numbers or birds.
            Write:
            - headline: max 8 words, inviting, no emoji.
            - briefing: 2-3 short sentences (max 60 words) saying when to go and why, based on bestDay,
              stormWarning and season. Encourage leaving the phone in the pocket.
            - cues: exactly one cue per stop, in stop order. Each cue max 22 words, spoken aloud when the
              walker arrives. Tell them what to look or listen for (canopy, purple carpet underfoot,
              bees in the flowers, the smell after rain). Mention the place label once.
            - bloomChanceThisWeek is a model estimate (0-100%) that trees here are flowering. A stop with
              evidence "mapped tree" has no fresh sighting: say the model expects blooms, don't claim one was seen.
            Answer with JSON only.""";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "headline", Map.of("type", "string"),
                    "briefing", Map.of("type", "string"),
                    "cues", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("headline", "briefing", "cues"));

    private final OllamaClient ollama;
    private final ObjectMapper mapper;

    public FieldGuideWriter(OllamaClient ollama, ObjectMapper mapper) {
        this.ollama = ollama;
        this.mapper = mapper;
    }

    /** Stops last reported longer ago than this are trees mapped in earlier years, not fresh sightings. */
    static final int RECENT_DAYS = 21;

    /** @param bloomChance 0..1 bloom-model chance of flowering at the start point today, or null when unavailable */
    public Narrative write(TripIntent intent, List<Hotspot> stops, double routeKm, SeasonCurve.Status season,
                           BestDayPicker.Outlook outlook, Double bloomChance, LocalDate today) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("activity", intent.activity());
        facts.put("routeKm", Math.round(routeKm * 10) / 10.0);
        facts.put("userNotes", intent.notes() == null ? "" : intent.notes());
        facts.put("season", Map.of("phase", season.phase(), "note", season.note()));
        if (bloomChance != null) {
            facts.put("bloomChanceThisWeek", percent(bloomChance) + "%");
        }
        if (outlook.best() != null) {
            facts.put("bestDay", Map.of("day", outlook.best().day(), "date", outlook.best().date().toString(),
                    "weather", outlook.best().reason()));
        }
        facts.put("stormWarning", outlook.stormWarning() == null ? "" : outlook.stormWarning());
        List<Map<String, Object>> stopFacts = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Hotspot h = stops.get(i);
            long daysAgo = ChronoUnit.DAYS.between(h.lastSeen(), today);
            stopFacts.add(Map.of(
                    "stop", i + 1,
                    "place", h.label(),
                    "evidence", daysAgo <= RECENT_DAYS ? "recent sighting" : "mapped tree",
                    "sightings", h.sightings(),
                    "lastReportedDaysAgo", daysAgo));
        }
        facts.put("stops", stopFacts);

        String user;
        try {
            user = "FACTS:\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
        } catch (Exception e) {
            user = "FACTS: " + facts;
        }

        Narrative fallback = template(intent, stops, routeKm, season, outlook, bloomChance, today);
        return ollama.chatJson(SYSTEM, user, SCHEMA)
                .map(j -> merge(j, fallback, stops.size()))
                .orElse(fallback);
    }

    private static Narrative merge(JsonNode j, Narrative fallback, int stopCount) {
        String headline = text(j, "headline", fallback.headline());
        String briefing = text(j, "briefing", fallback.briefing());
        List<String> cues = new ArrayList<>();
        JsonNode arr = j.path("cues");
        for (int i = 0; i < stopCount; i++) {
            String c = arr.isArray() && i < arr.size() ? arr.get(i).asText("").trim() : "";
            cues.add(c.isEmpty() ? fallback.cues().get(i) : c);
        }
        return new Narrative(headline, briefing, cues, true);
    }

    private static long percent(double p) {
        return Math.round(Math.max(0, Math.min(1, p)) * 100);
    }

    private static String text(JsonNode j, String field, String fallback) {
        String v = j.path(field).asText("").trim();
        return v.isEmpty() ? fallback : v;
    }

    static Narrative template(TripIntent intent, List<Hotspot> stops, double routeKm, SeasonCurve.Status season,
                              BestDayPicker.Outlook outlook, Double bloomChance, LocalDate today) {
        String headline = stops.isEmpty()
                ? "No blooms in range this time"
                : String.format(Locale.ROOT, "%.1f km purple %s loop", routeKm, intent.activity());
        StringBuilder b = new StringBuilder();
        if (outlook.best() != null) {
            b.append("Best day: ").append(outlook.best().day()).append(" (").append(outlook.best().reason()).append("). ");
        }
        b.append(season.note());
        if (bloomChance != null) {
            b.append(" Bloom model: ").append(percent(bloomChance)).append("% chance the trees here are flowering.");
        }
        if (!stops.isEmpty()) {
            b.append(" Pocket the phone. It will speak at each stop.");
        }
        List<String> cues = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Hotspot h = stops.get(i);
            long days = ChronoUnit.DAYS.between(h.lastSeen(), today);
            if (days > RECENT_DAYS) {
                cues.add(String.format(Locale.ROOT, "Stop %d, %s. Jacarandas were mapped here, last reported %s %d. No fresh sighting yet: look up and tell iNaturalist what you see.",
                        i + 1, h.label(), h.lastSeen().getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH),
                        h.lastSeen().getYear()));
                continue;
            }
            String when = days <= 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
            cues.add(String.format(Locale.ROOT, "Stop %d, %s. %d recent sighting%s, last one %s. Look up, then look down for the purple carpet.",
                    i + 1, h.label(), h.sightings(), h.sightings() == 1 ? "" : "s", when));
        }
        return new Narrative(headline, b.toString().trim(), cues, false);
    }
}
