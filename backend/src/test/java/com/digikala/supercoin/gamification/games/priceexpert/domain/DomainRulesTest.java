package com.digikala.supercoin.gamification.games.priceexpert.domain;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.DifficultyBand;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.LaunchConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Question;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.ConfigValidator;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.Direction;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.StreakPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Pure domain rules: T17 band boundaries, T16 equal prices, T30 checkpoint totals, T34 streak, cap formula, config invariants. */
class DomainRulesTest {
    static final List<DifficultyBand> B = LaunchConfig.value().bands();

    @Test void t17_everyBandBoundaryIsExact() {
        // reference 1,000,000: candidate 1,000,000 × (1 + d)
        long p = 1_000_000;
        // 0–2: d > 50%
        assertFalse(DifficultyPolicy.inBand(B.get(0), p, 1_500_000));   // exactly 50% → not above
        assertTrue(DifficultyPolicy.inBand(B.get(0), p, 1_500_001));
        assertFalse(DifficultyPolicy.inBand(B.get(0), p, 1_499_999));
        // 3–4: 30% < d ≤ 50%
        assertFalse(DifficultyPolicy.inBand(B.get(1), p, 1_300_000));
        assertTrue(DifficultyPolicy.inBand(B.get(1), p, 1_300_001));
        assertTrue(DifficultyPolicy.inBand(B.get(1), p, 1_500_000));
        assertFalse(DifficultyPolicy.inBand(B.get(1), p, 1_500_001));
        // 5–6: 15% < d ≤ 30%
        assertFalse(DifficultyPolicy.inBand(B.get(2), p, 1_150_000));
        assertTrue(DifficultyPolicy.inBand(B.get(2), p, 1_150_001));
        assertTrue(DifficultyPolicy.inBand(B.get(2), p, 1_300_000));
        assertFalse(DifficultyPolicy.inBand(B.get(2), p, 1_300_001));
        // 7–9: 8% < d ≤ 15%
        assertFalse(DifficultyPolicy.inBand(B.get(3), p, 1_080_000));
        assertTrue(DifficultyPolicy.inBand(B.get(3), p, 1_080_001));
        assertTrue(DifficultyPolicy.inBand(B.get(3), p, 1_150_000));
        assertFalse(DifficultyPolicy.inBand(B.get(3), p, 1_150_001));
        // 10+: 3% ≤ d ≤ 8%  (inclusive lower bound)
        assertFalse(DifficultyPolicy.inBand(B.get(4), p, 1_029_999));
        assertTrue(DifficultyPolicy.inBand(B.get(4), p, 1_030_000));
        assertTrue(DifficultyPolicy.inBand(B.get(4), p, 1_080_000));
        assertFalse(DifficultyPolicy.inBand(B.get(4), p, 1_080_001));
        // cheaper direction uses min as the base: 1,030,000 vs 1,000,000 is 3% either way round
        assertTrue(DifficultyPolicy.inBand(B.get(4), 1_030_000, 1_000_000));
        assertFalse(DifficultyPolicy.inBand(B.get(4), 1_029_999, 1_000_000));
        // a pair that binary floating point classifies wrongly: 100 × (103/100 − 1) = 3.0000000000000027
        assertTrue(DifficultyPolicy.inBand(B.get(4), 100, 103));
        assertNotEquals(3.0, 100.0 * (103.0 / 100.0 - 1.0));   // the float computation is not exactly 3: the integer check avoids it
    }

