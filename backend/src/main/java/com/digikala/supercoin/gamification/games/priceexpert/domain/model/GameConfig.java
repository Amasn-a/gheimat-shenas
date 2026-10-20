package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * One immutable published configuration version. Active runs are pinned to the version they started with.
 * Launch values (R-20, R-33, R-45, R-47) live in {@code LaunchConfig}; nothing here is a frontend constant.
 *
 * @param inputMs                  client input window after t0 (R-45: 10,500)
 * @param acceptMs                 server acceptance window after t0 (R-45: 11,000)
 * @param offerMs                  continue-offer window once activated (spec: 10,000)
 * @param inactivityMs             between questions (spec: 180,000)
 * @param offerActivationMs        PROPOSED engineering value: how long a miss may wait for the post-feedback
 *                                 offer activation (F-02) before the run ends. Reuses the inactivity value until decided.
 * @param timeoutGraceMs           handoff: a timeout is finalised no earlier than acceptDeadline + this, stamped at acceptDeadline
 * @param checkpointAutoContinueMs PROPOSED tuning value (F-01, 5,000): automatic checkpoint continuation on the client
 * @param payoutAlertThreshold     total daily DigiCoin payout alert (R-47); null = owner value pending, alert disabled
 * @param priceChangeThresholdBp   catalog price-change exclusion (R-42); null = pending, filter disabled
 * @param features                 A01 P0 feature switches
 */
public record GameConfig(
        int playsPerDay,
        long inputMs,
        long acceptMs,
        long offerMs,
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
        Features features) {

    /** A01 P0 switches. Haptics only take effect where the host supports them. */
    public record Features(boolean streak, boolean share, boolean products, boolean haptics) {}

    /** @param requiredCorrect score at which the checkpoint is reached; reward is a total, not an increment (R-17) */
    public record Checkpoint(String id, int requiredCorrect, String title, String badgeAssetId, int reward, boolean enabled) {}

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

    /**
     * R-47: plays per day × highest checkpoint reward + daily streak reward (launch: 3 × 40 + 1 = 121). Exact: a value
     * that does not fit throws instead of wrapping; {@code ConfigValidator} rejects such a configuration before publish.
     */
    public int dailyAccountCap() {
        return Math.toIntExact(dailyAccountCapExact());
    }

    /** The cap in 64-bit arithmetic (int × int + int cannot overflow a long), for validation and admin display. */
    public long dailyAccountCapExact() {
        return (long) playsPerDay * highestCheckpointReward() + effectiveStreakReward();
    }
}
