package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Campaign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository {
    record Entitlement(String entitlementId, String campaignId, String userId, String runId, String status, long amountToman,
                       String codeCiphertext, Instant expiresAt, String providerRef, int attempts, Instant nextAttemptAt,
                       String lastError, Instant createdAt, Instant issuedAt) {}

    Optional<Campaign> liveAt(Instant t);
    Optional<Campaign> find(String campaignId);
    List<Campaign> list();
    void insert(Campaign c, String actor, Instant now);
    void update(Campaign c, Instant now);
    /** One conditional update: codes_issued + 1 and budget_used + amount, only within code cap and budget (R-48). */
    boolean reserveCode(String campaignId, long amountToman);
    boolean overlaps(Instant startsAt, Instant endsAt, String exceptCampaignId);
    /** Serialises campaign schedule writes (create, edit, end) until the transaction ends: overlap checks and status
     *  transitions then read a schedule nobody else is changing. */
    void lockSchedule();
    /** Entitlements whose code issue exhausted its retries. */
    List<Entitlement> entitlementsNeedingReview(int limit);

    Optional<Entitlement> entitlement(String campaignId, String userId);
    Optional<Entitlement> entitlementOfRun(String runId);
    void insertEntitlement(Entitlement e);
    Optional<Entitlement> lockEntitlement(String entitlementId);
    void updateEntitlement(Entitlement e);
    List<String> dueEntitlementIds(Instant now, int limit);
}
