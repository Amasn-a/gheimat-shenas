package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.Candidate;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.PricePoint;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CatalogProduct;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.DifficultyBand;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.Direction;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.PriceRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side product selection (R-41, R-52, R-55, R-62).
 * <ul>
 *   <li>Comparison drawn only after reveal intent, inside the score band of the current score, never widening the band.</li>
 *   <li>Unseen-today products first. When both directions have one, direction is 50/50; within a direction the draw is
 *       uniform (random order from the repository).</li>
 *   <li>Only when the band has no unseen candidate, products seen earlier today (other runs) are reused, least-recently
 *       seen first. Products of the current run are never drawn again.</li>
 *   <li>Look-ahead: a candidate is accepted only if, after a correct answer, at least one valid next question exists in the
 *       next score's band (excluding every product of this run and the candidate).</li>
 *   <li>One definition of "a question can be revealed" ({@link #canReveal}) is used for admission, offer activation and
 *       the paid-continue grant, and reveal finds a question whenever it holds: a bounded random sample is tried first,
 *       then every candidate of the band, so a sample of dead ends is never reported as exhaustion.</li>
 * </ul>
 * Look-ahead runs against an in-memory price index of the pinned catalog version (published versions never change).
 */
public final class SelectionService {
    static final int CANDIDATE_LIMIT = 48;
    private static final int INDEX_CACHE = 4;
    private final PriceExpertContext ctx;
    private final Map<Integer, PriceIndex> indexes = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, PriceIndex> e) { return size() > INDEX_CACHE; }
    };

    public SelectionService(PriceExpertContext ctx) { this.ctx = ctx; }

    public record Selected(CatalogProduct product, int bandIndex) {}

    public Optional<Selected> chooseComparison(int catalogVersion, GameConfig cfg, long referencePrice, int score,
                                               Set<Long> runProducts, String userId, LocalDate showDate) {
        int bandIndex = new DifficultyPolicy(cfg.bands()).bandIndexFor(score);
        Optional<CatalogProduct> pick = search(catalogVersion, cfg, referencePrice, score, runProducts, userId, showDate, CANDIDATE_LIMIT);
        // the sample is not proof of exhaustion: consider every candidate of the band before reporting none
        if (pick.isEmpty()) pick = search(catalogVersion, cfg, referencePrice, score, runProducts, userId, showDate, Integer.MAX_VALUE);
        return pick.map(p -> new Selected(p, bandIndex));
    }

    private Optional<CatalogProduct> search(int catalogVersion, GameConfig cfg, long referencePrice, int score, Set<Long> runProducts,
                                            String userId, LocalDate showDate, int limit) {
        DifficultyBand band = new DifficultyPolicy(cfg.bands()).bandFor(score);
        List<Candidate> dearer = inBand(ctx.catalog().candidates(catalogVersion, DifficultyPolicy.range(band, referencePrice, Direction.DEARER),
                runProducts, userId, showDate, limit), band, referencePrice);
        List<Candidate> cheaper = inBand(ctx.catalog().candidates(catalogVersion, DifficultyPolicy.range(band, referencePrice, Direction.CHEAPER),
                runProducts, userId, showDate, limit), band, referencePrice);
        List<Candidate> dearerUnseen = dearer.stream().filter(c -> c.lastSeenAt() == null).toList();
        List<Candidate> cheaperUnseen = cheaper.stream().filter(c -> c.lastSeenAt() == null).toList();
        Optional<CatalogProduct> pick = pickWithDirection(dearerUnseen, cheaperUnseen, cfg, score, runProducts, catalogVersion);
        if (pick.isPresent()) return pick;
        // R-62 fallback: same band, products seen earlier today, least-recently seen first, direction irrelevant.
        List<Candidate> seen = new ArrayList<>();
        dearer.stream().filter(c -> c.lastSeenAt() != null).forEach(seen::add);
        cheaper.stream().filter(c -> c.lastSeenAt() != null).forEach(seen::add);
        seen.sort(Comparator.comparing(Candidate::lastSeenAt));
        return firstPassingLookAhead(seen, cfg, score, runProducts, catalogVersion);
    }

    /** Defensive exact check (the SQL range is already exact); repository lists may be immutable, so filter into a copy. */
    private static List<Candidate> inBand(List<Candidate> in, DifficultyBand band, long referencePrice) {
        return in.stream().filter(c -> DifficultyPolicy.inBand(band, referencePrice, c.product().priceToman())).toList();
    }

    private Optional<CatalogProduct> pickWithDirection(List<Candidate> dearer, List<Candidate> cheaper, GameConfig cfg, int score,
                                                       Set<Long> runProducts, int catalogVersion) {
        boolean dearerFirst;
        if (!dearer.isEmpty() && !cheaper.isEmpty()) dearerFirst = ctx.random().nextBoolean();   // 50/50 (R-41)
        else dearerFirst = !dearer.isEmpty();
        List<Candidate> first = dearerFirst ? dearer : cheaper, second = dearerFirst ? cheaper : dearer;
        Optional<CatalogProduct> p = firstPassingLookAhead(first, cfg, score, runProducts, catalogVersion);
        return p.isPresent() ? p : firstPassingLookAhead(second, cfg, score, runProducts, catalogVersion);
    }

    private Optional<CatalogProduct> firstPassingLookAhead(List<Candidate> list, GameConfig cfg, int score, Set<Long> runProducts, int catalogVersion) {
        PriceIndex idx = index(catalogVersion);
        for (Candidate c : list) {
            if (hasNext(idx, cfg, c.product().priceToman(), score + 1, runProducts, c.product().dkp())) return Optional.of(c.product());
        }
        return Optional.empty();
    }

    /**
     * True exactly when reveal at {@code score} will find a comparison: some product of the band, not in the run, that
     * itself leaves a valid next question (the look-ahead reveal applies). Used before admitting a play, opening an
     * offer and granting a paid continue, so none of them is sold for a run that would end at once with no_products.
     */
    public boolean canReveal(int catalogVersion, GameConfig cfg, long referencePrice, int score, Set<Long> runProducts) {
        PriceIndex idx = index(catalogVersion);
        DifficultyBand band = new DifficultyPolicy(cfg.bands()).bandFor(score);
        for (Direction d : Direction.values()) {
            PriceRange r = DifficultyPolicy.range(band, referencePrice, d);
            if (r.isEmpty()) continue;
            for (int i = idx.firstAtLeast(r.low()); i < idx.size() && idx.price(i) <= r.high(); i++) {
                long dkp = idx.dkp(i), price = idx.price(i);
                if (runProducts.contains(dkp) || !DifficultyPolicy.inBand(band, referencePrice, price)) continue;
                if (hasNext(idx, cfg, price, score + 1, runProducts, dkp)) return true;
            }
        }
        return false;
    }

    /** True when at least one eligible product forms a valid question for {@code score} against this reference price. */
    public boolean hasNextQuestion(int catalogVersion, GameConfig cfg, long referencePrice, int score, Set<Long> exclude) {
        return hasNext(index(catalogVersion), cfg, referencePrice, score, exclude, Long.MIN_VALUE);
    }

    /** {@code alsoExcluded}: one more product to leave out (the candidate itself), without copying the set. */
    private static boolean hasNext(PriceIndex idx, GameConfig cfg, long referencePrice, int score, Set<Long> exclude, long alsoExcluded) {
        DifficultyBand band = new DifficultyPolicy(cfg.bands()).bandFor(score);
        for (Direction d : Direction.values()) {
            PriceRange r = DifficultyPolicy.range(band, referencePrice, d);
            if (r.isEmpty()) continue;
            for (int i = idx.firstAtLeast(r.low()); i < idx.size() && idx.price(i) <= r.high(); i++) {
                long dkp = idx.dkp(i);
                if (dkp != alsoExcluded && !exclude.contains(dkp)) return true;
            }
        }
        return false;
    }

    /** The first reference of a run: unseen today preferred, and its first question must be revealable. */
    public Optional<CatalogProduct> chooseInitialReference(int catalogVersion, GameConfig cfg, String userId, LocalDate showDate) {
        for (CatalogProduct p : ctx.catalog().referenceCandidates(catalogVersion, userId, showDate, CANDIDATE_LIMIT)) {
            if (canReveal(catalogVersion, cfg, p.priceToman(), 0, Set.of(p.dkp()))) return Optional.of(p);
        }
        // the random sample is not proof that nothing is playable: try every eligible product (from a random start, so the
        // choice stays spread) before refusing the play. Only reached when the sample of 48 found nothing playable.
        PriceIndex idx = index(catalogVersion);
        int n = idx.size(), start = n == 0 ? 0 : ctx.random().nextInt(n);
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            long dkp = idx.dkp(i);
            if (canReveal(catalogVersion, cfg, idx.price(i), 0, Set.of(dkp))) return ctx.catalog().product(catalogVersion, dkp);
        }
        return Optional.empty();
    }

    private PriceIndex index(int catalogVersion) {
        synchronized (indexes) {
            PriceIndex idx = indexes.get(catalogVersion);
            if (idx != null) return idx;
        }
        PriceIndex built = PriceIndex.of(ctx.catalog().eligiblePrices(catalogVersion));
        synchronized (indexes) { indexes.put(catalogVersion, built); }
        return built;
    }

    /** Eligible products of one immutable catalog version, sorted by price. */
    record PriceIndex(long[] prices, long[] dkps) {
        static PriceIndex of(List<PricePoint> points) {
            long[] p = new long[points.size()], d = new long[points.size()];
            for (int i = 0; i < p.length; i++) { p[i] = points.get(i).priceToman(); d[i] = points.get(i).dkp(); }
            return new PriceIndex(p, d);
        }
        int size() { return prices.length; }
        long price(int i) { return prices[i]; }
        long dkp(int i) { return dkps[i]; }
        int firstAtLeast(long key) {
            int lo = 0, hi = prices.length;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (prices[mid] < key) lo = mid + 1; else hi = mid; }
            return lo;
        }
    }

}
