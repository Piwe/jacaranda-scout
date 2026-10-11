package za.co.netbrainis.jacarandascout.ai;

import org.junit.jupiter.api.Test;
import za.co.netbrainis.jacarandascout.core.BestDayPicker;
import za.co.netbrainis.jacarandascout.core.Hotspot;
import za.co.netbrainis.jacarandascout.core.SeasonCurve;
import za.co.netbrainis.jacarandascout.core.TripIntent;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldGuideWriterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void mappedTreeCueDoesNotClaimAFreshSighting() {
        Hotspot fresh = new Hotspot("a", -26.17, 28.03, 1, 2, TODAY.minusDays(1), "Westcliff, Johannesburg", null, List.of());
        Hotspot mapped = new Hotspot("b", -26.18, 28.02, 0.3, 4, LocalDate.of(2024, 10, 12), "Parkview, Johannesburg", null, List.of());
        FieldGuideWriter.Narrative n = FieldGuideWriter.template(
                new TripIntent("walk", 4, null, null), List.of(fresh, mapped), 4.2,
                new SeasonCurve.Status("unknown", 0, 42, 0, "Not enough data."),
                new BestDayPicker.Outlook(List.of(), null, null), 0.78, TODAY);

        assertTrue(n.cues().get(0).contains("last one yesterday"));
        assertTrue(n.cues().get(1).contains("October 2024"));
        assertFalse(n.cues().get(1).contains("recent sighting"));
        assertTrue(n.briefing().contains("78% chance"));
    }
}
