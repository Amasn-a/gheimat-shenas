package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.Checkpoint;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.ContinueRule;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.DifficultyBand;

import java.util.List;

/** The owner-approved launch configuration, published as version 1 on an empty database. */
public final class LaunchConfig {
    private LaunchConfig() {}

    public static final long INPUT_MS = 10_000;          // visible answer window (brief 2026-10-20; replaces R-45 10,500)
    public static final long ACCEPT_MS = 12_500;         // answer arrival deadline (brief 2026-10-20; replaces R-45 11,000)
    public static final long OFFER_MS = 10_000;          // visible extra-life window (spec, R-08)
    public static final long OFFER_ACCEPT_MS = 15_000;   // purchase arrival deadline (brief 2026-10-20)
    public static final long PAYMENT_RESOLUTION_MS = 60_000;   // PROPOSED wait for an admitted, unresolved charge
    public static final long INACTIVITY_MS = 180_000; // spec

    /** Redesign 2026-10-21 §9: thresholds 5/10/15/20 and rewards 0/1/3/5 approved; names and percentages proposed. */
    public static final List<Checkpoint> CHECKPOINTS = List.of(
            new Checkpoint("cp_5", 5, "بازاری تازه‌کار", "badge_5", 0, true, 50),
            new Checkpoint("cp_10", 10, "بازاری قدیمی", "badge_10", 1, true, 70),
            new Checkpoint("cp_15", 15, "قیمت‌شناس حرفه‌ای", "badge_15", 3, true, 90),
            new Checkpoint("cp_20", 20, "قیمت‌شناس اعظم", "badge_20", 5, true, 99));
    public static final int CONTINUE_COST = 5;           // redesign §1
    public static final int RECORD_REWARD = 1;           // redesign §13
    public static final int RECORD_REWARD_ABOVE = 20;    // redesign §13: a record above 20
    /**
     * R-41 distances unchanged. The band starts followed the old checkpoints 3/5/7/10 and now follow 5/10/15/20
     * (redesign §16: transition points aligned with the new checkpoints; formula and distances not reinvented).
     */
    public static final List<DifficultyBand> BANDS = List.of(
            new DifficultyBand(0, 5_000, false, null, false),     // 0–4: d > 50%
            new DifficultyBand(5, 3_000, false, 5_000, true),     // 5–9: 30% < d <= 50%
            new DifficultyBand(10, 1_500, false, 3_000, true),    // 10–14: 15% < d <= 30%
            new DifficultyBand(15, 800, false, 1_500, true),      // 15–19: 8% < d <= 15%
            new DifficultyBand(20, 300, true, 800, true));        // 20+: 3% <= d <= 8%

    public static GameConfig value() {
        return new GameConfig(
                3,                                   // R-33
                INPUT_MS, ACCEPT_MS, OFFER_MS, OFFER_ACCEPT_MS, PAYMENT_RESOLUTION_MS, INACTIVITY_MS,
                INACTIVITY_MS,                       // offer activation wait: PROPOSED, see GameConfig javadoc
                1_000,                               // timeout grace (handoff)
                5_000,                               // checkpoint auto continue: PROPOSED (F-01)
                CHECKPOINTS,
                new ContinueRule(true, CONTINUE_COST, 1),   // redesign §1: 5 DigiCoin; the cap of 1 is the current rule (R-20)
                1,                                   // R-47 streak reward
                BANDS,
                null,                                // payout alert threshold: owner value pending (R-47)
                null,                                // price-change threshold: pending (R-42)
                new GameConfig.Features(true, true, true, true),
                true, RECORD_REWARD, RECORD_REWARD_ABOVE);
    }
}
