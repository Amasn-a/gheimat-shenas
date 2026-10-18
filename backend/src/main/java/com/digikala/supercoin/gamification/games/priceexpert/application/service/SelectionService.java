package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.Candidate;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CatalogProduct;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.DifficultyBand;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.Direction;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.PriceRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 *       next score's band (excluding every product of this run and the candidate). Bounded: at most
 *       {@link #CANDIDATE_LIMIT} candidates per direction are examined; each look-ahead is one indexed EXISTS query.</li>
 * </ul>
 */
public final class SelectionService {
    static final int CANDIDATE_LIMIT = 48;
    private final PriceExpertContext ctx;

    public SelectionService(PriceExpertContext ctx) { this.ctx = ctx; }

    public record Selected(CatalogProduct product, int bandIndex) {}

    public Optional<Selected> chooseComparison(int catalogVersion, GameConfig cfg, long referencePrice, int score,
                                               Set<Long> runProducts, String userId, LocalDate showDate) {
        DifficultyPolicy policy = new DifficultyPolicy(cfg.bands());
        int bandIndex = policy.bandIndexFor(score);
        DifficultyBand band = cfg.bands().get(bandIndex);

        List<Candidate> dearer = ctx.catalog().candidates(catalogVersion, DifficultyPolicy.range(band, referencePrice, Direction.DEARER),
                runProducts, userId, showDate, CANDIDATE_LIMIT);
        List<Candidate> cheaper = ctx.catalog().candidates(catalogVersion, DifficultyPolicy.range(band, referencePrice, Direction.CHEAPER),
                runProducts, userId, showDate, CANDIDATE_LIMIT);
        // Defensive exact check (the SQL range is already exact).
        dearer.removeIf(c -> !DifficultyPolicy.inBand(band, referencePrice, c.product().priceToman()));
        cheaper.removeIf(c -> !DifficultyPolicy.inBand(band, referencePrice, c.product().priceToman()));

        List<Candidate> dearerUnseen = dearer.stream().filter(c -> c.lastSeenAt() == null).toList();
        List<Candidate> cheaperUnseen = cheaper.stream().filter(c -> c.lastSeenAt() == null).toList();

        Optional<CatalogProduct> pick = pickWithDirection(dearerUnseen, cheaperUnseen, cfg, score, runProducts, catalogVersion);
        if (pick.isEmpty()) {
            // R-62 fallback: same band, products seen earlier today, least-recently seen first, direction irrelevant.
            List<Candidate> seen = new ArrayList<>();
            dearer.stream().filter(c -> c.lastSeenAt() != null).forEach(seen::add);
            cheaper.stream().filter(c -> c.lastSeenAt() != null).forEach(seen::add);
            seen.sort(Comparator.comparing(Candidate::lastSeenAt));
            pick = firstPassingLookAhead(seen, cfg, score, runProducts, catalogVersion);
        }
        return pick.map(p -> new Selected(p, bandIndex));
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
        for (Candidate c : list) {
            if (hasNextQuestion(catalogVersion, cfg, c.product().priceToman(), score + 1, with(runProducts, c.product().dkp()))) return Optional.of(c.product());
        }
        return Optional.empty();
    }

    /** True when at least one eligible product forms a valid question for {@code score} against this reference price. */
    public boolean hasNextQuestion(int catalogVersion, GameConfig cfg, long referencePrice, int score, Set<Long> exclude) {
        DifficultyBand band = new DifficultyPolicy(cfg.bands()).bandFor(score);
        List<PriceRange> ranges = List.of(DifficultyPolicy.range(band, referencePrice, Direction.DEARER),
                DifficultyPolicy.range(band, referencePrice, Direction.CHEAPER));
        return ctx.catalog().anyIn(catalogVersion, ranges, exclude);
    }

    /** The first reference of a run: unseen today preferred, and a first question must exist for score 0. */
    public Optional<CatalogProduct> chooseInitialReference(int catalogVersion, GameConfig cfg, String userId, LocalDate showDate) {
        for (CatalogProduct p : ctx.catalog().referenceCandidates(catalogVersion, userId, showDate, CANDIDATE_LIMIT)) {
            if (hasNextQuestion(catalogVersion, cfg, p.priceToman(), 0, Set.of(p.dkp()))) return Optional.of(p);
        }
        return Optional.empty();
    }

    private static Set<Long> with(Set<Long> s, long extra) {
        Set<Long> out = new HashSet<>(s);
        out.add(extra);
        return out;
    }
}
