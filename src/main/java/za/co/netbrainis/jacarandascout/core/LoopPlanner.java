package za.co.netbrainis.jacarandascout.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a loop that starts and ends at the user and threads through the most rewarding
 * hotspots within a distance budget.
 *
 * <p>This is a small "orienteering problem": maximise collected bloom score subject to
 * a length limit. We use cheapest-insertion by score-per-extra-metre, then 2-opt to untangle.
 * Straight-line distances are inflated by {@code detourFactor} to approximate street routing.
 */
public final class LoopPlanner {

    private final double detourFactor;
    private final int maxStops;

    public LoopPlanner(double detourFactor, int maxStops) {
        this.detourFactor = detourFactor;
        this.maxStops = maxStops;
    }

    public List<Hotspot> plan(LatLon start, double budgetMeters, List<Hotspot> candidates) {
        List<Hotspot> remaining = new ArrayList<>();
        for (Hotspot h : candidates) {
            if (2 * start.metersTo(h.position()) * detourFactor <= budgetMeters) {
                remaining.add(h);
            }
        }

        List<Hotspot> tour = new ArrayList<>();
        double length = 0;
        while (tour.size() < maxStops && !remaining.isEmpty()) {
            Hotspot best = null;
            int bestPos = -1;
            double bestAdded = 0;
            double bestRatio = -1;
            for (Hotspot h : remaining) {
                for (int pos = 0; pos <= tour.size(); pos++) {
                    LatLon prev = pos == 0 ? start : tour.get(pos - 1).position();
                    LatLon next = pos == tour.size() ? start : tour.get(pos).position();
                    double added = prev.metersTo(h.position()) + h.position().metersTo(next) - prev.metersTo(next);
                    if ((length + added) * detourFactor > budgetMeters) {
                        continue;
                    }
                    double ratio = h.score() / (added + 50.0);
                    if (ratio > bestRatio) {
                        bestRatio = ratio;
                        best = h;
                        bestPos = pos;
                        bestAdded = added;
                    }
                }
            }
            if (best == null) {
                break;
            }
            tour.add(bestPos, best);
            length += bestAdded;
            remaining.remove(best);
        }
        twoOpt(start, tour);
        return tour;
    }

    /** Estimated loop length in metres (straight-line × detour factor). */
    public double estimateMeters(LatLon start, List<Hotspot> tour) {
        return loopLength(start, tour) * detourFactor;
    }

    static double loopLength(LatLon start, List<Hotspot> tour) {
        List<LatLon> pts = new ArrayList<>();
        pts.add(start);
        for (Hotspot h : tour) {
            pts.add(h.position());
        }
        pts.add(start);
        return LatLon.pathMeters(pts);
    }

    private static void twoOpt(LatLon start, List<Hotspot> tour) {
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 100) {
            improved = false;
            double current = loopLength(start, tour);
            for (int i = 0; i < tour.size() - 1; i++) {
                for (int k = i + 1; k < tour.size(); k++) {
                    reverse(tour, i, k);
                    double candidate = loopLength(start, tour);
                    if (candidate + 1e-6 < current) {
                        current = candidate;
                        improved = true;
                    } else {
                        reverse(tour, i, k);
                    }
                }
            }
        }
    }

    private static void reverse(List<Hotspot> list, int i, int k) {
        while (i < k) {
            Hotspot tmp = list.get(i);
            list.set(i, list.get(k));
            list.set(k, tmp);
            i++;
            k--;
        }
    }
}
