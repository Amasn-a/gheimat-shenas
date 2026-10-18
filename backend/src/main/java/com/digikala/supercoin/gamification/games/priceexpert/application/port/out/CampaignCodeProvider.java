package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.time.Instant;

/**
 * Source of account-bound discount codes for A07 campaigns. The real source is still open (contract §19);
 * only a clearly labelled development fake exists. Issue is idempotent by {@code entitlementId}.
 */
public interface CampaignCodeProvider {

    sealed interface Result permits Issued, Failed, Unknown {}
    record Issued(String code, Instant expiresAt, String providerRef) implements Result {}
    record Failed(String error) implements Result {}
    record Unknown(String error) implements Result {}

    Result issue(String entitlementId, String userId, String campaignId, long amountToman, int validityDays);

    boolean isFake();
}
