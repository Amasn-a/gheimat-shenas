package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.campaign;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignCodeProvider;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DEVELOPMENT FAKE code source (contract §7, §19: the real source and its account-binding contract are open).
 * Codes start with "DEV-" so they can never be mistaken for production codes. Idempotent by entitlement id.
 * Production start-up refuses this class.
 */
public final class FakeCampaignCodeProvider implements CampaignCodeProvider {
    private static final char[] A = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom rng = new SecureRandom();
    private final InstantSource clock;
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();

    public FakeCampaignCodeProvider(InstantSource clock) { this.clock = clock; }

    @Override public Result issue(String entitlementId, String userId, String campaignId, long amountToman, int validityDays) {
        return issued.computeIfAbsent(entitlementId, k -> {
            StringBuilder sb = new StringBuilder("DEV-");
            for (int i = 0; i < 8; i++) sb.append(A[rng.nextInt(A.length)]);
            return new Issued(sb.toString(), clock.instant().plus(Duration.ofDays(validityDays)), "fake-" + k);
        });
    }

    @Override public boolean isFake() { return true; }
}
