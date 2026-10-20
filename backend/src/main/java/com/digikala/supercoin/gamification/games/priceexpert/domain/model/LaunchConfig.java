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

    public static GameConfig value() {
        return new GameConfig(
                3,                                   // R-33
                INPUT_MS, ACCEPT_MS, OFFER_MS, OFFER_ACCEPT_MS, PAYMENT_RESOLUTION_MS, INACTIVITY_MS,
                INACTIVITY_MS,                       // offer activation wait: PROPOSED, see GameConfig javadoc
                1_000,                               // timeout grace (handoff)
                5_000,                               // checkpoint auto continue: PROPOSED (F-01)
                List.of(                             // R-20
                        new Checkpoint("cp_3", 3, "قیمت‌یاب تازه‌کار", "badge_3", 5, true),
                        new Checkpoint("cp_5", 5, "قیمت‌یاب", "badge_5", 10, true),
                        new Checkpoint("cp_7", 7, "کارشناس بازار", "badge_7", 20, true),
                        new Checkpoint("cp_10", 10, "قیمت‌شناس واقعی", "badge_10", 40, true)),
                new ContinueRule(true, 10, 1),       // R-20
                1,                                   // R-47 streak reward
                List.of(                             // R-41
                        new DifficultyBand(0, 5_000, false, null, false),     // 0–2: d > 50%
                        new DifficultyBand(3, 3_000, false, 5_000, true),     // 3–4: 30% < d <= 50%
                        new DifficultyBand(5, 1_500, false, 3_000, true),     // 5–6: 15% < d <= 30%
                        new DifficultyBand(7, 800, false, 1_500, true),       // 7–9: 8% < d <= 15%
                        new DifficultyBand(10, 300, true, 800, true)),        // 10+: 3% <= d <= 8%
                null,                                // payout alert threshold: owner value pending (R-47)
                null,                                // price-change threshold: pending (R-42)
                new GameConfig.Features(true, true, true, true));
    }
}
