package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;
import java.util.List;

/** A cluster of recent bloom sightings: a street or park worth walking through. */
public record Hotspot(
        String id,
        double lat,
        double lon,
        double score,
        int sightings,
        LocalDate lastSeen,
        String label,
        String photoUrl,
        List<Long> observationIds) {

    public LatLon position() {
        return new LatLon(lat, lon);
    }
}
