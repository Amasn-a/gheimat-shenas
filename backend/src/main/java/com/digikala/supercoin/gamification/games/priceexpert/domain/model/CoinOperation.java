package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationStatus;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationType;

import java.time.Instant;
import java.time.LocalDate;

/** Ledger and outbox row for one logical coin effect. operationId is the provider idempotency key. */
public class CoinOperation {
    public String operationId;
    public String userId;
    public String runId;
    public String offerId;
    public OperationType type;
    public int amount;
    public int heldAmount;
    public OperationStatus status;
    public LocalDate capDay;
    public String relatedOperationId;
    public String providerRef;
    public int attempts;
    public Instant nextAttemptAt;
    public Instant giveUpAt;
    public String lastError;
    public Instant createdAt;
    public Instant executedAt;
    public Instant confirmedAt;
    public String rewardKey;       // run rewards: the checkpoint id or 'record:<target>'; one per run and key (V5)
    public Instant claimedAt;      // daily reward: when the player's claim turned CLAIMABLE into PENDING
}
