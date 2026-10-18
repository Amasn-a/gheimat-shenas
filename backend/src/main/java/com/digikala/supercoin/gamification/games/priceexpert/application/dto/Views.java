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

    public record CheckpointView(String checkpointId, String title, String badgeAssetId, int rewardAmount, int requiredCorrect) {}

    public record OfferView(String offerId, String state, Instant openedAt, Instant deadline, int cost) {}

    public record OperationRef(String operationId, String status) {}

    public record ContinueRuleView(boolean enabled, int cost, int cap) {}

    public record RunConfigView(int configVersion, int catalogVersion, long inputMs, long acceptMs, long offerMs,
                                long checkpointAutoContinueMs, List<CheckpointView> checkpoints, ContinueRuleView continueRule) {}

    public record RunView(String runId, String state, String finishReason, String noOfferReason, int score, int record,
                          int continuesUsed, CheckpointView highestCheckpoint, ReferenceCard reference, QuestionView question,
                          LastResult lastResult, OfferView offer, OperationRef operation, Instant awaitingOfferUntil,
                          boolean onThisDevice, RunConfigView runConfig, Instant serverTime) {}

    public record ReplacedRun(String runId, int score, int checkpointReward) {}

    public record StartView(RunView run, ReplacedRun replacedRun, Quota quota) {}

    public record AnswerView(String questionId, String result, String choice, boolean equalPrices, long revealedPrice, String direction,
                             int score, CheckpointView checkpointReached, boolean continuePossible, String noOfferReason,
                             boolean runFinished, RunView run, Instant serverTime) {}

    public record ActivationView(OfferView offer, Long balance, String noOfferReason, RunView run, Instant serverTime) {}

    /**
     * status: pending · succeeded · failed · refunded · expired_unconfirmed (handoff).
     * chargeStatus: unknown · confirmed · failed — what is known about the debit. refundStatus: none · pending · succeeded.
     */
    public record OperationView(String operationId, String status, String chargeStatus, String refundStatus, int chargedAmount,
                                int refundedAmount, Long balance, RunView run, Instant serverTime) {}

    public record Quota(int playsLeft, int playsPerDay, Instant resetsAt) {}

    public record Streak(int days, String rewardToday, boolean lostNotice) {}

    public record ActiveRun(String runId, String state, boolean onThisDevice, int score, CheckpointView checkpoint, Instant offerDeadline,
                            String operationId) {}

    public record CampaignBanner(String campaignId, String name, int checkpointRequiredCorrect, String checkpointTitle, long codeAmountToman) {}

    public record Access(String status, String message) {}

    public record BootstrapView(Access access, boolean firstVisit, Quota quota, int record, CheckpointView bestCheckpoint, Streak streak,
                                Long balance, ActiveRun activeRun, RunConfigView config, int streakReward, CampaignBanner campaign,
                                Instant serverTime) {}

    public record RewardStatus(int amount, int heldAmount, String status) {}

    public record StreakResult(int days, RewardStatus reward, boolean lostNotice) {}

    public record NextGoal(int remaining, String title, int reward, int requiredCorrect) {}

    public record CampaignCode(String status, String code, long amountToman, String terms, Instant expiresAt) {}

    public record ResultView(String runId, int score, String reason, String noOfferReason, int record, boolean isNewRecord,
                             int previousRecord, CheckpointView checkpoint, RewardStatus checkpointReward, StreakResult streak,
                             int continuesUsed, boolean continueLimitUsed, NextGoal nextGoal, Quota quota, Long balance,
                             CampaignCode campaignCode, Instant finishedAt, Instant serverTime) {}

    public record ProductItem(int position, String role, String productId, String variantId, String displayTitle, String title,
                              String imageUrl, Long gamePrice, String result, boolean isLoss, String link) {}

    public record ProductsView(String runId, List<ProductItem> items, String lossProductId, Instant serverTime) {}

    public record ShareView(int score, String checkpointTitle, String badgeAssetId, int streakDays, String gameName, String deepLink) {}
}
