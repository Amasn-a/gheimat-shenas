package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CoinOperation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Coin operation ledger (also the outbox), daily cap high-water, per-account daily credit, payout monitoring, alerts. */
public interface LedgerRepository {
    record DailyCredit(int credited, int held) {}

    void insertOperation(CoinOperation op);
    Optional<CoinOperation> findOperation(String operationId);
    Optional<CoinOperation> lockOperation(String operationId);
    void updateOperation(CoinOperation op);
    record DueOperation(String operationId, String userId) {}
    List<DueOperation> dueOperations(Instant now, int limit);
    List<CoinOperation> operationsOfRun(String runId);
    Optional<CoinOperation> refundOf(String chargeOperationId);
    Optional<CoinOperation> streakRewardOf(String userId, LocalDate capDay);
    /** Daily (streak) rewards earned and not yet claimed, oldest day first, row-locked for the claim. */
    List<CoinOperation> lockClaimableStreakRewards(String userId);
    /** The streak rewards of the player's most recent claim (one claim may cover several unclaimed days). */
    List<CoinOperation> latestStreakClaim(String userId);

    /** Highest cap effective on the date; inserts {@code currentCap} if the date has no row yet. */
    int capHighWater(LocalDate date, int currentCap, Instant now);
    void raiseCapHighWater(LocalDate date, int cap, Instant now);
    DailyCredit lockDailyCredit(String userId, LocalDate capDay);
    void saveDailyCredit(String userId, LocalDate capDay, DailyCredit credit);

    /** Adds to the execution-day payout total and returns the new total. */
    long addPayout(LocalDate date, long amount);
    /** True only for the first caller on a date (alert once). */
    boolean claimPayoutAlert(LocalDate date);

    void alert(String kind, String dedupeKey, String detailsJson, Instant now);
}
