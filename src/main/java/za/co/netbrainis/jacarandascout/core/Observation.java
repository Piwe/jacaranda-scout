package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;

/**
 * One citizen-science sighting of a jacaranda (from iNaturalist, a local snapshot, or demo data).
 *
 * @param flowering true when the observer annotated it as "Flowering"
 */
public record Observation(
        long id,
        double lat,
        double lon,
        LocalDate observedOn,
        boolean flowering,
        String photoUrl,
        String place,
        String url) {

    public LatLon position() {
        return new LatLon(lat, lon);
    }
}
