package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.worker;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.TransactionRunner;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.CampaignService;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.DeadlineService;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.PaymentService;

import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background loops. All state they act on is persisted, so they can stop and restart at any time; several instances may
 * run in parallel (row claims and locks keep work single-owner). In supercoin these become @Scheduled jobs.
 */
public final class Workers {
    private static final Logger LOG = Logger.getLogger(Workers.class.getName());
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(3, Thread.ofVirtual().factory());
    private final DeadlineService deadlines;
    private final PaymentService payments;
    private final CampaignService campaigns;
    private final IdempotencyRepository idempotency;
    private final TransactionRunner tx;
    private final InstantSource clock;

    public Workers(DeadlineService deadlines, PaymentService payments, CampaignService campaigns, IdempotencyRepository idempotency,
                   TransactionRunner tx, InstantSource clock) {
        this.deadlines = deadlines;
        this.payments = payments;
        this.campaigns = campaigns;
        this.idempotency = idempotency;
        this.tx = tx;
        this.clock = clock;
    }

    public void start() {
        every(250, "sweeper", () -> deadlines.sweep(200));
        every(500, "coin-operations", () -> payments.processDue(50));
        every(1000, "campaign-codes", () -> campaigns.processDue(20));
        every(3_600_000, "idempotency-purge", () -> tx.run(() -> idempotency.purgeOlderThan(clock.instant().minus(Duration.ofHours(24)))));
    }

    public void stop() { pool.shutdownNow(); }

    private void every(long ms, String name, Runnable r) {
        pool.scheduleWithFixedDelay(() -> {
            try { r.run(); } catch (Throwable t) { LOG.warning(name + " loop failed: " + t); }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }
}
