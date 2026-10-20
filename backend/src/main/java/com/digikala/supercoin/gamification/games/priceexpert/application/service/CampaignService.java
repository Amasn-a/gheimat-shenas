package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignCodeProvider;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignRepository.Entitlement;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Campaign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Issues reserved campaign codes through the provider (outbox-style: the entitlement row was written with the run's
 * finish). The code is bound to the account and stored encrypted; it is never logged.
 */
public final class CampaignService {
    private static final Logger LOG = Logger.getLogger(CampaignService.class.getName());
    private static final int MAX_ATTEMPTS = 20;
    private final PriceExpertContext ctx;

    public CampaignService(PriceExpertContext ctx) { this.ctx = ctx; }

    public int processDue(int limit) {
        List<String> ids = ctx.tx().inTransaction(() -> ctx.campaigns().dueEntitlementIds(ctx.now(), limit));
        for (String id : ids) {
            try { process(id); } catch (RuntimeException e) { LOG.warning("entitlement " + id + " failed: " + e.getClass().getSimpleName()); }
        }
        return ids.size();
    }

    void process(String id) {
        Optional<Entitlement> claimed = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Optional<Entitlement> e = ctx.campaigns().lockEntitlement(id);
            if (e.isEmpty() || !"PENDING".equals(e.get().status()) || e.get().nextAttemptAt().isAfter(now)) return Optional.<Entitlement>empty();
            Entitlement x = e.get();
            if (x.attempts() >= MAX_ATTEMPTS) {                                // ceiling holds whatever the provider did (throws, hangs)
                ctx.campaigns().updateEntitlement(new Entitlement(x.entitlementId(), x.campaignId(), x.userId(), x.runId(), "NEEDS_REVIEW",
                        x.amountToman(), null, null, x.providerRef(), x.attempts(), null, x.lastError() == null ? "retry limit reached" : x.lastError(),
                        x.createdAt(), null));
                ctx.ledger().alert("CAMPAIGN_CODE_NEEDS_REVIEW", "code:" + x.entitlementId(), "{\"entitlement_id\":\"" + x.entitlementId() + "\"}", now);
                return Optional.<Entitlement>empty();
            }
            Entitlement leased = new Entitlement(x.entitlementId(), x.campaignId(), x.userId(), x.runId(), x.status(), x.amountToman(),
                    x.codeCiphertext(), x.expiresAt(), x.providerRef(), x.attempts() + 1, now.plusSeconds(20), x.lastError(), x.createdAt(), x.issuedAt());
            ctx.campaigns().updateEntitlement(leased);
            return Optional.of(leased);
        });
        if (claimed.isEmpty()) return;
        Entitlement e = claimed.get();
        Campaign c = ctx.tx().inTransaction(() -> ctx.campaigns().find(e.campaignId()).orElseThrow());
        CampaignCodeProvider.Result r;
        try {
            r = ctx.codes().issue(e.entitlementId(), e.userId(), e.campaignId(), e.amountToman(), c.validityDays());
        } catch (RuntimeException ex) {                                        // an exception is an unknown outcome, recorded like one
            r = new CampaignCodeProvider.Unknown("provider error: " + ex.getClass().getSimpleName());
        }
        CampaignCodeProvider.Result outcome = r;
        ctx.tx().run(() -> {
            Instant now = ctx.now();
            Entitlement cur = ctx.campaigns().lockEntitlement(id).orElseThrow();
            if (!"PENDING".equals(cur.status())) return;
            Entitlement next;
            if (outcome instanceof CampaignCodeProvider.Issued i) {
                next = new Entitlement(cur.entitlementId(), cur.campaignId(), cur.userId(), cur.runId(), "ISSUED", cur.amountToman(),
                        ctx.cipher().encrypt(i.code()), i.expiresAt(), i.providerRef(), cur.attempts(), null, null, cur.createdAt(), now);
            } else {
                String err = outcome instanceof CampaignCodeProvider.Failed f ? f.error() : ((CampaignCodeProvider.Unknown) outcome).error();
                boolean giveUp = cur.attempts() >= MAX_ATTEMPTS;
                next = new Entitlement(cur.entitlementId(), cur.campaignId(), cur.userId(), cur.runId(), giveUp ? "NEEDS_REVIEW" : "PENDING",
                        cur.amountToman(), null, null, cur.providerRef(), cur.attempts(), giveUp ? null : now.plus(PaymentService.backoff(cur.attempts())),
                        err, cur.createdAt(), null);
                if (giveUp) ctx.ledger().alert("CAMPAIGN_CODE_NEEDS_REVIEW", "code:" + cur.entitlementId(),
                        "{\"entitlement_id\":\"" + cur.entitlementId() + "\"}", now);
            }
            ctx.campaigns().updateEntitlement(next);
        });
    }
}
