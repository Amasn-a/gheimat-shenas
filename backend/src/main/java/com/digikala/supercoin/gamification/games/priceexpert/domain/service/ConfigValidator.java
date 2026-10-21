package com.digikala.supercoin.gamification.games.priceexpert.domain.service;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.Checkpoint;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig.DifficultyBand;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.LaunchConfig;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural invariants for a configuration draft (spec «انتشار تنظیمات», handoff admin API). Never clamps:
 * an invalid value is reported, not corrected. Numeric upper bounds are owner decisions that are still open
 * (R-47/R-53 follow-up); they apply only when configured in {@link Bounds}, otherwise only invariants are checked.
 */
public final class ConfigValidator {

    /** Upper bounds per money/quota field. {@code null} = owner value pending; not enforced, reported as pending. */
    public record Bounds(Integer maxPlaysPerDay, Integer maxCheckpointReward, Integer maxContinueCost,
                         Integer maxContinueCap, Integer maxStreakReward) {
        public static Bounds pending() { return new Bounds(null, null, null, null, null); }
    }

    private static final Pattern ID = Pattern.compile("^[a-z0-9_-]{1,40}$");

    private ConfigValidator() {}

    public static Map<String, String> validate(GameConfig c, Bounds bounds) {
        Map<String, String> e = new LinkedHashMap<>();
        if (c == null) { e.put("config", "missing"); return e; }

        if (c.playsPerDay() < 1) e.put("plays_per_day", "must be at least 1");
        max(e, "plays_per_day", c.playsPerDay(), bounds.maxPlaysPerDay());

        // Timer values are not configurable in P0 (spec A01; timing contract of 2026-10-20). Reject any change instead of fixing it.
        if (c.inputMs() != LaunchConfig.INPUT_MS) e.put("input_ms", "fixed at " + LaunchConfig.INPUT_MS + " in P0");
        if (c.acceptMs() != LaunchConfig.ACCEPT_MS) e.put("accept_ms", "fixed at " + LaunchConfig.ACCEPT_MS + " in P0");
        if (c.offerMs() != LaunchConfig.OFFER_MS) e.put("offer_ms", "fixed at " + LaunchConfig.OFFER_MS + " in P0 (spec)");
        if (c.offerAcceptMs() != LaunchConfig.OFFER_ACCEPT_MS) e.put("offer_accept_ms", "fixed at " + LaunchConfig.OFFER_ACCEPT_MS + " in P0");
        if (c.paymentResolutionMs() < 10_000 || c.paymentResolutionMs() > 600_000) e.put("payment_resolution_ms", "must be between 10000 and 600000");
        if (c.inactivityMs() != LaunchConfig.INACTIVITY_MS) e.put("inactivity_ms", "fixed at 180000 in P0 (spec)");
        if (c.offerActivationMs() < c.offerMs()) e.put("offer_activation_ms", "must be at least the offer window");
        if (c.timeoutGraceMs() < 0 || c.timeoutGraceMs() > 5_000) e.put("timeout_grace_ms", "must be between 0 and 5000");
        if (c.checkpointAutoContinueMs() < 1_000) e.put("checkpoint_auto_continue_ms", "must be at least 1000");

        List<Checkpoint> cps = c.checkpoints();
        if (cps == null || cps.isEmpty()) e.put("checkpoints", "at least one checkpoint is required");
        else {
            Set<String> ids = new HashSet<>();
            Set<Integer> steps = new HashSet<>();
            int prev = 0;
            for (int i = 0; i < cps.size(); i++) {
                Checkpoint cp = cps.get(i);
                String f = "checkpoints[" + i + "]";
                if (cp.id() == null || !ID.matcher(cp.id()).matches()) e.put(f + ".id", "lowercase id required");
                else if (!ids.add(cp.id())) e.put(f + ".id", "duplicate id");
                if (cp.title() == null || cp.title().isBlank() || cp.title().length() > 40) e.put(f + ".title", "1–40 characters");
                if (cp.badgeAssetId() == null || !ID.matcher(cp.badgeAssetId()).matches()) e.put(f + ".badge_asset_id", "invalid badge reference");
                if (cp.reward() < 0) e.put(f + ".reward", "must not be negative");
                max(e, f + ".reward", cp.reward(), bounds.maxCheckpointReward());
                if (cp.requiredCorrect() < 1) e.put(f + ".required_correct", "must be a positive integer");
                if (cp.comparisonPercent() != null && (cp.comparisonPercent() < 1 || cp.comparisonPercent() > 99))
                    e.put(f + ".comparison_percent", "must be between 1 and 99");
                if (cp.enabled()) {
                    if (!steps.add(cp.requiredCorrect())) e.put(f + ".required_correct", "steps must be unique");
                    else if (cp.requiredCorrect() <= prev) e.put(f + ".required_correct", "enabled steps must be in ascending order");
                    prev = Math.max(prev, cp.requiredCorrect());
                }
            }
            if (cps.stream().noneMatch(Checkpoint::enabled)) e.put("checkpoints", "at least one enabled checkpoint is required");
        }

        if (c.continueRule() == null) e.put("continue", "missing");
        else {
            if (c.continueRule().cost() <= 0) e.put("continue.cost", "must be positive");
            if (c.continueRule().cap() < 0) e.put("continue.cap", "must not be negative");
            max(e, "continue.cost", c.continueRule().cost(), bounds.maxContinueCost());
            max(e, "continue.cap", c.continueRule().cap(), bounds.maxContinueCap());
        }
        if (c.streakReward() < 0) e.put("streak_reward", "must not be negative");
        if (!c.payOnCrossing()) e.put("pay_on_crossing", "fixed at true since the redesign of 2026-10-21");
        if (c.recordReward() < 0) e.put("record_reward", "must not be negative");
        max(e, "record_reward", c.recordReward(), bounds.maxCheckpointReward());
        if (c.recordRewardAbove() < 0) e.put("record_reward_above", "must not be negative");
        max(e, "streak_reward", c.streakReward(), bounds.maxStreakReward());

        List<DifficultyBand> bands = c.bands();
        if (bands == null || bands.isEmpty()) e.put("bands", "at least one band is required");
        else {
            if (bands.get(0).minScore() != 0) e.put("bands[0].min_score", "first band must start at score 0");
            for (int i = 0; i < bands.size(); i++) {
                DifficultyBand b = bands.get(i);
                String f = "bands[" + i + "]";
                if (i > 0 && b.minScore() <= bands.get(i - 1).minScore()) e.put(f + ".min_score", "must increase");
                if (b.minBp() <= 0) e.put(f + ".min_bp", "must be greater than 0 so equal prices cannot be forced");
                if (b.maxBp() != null) {
                    boolean empty = b.maxBp() < b.minBp() || (b.maxBp() == b.minBp() && !(b.minInclusive() && b.maxInclusive()));
                    if (empty) e.put(f + ".max_bp", "must be above the minimum");
                }
            }
        }
        if (c.features() == null) e.put("features", "missing");
        // The derived daily cap is stored as INTEGER and compared against credits: it must fit, never wrap (R-47).
        if (c.checkpoints() != null && c.dailyAccountCapExact() > Integer.MAX_VALUE)
            e.put("daily_account_cap", "plays per day × highest checkpoint reward + streak reward must be at most " + Integer.MAX_VALUE);
        if (c.payoutAlertThreshold() != null && c.payoutAlertThreshold() <= 0) e.put("payout_alert_threshold", "must be positive or unset");
        if (c.priceChangeThresholdBp() != null && c.priceChangeThresholdBp() <= 0) e.put("price_change_threshold_bp", "must be positive or unset");
        return e;
    }

    private static void max(Map<String, String> e, String field, int value, Integer bound) {
        if (bound != null && value > bound && !e.containsKey(field)) e.put(field, "must be at most " + bound);
    }
}
