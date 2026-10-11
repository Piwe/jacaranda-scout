package za.co.netbrainis.jacarandascout.core;

import java.util.List;

/** A walkable path geometry plus its length. {@code source} says whether it came from a street router or straight lines. */
public record Route(List<LatLon> geometry, double meters, String source) {}
