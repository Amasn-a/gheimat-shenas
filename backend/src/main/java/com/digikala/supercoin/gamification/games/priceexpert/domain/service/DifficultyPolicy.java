package com.digikala.supercoin.gamification.games.priceexpert.domain.service;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.DifficultyBand;

import java.util.List;

/**
 * R-41 / R-55 / R-62 distance rule. For positive prices A and B: d = 100 × (max/min − 1) percent.
 * All comparisons use exact integer arithmetic on basis points, never floating point, so a pair on a
 * boundary (for example exactly 3%) is classified exactly. Prices fit in 10 digits; 10^10 × 2·10^4 fits a long.
 */
public final class DifficultyPolicy {

    public enum Direction { DEARER, CHEAPER }

    /** Inclusive price interval [low, high]; empty when low > high. */
    public record PriceRange(long low, long high) {
        public boolean isEmpty() { return low > high; }
        public boolean contains(long p) { return p >= low && p <= high; }
    }

    private final List<DifficultyBand> bands;

    public DifficultyPolicy(List<DifficultyBand> bands) {
        this.bands = List.copyOf(bands);
    }

    public int bandIndexFor(int score) {
        int idx = 0;
        for (int i = 0; i < bands.size(); i++) if (bands.get(i).minScore() <= score) idx = i;
        return idx;
    }

    public DifficultyBand bandFor(int score) { return bands.get(bandIndexFor(score)); }

    /** Exact membership test for a (reference, candidate) pair. Equal prices are never in a validated band (minBp > 0). */
    public static boolean inBand(DifficultyBand b, long reference, long candidate) {
        if (reference <= 0 || candidate <= 0) throw new IllegalArgumentException("prices must be positive");
        long hi = Math.max(reference, candidate), lo = Math.min(reference, candidate);
        // d_bp = 10000 × (hi − lo) / lo  → compare 10000 × (hi − lo) against bound × lo
        long lhs = 10_000L * (hi - lo);
        long minRhs = (long) b.minBp() * lo;
        boolean aboveMin = b.minInclusive() ? lhs >= minRhs : lhs > minRhs;
        if (!aboveMin) return false;
        if (b.maxBp() == null) return true;
        long maxRhs = (long) b.maxBp() * lo;
        return b.maxInclusive() ? lhs <= maxRhs : lhs < maxRhs;
    }

    /** The exact inclusive price interval of candidates in the given direction from a reference price. */
    public static PriceRange range(DifficultyBand b, long p, Direction dir) {
        long minBp = b.minBp();
        if (dir == Direction.DEARER) {
            // candidate B > p:  10000(B − p) {>,>=} minBp·p  and  10000(B − p) {<=,<} maxBp·p
            long low = b.minInclusive() ? ceilDiv((10_000L + minBp) * p, 10_000L) : floorDiv((10_000L + minBp) * p, 10_000L) + 1;
            long high;
            if (b.maxBp() == null) high = Long.MAX_VALUE;
            else {
                long num = (10_000L + b.maxBp()) * p;
                high = b.maxInclusive() ? floorDiv(num, 10_000L) : ceilDiv(num, 10_000L) - 1;
            }
            low = Math.max(low, p + 1);
            return new PriceRange(low, high);
        } else {
            // candidate B < p:  10000(p − B) {>,>=} minBp·B   ⇔  B(10000 + minBp) {<,<=} 10000p
            long num = 10_000L * p;
            long high = b.minInclusive() ? floorDiv(num, 10_000L + minBp) : ceilDiv(num, 10_000L + minBp) - 1;
            long low;
            if (b.maxBp() == null) low = 1;
            else low = b.maxInclusive() ? ceilDiv(num, 10_000L + b.maxBp()) : floorDiv(num, 10_000L + b.maxBp()) + 1;
            high = Math.min(high, p - 1);
            return new PriceRange(Math.max(low, 1), high);
        }
    }

    private static long floorDiv(long a, long b) { return Math.floorDiv(a, b); }
    private static long ceilDiv(long a, long b) { return -Math.floorDiv(-a, b); }
}
