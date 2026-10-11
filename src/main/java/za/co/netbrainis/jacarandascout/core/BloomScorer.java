package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Turns a single sighting into a "bloom evidence" weight.
 * <ul>
 *   <li>Recency decays exponentially: a sighting from {@code halfLifeDays} ago counts half.</li>
 *   <li>Sightings annotated "Flowering" count fully; un-annotated ones count half.</li>
 *   <li>An optional vision score (0..1) from a local model scales the weight between 0.2 and 1.</li>
 *   <li>A tree mapped in an earlier year, with no fresh sighting, counts {@value #TREE_WEIGHT} times the
 *       bloom model's flowering chance: less than a fresh photo, but enough to plan around.</li>
 * </ul>
 */
public final class BloomScorer {

    public static final double TREE_WEIGHT = 0.4;

    private final double halfLifeDays;

    public BloomScorer(double halfLifeDays) {
        if (halfLifeDays <= 0) {
            throw new IllegalArgumentException("halfLifeDays must be positive");
        }
        this.halfLifeDays = halfLifeDays;
    }

    public double weight(Observation o, LocalDate today) {
        return weight(o, today, Double.NaN);
    }

    /** @param visionIntensity 0..1 bloom intensity from a vision model, or NaN when unknown */
    public double weight(Observation o, LocalDate today, double visionIntensity) {
        long ageDays = Math.max(0, ChronoUnit.DAYS.between(o.observedOn(), today));
        double recency = Math.pow(0.5, ageDays / halfLifeDays);
        double phenology = o.flowering() ? 1.0 : 0.5;
        double vision = Double.isNaN(visionIntensity)
                ? 1.0
                : 0.2 + 0.8 * Math.max(0, Math.min(1, visionIntensity));
        return recency * phenology * vision;
    }

    /** @param bloomChance 0..1 chance the bloom model gives that this known tree is flowering today */
    public static double treeWeight(double bloomChance) {
        return TREE_WEIGHT * Math.max(0, Math.min(1, bloomChance));
    }
}
