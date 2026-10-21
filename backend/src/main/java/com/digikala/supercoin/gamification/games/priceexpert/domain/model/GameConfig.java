package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * One immutable published configuration version. Active runs are pinned to the version they started with.
 * Launch values (R-20, R-33, R-45, R-47) live in {@code LaunchConfig}; nothing here is a frontend constant.
 *
 * @param inputMs                  visible answer window after t0; the client locks input at t0 + this (brief 2026-10-20: 10,000)
 * @param acceptMs                 backend arrival deadline for an initial answer request, t0 + this inclusive (brief: 12,500).
 *                                 It limits admission only; an admitted answer may finish processing after it.
 * @param offerMs                  visible extra-life decision window after the offer start (spec: 10,000)
 * @param offerAcceptMs            backend arrival deadline for an initial purchase request, offer start + this inclusive
 *                                 (brief 2026-10-20: 15,000). Older versions carry offerMs here (strict R-43 behaviour).
 * @param paymentResolutionMs      PROPOSED: how long after the purchase admission deadline an admitted, still unresolved
 *                                 charge keeps the run waiting before it ends as continue_failed (a later debit is refunded,
 *                                 R-43 path). 0 in versions published before the brief (the run ended at the offer deadline).
 * @param inactivityMs             between questions (spec: 180,000)
 * @param offerActivationMs        PROPOSED engineering value: how long a miss may wait for the post-feedback
 *                                 offer activation (F-02) before the run ends. Reuses the inactivity value until decided.
 * @param timeoutGraceMs           handoff: a timeout is finalised no earlier than acceptDeadline + this, stamped at acceptDeadline
 * @param checkpointAutoContinueMs PROPOSED tuning value (F-01, 5,000): automatic checkpoint continuation on the client
 * @param payoutAlertThreshold     total daily DigiCoin payout alert (R-47); null = owner value pending, alert disabled
 * @param priceChangeThresholdBp   catalog price-change exclusion (R-42); null = pending, filter disabled
 * @param features                 A01 P0 feature switches
 * @param payOnCrossing            redesign 2026-10-21: every checkpoint pays its own reward once, when it is crossed (0/1/3/5,
 *                                 9 in a run that reaches 20). False in older versions (highest checkpoint only, at run end, R-17).
 * @param recordReward             redesign 2026-10-21 §13: DigiCoin for matching the all-time record frozen at run start, once
 *                                 per run, when that record is above {@code recordRewardAbove}. 0 = off (older versions).
 * @param recordRewardAbove        the record must be strictly above this (launch: 20)
 */
public record GameConfig(
        int playsPerDay,
        long inputMs,
        long acceptMs,
        long offerMs,
        long offerAcceptMs,
        long paymentResolutionMs,
        long inactivityMs,
        long offerActivationMs,
        long timeoutGraceMs,
        long checkpointAutoContinueMs,
        List<Checkpoint> checkpoints,
        ContinueRule continueRule,
        int streakReward,
        List<DifficultyBand> bands,
        Long payoutAlertThreshold,
        Integer priceChangeThresholdBp,
        Features features,
        boolean payOnCrossing,
        int recordReward,
        int recordRewardAbove) {

    /** A01 P0 switches. Haptics only take effect where the host supports them. */
    public record Features(boolean streak, boolean share, boolean products, boolean haptics) {}

    /**
     * @param requiredCorrect   score at which the checkpoint is reached
     * @param reward            with payOnCrossing: paid once when crossed; otherwise the run total of the highest one (R-17)
     * @param comparisonPercent the fixed product copy «از {x} درصد آدما باهوش‌تری!» / «تو بهتر از {x} درصد دیجی‌کالایی‌ها بودی!»
     *                          (redesign §1: product copy, not a measured statistic); null = no percentage line
     */
    public record Checkpoint(String id, int requiredCorrect, String title, String badgeAssetId, int reward, boolean enabled,
                             Integer comparisonPercent) {}

    public record ContinueRule(boolean enabled, int cost, int cap) {}

    /**
     * Eligible percentage distance for a score range (R-41). Distances are in basis points (1% = 100 bp) so that
     * boundaries are compared exactly with integer arithmetic. {@code maxBp == null} means no upper limit.
     * A band applies from {@code minScore} until the next band's minScore.
     */
    public record DifficultyBand(int minScore, int minBp, boolean minInclusive, Integer maxBp, boolean maxInclusive) {}

    /** The streak reward actually paid: zero while the A01 streak switch is off. */
    public int effectiveStreakReward() { return features == null || features.streak() ? streakReward : 0; }

    public List<Checkpoint> enabledCheckpoints() {
        return checkpoints.stream().filter(Checkpoint::enabled).sorted(Comparator.comparingInt(Checkpoint::requiredCorrect)).toList();
    }

    public int highestCheckpointReward() {
        return enabledCheckpoints().stream().mapToInt(Checkpoint::reward).max().orElse(0);
    }

    /** Most a single run can earn from checkpoints and the record reward. */
    public long runRewardCeiling() {
        long cps = payOnCrossing ? enabledCheckpoints().stream().mapToLong(Checkpoint::reward).sum() : highestCheckpointReward();
        return cps + Math.max(0, recordReward);
    }

    /**
     * R-47: plays per day × the most one run can earn + daily streak reward. Launch (redesign 2026-10-21):
     * 3 × (0 + 1 + 3 + 5 + 1) + 1 = 31. Exact: a value that does not fit throws instead of wrapping;
     * {@code ConfigValidator} rejects such a configuration before publish.
     */
    public int dailyAccountCap() {
        return Math.toIntExact(dailyAccountCapExact());
    }

    /** The cap in 64-bit arithmetic (int × int + int cannot overflow a long), for validation and admin display. */
    public long dailyAccountCapExact() {
        return (long) playsPerDay * runRewardCeiling() + effectiveStreakReward();
    }
}