    @Test void t17_rangeMatchesTheExactRuleForEveryCandidate() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            long ref = 3_500 + rnd.nextInt(5_000_000);
            for (DifficultyBand b : B) for (Direction d : Direction.values()) {
                DifficultyPolicy.PriceRange r = DifficultyPolicy.range(b, ref, d);
                long lo = d == Direction.DEARER ? ref + 1 : Math.max(1, ref / 3);
                long hi = d == Direction.DEARER ? ref * 3 : ref - 1;
                long step = Math.max(1, (hi - lo) / 4000);
                for (long c = lo; c <= hi; c += step) {
                    boolean exact = DifficultyPolicy.inBand(b, ref, c);
                    long cc = c;
                    assertEquals(exact, r.contains(c), () -> "ref " + ref + " cand " + cc + " band " + b + " dir " + d);
                }
                // and the edges themselves
                if (!r.isEmpty()) {
                    assertTrue(DifficultyPolicy.inBand(b, ref, r.low()), "low edge in band");
                    if (r.high() != Long.MAX_VALUE) assertTrue(DifficultyPolicy.inBand(b, ref, r.high()), "high edge in band");
                    if (d == Direction.DEARER && r.low() - 1 > ref) assertFalse(DifficultyPolicy.inBand(b, ref, r.low() - 1), "just below the low edge");
                    if (d == Direction.CHEAPER && r.high() + 1 < ref) assertFalse(DifficultyPolicy.inBand(b, ref, r.high() + 1), "just above the high edge");
                }
            }
        }
    }

    @Test void bandForScoreFollowsScoreNotCheckpoints() {
        DifficultyPolicy p = new DifficultyPolicy(B);
        int[] expected = {0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4};
        for (int s = 0; s < expected.length; s++) assertEquals(expected[s], p.bandIndexFor(s), "score " + s);
        assertEquals(4, p.bandIndexFor(120));
    }

    @Test void t16_equalPricesAcceptBothChoices() {
        Question q = new Question();
        q.referencePrice = 1_945_000; q.comparisonPrice = 1_945_000;
        assertTrue(q.isCorrect(Choice.higher));
        assertTrue(q.isCorrect(Choice.lower));
        q.comparisonPrice = 1_945_001;
        assertTrue(q.isCorrect(Choice.higher));
        assertFalse(q.isCorrect(Choice.lower));
    }

    @Test void capFormulaAndPayOnCrossing() {
        GameConfig c = LaunchConfig.value();
        assertTrue(c.payOnCrossing());
        assertEquals(List.of(0, 1, 3, 5), c.enabledCheckpoints().stream().map(GameConfig.Checkpoint::reward).toList());
        assertEquals(List.of(5, 10, 15, 20), c.enabledCheckpoints().stream().map(GameConfig.Checkpoint::requiredCorrect).toList());
        assertEquals(10, c.runRewardCeiling());   // each crossed checkpoint pays its own: 0 + 1 + 3 + 5, plus the record reward 1
        assertEquals(31, c.dailyAccountCap());    // 3 × (9 + 1) + 1 (R-47, redesign 2026-10-21)
        assertEquals(5, c.continueRule().cost());
        // a version from before the redesign: highest checkpoint only (R-17)
        GameConfig legacy = new GameConfig(c.playsPerDay(), c.inputMs(), c.acceptMs(), c.offerMs(), c.offerAcceptMs(), c.paymentResolutionMs(), c.inactivityMs(),
                c.offerActivationMs(), c.timeoutGraceMs(), c.checkpointAutoContinueMs(), c.checkpoints(), c.continueRule(), c.streakReward(), c.bands(),
                null, null, c.features(), false, 0, c.recordRewardAbove());
        assertEquals(5, legacy.runRewardCeiling());
        assertEquals(16, legacy.dailyAccountCap());
    }

    @Test void t34_streakIsConsecutiveStartDaysAndBreaks() {
        LocalDate d = LocalDate.of(2026, 10, 20);
        assertEquals(new StreakPolicy.Streak(0, null, false), StreakPolicy.compute(List.of(), d));
        assertEquals(3, StreakPolicy.compute(List.of(d, d.minusDays(1), d.minusDays(2)), d).days());
        StreakPolicy.Streak alive = StreakPolicy.compute(List.of(d.minusDays(1), d.minusDays(2)), d);   // not played yet today
        assertEquals(2, alive.days());
        assertFalse(alive.lostNotice());
        StreakPolicy.Streak broken = StreakPolicy.compute(List.of(d.minusDays(2), d.minusDays(3)), d);
        assertEquals(0, broken.days());
        assertTrue(broken.lostNotice());
        // out-of-order arrival does not matter
        assertEquals(3, StreakPolicy.compute(new ArrayList<>(List.of(d.minusDays(2), d, d.minusDays(1))), d).days());
    }

    @Test void configInvariantsAreEnforcedWithoutClamping() {
        GameConfig ok = LaunchConfig.value();
        assertTrue(ConfigValidator.validate(ok, ConfigValidator.Bounds.pending()).isEmpty());
        var bands = new ArrayList<>(ok.bands());
        bands.set(4, new DifficultyBand(20, 0, true, 800, true));          // minimum 0 would allow equal prices
        GameConfig bad = new GameConfig(0, 10_500, 11_000, ok.offerMs(), 10_000, ok.paymentResolutionMs(), ok.inactivityMs(), ok.offerActivationMs(), ok.timeoutGraceMs(),
                ok.checkpointAutoContinueMs(), List.of(new GameConfig.Checkpoint("cp_5", 5, "a", "badge_5", -1, true, null), new GameConfig.Checkpoint("cp_3", 3, "b", "badge_3", 5, true, null)),
                new GameConfig.ContinueRule(true, 0, -1), -1, bands, null, null, ok.features(), ok.payOnCrossing(), ok.recordReward(), ok.recordRewardAbove());
        var e = ConfigValidator.validate(bad, ConfigValidator.Bounds.pending());
        assertTrue(e.containsKey("plays_per_day"));
        assertTrue(e.containsKey("input_ms"), "timer is fixed in P0: the obsolete 10,500 ms input deadline is refused");
        assertTrue(e.containsKey("accept_ms"), "the obsolete 11,000 ms acceptance is refused");
        assertTrue(e.containsKey("offer_accept_ms"), "the purchase arrival deadline is fixed at 15,000 ms");
        assertTrue(e.containsKey("checkpoints[0].reward"));
        assertTrue(e.containsKey("checkpoints[1].required_correct"), "ascending steps");
        assertTrue(e.containsKey("continue.cost"));
        assertTrue(e.containsKey("continue.cap"));
        assertTrue(e.containsKey("streak_reward"));
        assertTrue(e.containsKey("bands[4].min_bp"));
        // owner bounds apply only once set
        var bounded = ConfigValidator.validate(ok, new ConfigValidator.Bounds(2, null, null, null, null));
        assertEquals("must be at most 2", bounded.get("plays_per_day"));
    }

    @Test void dailyCapNeverWraps() {
        // review of 716d889: 3 × 1,500,000,000 + 1 wrapped to 205,032,705 in 32-bit arithmetic and passed validation
        GameConfig b = LaunchConfig.value();
        List<GameConfig.Checkpoint> cps = b.checkpoints().stream()
                .map(c -> new GameConfig.Checkpoint(c.id(), c.requiredCorrect(), c.title(), c.badgeAssetId(), 1_500_000_000, c.enabled(), null)).toList();
        GameConfig huge = new GameConfig(3, b.inputMs(), b.acceptMs(), b.offerMs(), b.offerAcceptMs(), b.paymentResolutionMs(), b.inactivityMs(), b.offerActivationMs(), b.timeoutGraceMs(),
                b.checkpointAutoContinueMs(), cps, b.continueRule(), 1, b.bands(), b.payoutAlertThreshold(), b.priceChangeThresholdBp(), b.features(), b.payOnCrossing(), b.recordReward(), b.recordRewardAbove());
        assertEquals(18_000_000_004L, huge.dailyAccountCapExact());   // 3 × (4 × 1.5e9 + 1) + 1, each checkpoint paid
        assertThrows(ArithmeticException.class, huge::dailyAccountCap, "an oversized cap throws instead of wrapping");
        assertTrue(ConfigValidator.validate(huge, ConfigValidator.Bounds.pending()).containsKey("daily_account_cap"), "rejected before publish");
        assertTrue(ConfigValidator.validate(b, ConfigValidator.Bounds.pending()).isEmpty());
    }
}
