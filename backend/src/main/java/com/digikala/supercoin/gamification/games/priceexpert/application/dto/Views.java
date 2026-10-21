package com.digikala.supercoin.gamification.games.priceexpert.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Player API response shapes (serialized as snake_case). The comparison price of an OPEN question never appears in
 * any of these records; it is revealed only through {@link LastResult} after an immutable answer or timeout.
 */
public final class Views {
    private Views() {}

    public record ProductCard(String displayTitle, String imageUrl) {}

    public record ReferenceCard(String displayTitle, String imageUrl, long price) {}

    /** t0 / deadlines are absolute server instants; the client maps them with serverTime (R-38/R-45). */
    public record QuestionView(String questionId, ProductCard product, Instant t0, Instant inputDeadline, Instant acceptDeadline) {}

    /** {@code direction} = how the comparison relates to the reference: higher / lower / equal. */
    public record LastResult(String questionId, String result, String choice, long revealedPrice, boolean equalPrices, String direction,
                             Instant settledAt) {}

    /** comparisonPercent: fixed product copy of the checkpoint (redesign §9), not a measured statistic; null = none. */
    public record CheckpointView(String checkpointId, String title, String badgeAssetId, int rewardAmount, int requiredCorrect,
                                 Integer comparisonPercent) {}

    /**
     * deadline = end of the visible decision window; acceptDeadline = last arrival instant of a purchase request
     * (inclusive); resolveBy = an admitted charge still unresolved then ends the run (continue_failed, refund if it lands).
     */
    public record OfferView(String offerId, String state, Instant openedAt, Instant deadline, Instant acceptDeadline, Instant resolveBy, int cost) {}

    public record OperationRef(String operationId, String status) {}

    public record ContinueRuleView(boolean enabled, int cost, int cap) {}

    /** A01 switches of the configuration version the view belongs to; the client hides what is off, the server refuses it. */
    public record FeaturesView(boolean streak, boolean share, boolean products, boolean haptics) {}

    public record RunConfigView(int configVersion, int catalogVersion, long inputMs, long acceptMs, long offerMs, long offerAcceptMs,
                                long checkpointAutoContinueMs, List<CheckpointView> checkpoints, ContinueRuleView continueRule,
                                FeaturesView features, boolean payOnCrossing, int recordReward, int recordRewardAbove) {}

    public record RunView(String runId, String state, String finishReason, String noOfferReason, int score, int record,
                          int continuesUsed, CheckpointView highestCheckpoint, ReferenceCard reference, QuestionView question,
                          LastResult lastResult, OfferView offer, OperationRef operation, Instant awaitingOfferUntil,
                          boolean onThisDevice, RunConfigView runConfig, Instant serverTime) {}

    public record ReplacedRun(String runId, int score, int checkpointReward) {}

    public record StartView(RunView run, ReplacedRun replacedRun, Quota quota) {}

    /**
     * checkpointReward: the credit registered when this answer crossed checkpointReached (null when it pays nothing);
     * recordReward: the credit when this answer matched the record frozen at run start (redesign §13), else null.
     */
    public record AnswerView(String questionId, String result, String choice, boolean equalPrices, long revealedPrice, String direction,
                             int score, CheckpointView checkpointReached, RewardStatus checkpointReward, RewardStatus recordReward,
                             boolean continuePossible, String noOfferReason, boolean runFinished, RunView run, Instant serverTime) {}

    public record ActivationView(OfferView offer, Long balance, String noOfferReason, RunView run, Instant serverTime) {}

    /**
     * status: pending · succeeded · failed · refunded · expired_unconfirmed (handoff).
     * chargeStatus: unknown · confirmed · failed — what is known about the debit. refundStatus: none · pending · succeeded.
     */
    public record OperationView(String operationId, String offerId, String status, boolean definitive, String chargeStatus, String refundStatus,
                                int chargedAmount, int refundedAmount, Long balance, Instant admittedAt, Instant resolveBy, RunView run,
                                Instant serverTime) {}

    public record Quota(int playsLeft, int playsPerDay, Instant resetsAt) {}

    public record ActiveRun(String runId, String state, boolean onThisDevice, int score, CheckpointView checkpoint, Instant offerDeadline,
                            String operationId) {}

    public record CampaignBanner(String campaignId, String name, int checkpointRequiredCorrect, String checkpointTitle, long codeAmountToman) {}

    public record Access(String status, String message) {}

    /** firstVisit = the account has not completed the onboarding (stored on the account, not on the device). */
    public record BootstrapView(Access access, boolean firstVisit, Quota quota, int record, CheckpointView bestCheckpoint,
                                DailyRewardView dailyReward, Long balance, ActiveRun activeRun, RunConfigView config, CampaignBanner campaign,
                                String lastRunId, Instant serverTime) {}

    public record DailyRewardDay(java.time.LocalDate date, boolean attended, boolean today) {}

    /**
     * state: claimable (an earned reward waits for the claim) · processing (claimed, credit not confirmed yet) · claimed
     * (the latest claim is confirmed) · delayed (the latest claim is late or under review) · none. amount: of the claimable
     * rewards, or of the latest claim. streakDays: consecutive attended days (spec rule). days: the last 7 business days.
     */
    public record DailyRewardView(String state, int amount, int streakDays, List<DailyRewardDay> days, List<String> operationIds,
                                  Instant claimedAt, Instant serverTime) {}

    /** status: pending · succeeded · delayed · none · cancelled. For a run total, delayed if any part is. */
    public record RewardStatus(int amount, int heldAmount, String status) {}

    public record NextGoal(int remaining, String title, int reward, int requiredCorrect) {}

    public record CampaignCode(String status, String code, long amountToman, String terms, Instant expiresAt) {}

    public record ResultView(String runId, int score, String reason, String noOfferReason, int record, boolean isNewRecord,
                             int previousRecord, CheckpointView checkpoint, RewardStatus checkpointReward,
                             int continuesUsed, boolean continueLimitUsed, NextGoal nextGoal, Quota quota, Long balance,
                             CampaignCode campaignCode, Instant finishedAt, Instant serverTime) {}

    /** questionId: the turn this product was the comparison of (null for the run's first reference product). */
    public record ProductItem(int position, String role, String questionId, String productId, String variantId, String displayTitle, String title,
                              String imageUrl, Long gamePrice, String result, boolean isLoss, String link) {}

    public record ProductsView(String runId, List<ProductItem> items, String lossProductId, Instant serverTime) {}

    public record ShareView(int score, String checkpointTitle, String badgeAssetId, String gameName, String deepLink) {}
}
