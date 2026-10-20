package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway.Outcome;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Durable coin effects. Every operation follows the same recoverable workflow:
 * <ol>
 *   <li>the intent row (PENDING) is written in the transaction that decided it (outbox);</li>
 *   <li>{@link #claim} increments attempts and leases the row in its own transaction, before any external call, so a
 *       crash after sending is detectable (attempts &gt; 0) and a never-sent charge can be abandoned safely;</li>
 *   <li>the adapter call runs outside any transaction with the operation id as idempotency key;</li>
 *   <li>the result is applied with checked transitions. UNKNOWN is retried through a status query, never assumed.</li>
 * </ol>
 * No distributed exactly-once claim is made: one logical debit/credit relies on the provider honouring the key.
 */
public final class PaymentService {
    private static final Logger LOG = Logger.getLogger(PaymentService.class.getName());
    private static final Duration LEASE = Duration.ofSeconds(20);
    /** PROPOSED: players whose due operations are processed at once per worker batch. */
    static final int WORKER_PARALLELISM = 4;   // each in-flight operation uses at most one pooled connection at a time

    private final PriceExpertContext ctx;
    private final SettlementService settlement;
    private final SelectionService selection;

    public PaymentService(PriceExpertContext ctx, SettlementService settlement, SelectionService selection) {
        this.ctx = ctx;
        this.settlement = settlement;
        this.selection = selection;
    }

    /** Runs one due operation end to end. Safe to call concurrently: the claim serialises attempts. */
    public void process(String operationId) {
        Optional<CoinOperation> claimed = claim(operationId);
        if (claimed.isEmpty()) return;
        CoinOperation op = claimed.get();
        CoinGateway.Result r;
        try {
            r = execute(op);
        } catch (RuntimeException e) {
            r = new CoinGateway.Result(Outcome.UNKNOWN, null, "adapter error: " + e.getClass().getSimpleName());
        }
        if (op.type == OperationType.CHARGE) applyChargeOutcome(op.operationId, r);
        else applyCreditOutcome(op.operationId, r);
    }

    private CoinGateway.Result execute(CoinOperation op) {
        // A previous attempt may have reached the provider: ask before sending again (UNKNOWN or re-claimed PENDING).
        boolean mayHaveBeenSent = op.attempts > 1 || op.status == OperationStatus.UNKNOWN;
        if (mayHaveBeenSent) {
            CoinGateway.Result q = ctx.coin().query(op.operationId);
            if (q.outcome() != Outcome.NOT_FOUND) return q;              // reached the provider: applied (refunded if late) or rejected
        }
        if (op.type == OperationType.CHARGE) {
            // Nothing is known at the provider. A debit is sent (first time or again) only while the admitted purchase can
            // still be granted (run waiting, before resolveBy); after that no fresh debit is ever sent. A charge never sent is closed.
            // One that may have been sent is reconciled by status query only: if it lands it is refunded once; if it never
            // shows up, the retry window (giveUpAt) moves it to NEEDS_REVIEW with an alert, never to a silent FAILED,
            // because a provider "not found" is not yet known to be final (DigiCoin contract, D06).
            Offer offer = ctx.tx().inTransaction(() -> ctx.runs().findOffer(op.offerId)).orElse(null);
            long resolveMs = offer == null ? 0 : ctx.tx().inTransaction(() -> ctx.runs().findRun(offer.runId))
                    .map(run -> ctx.config(run.configVersion).paymentResolutionMs()).orElse(0L);
            if (offer == null || offer.state != OfferState.PAYMENT_PENDING || !ctx.now().isBefore(offer.resolveBy(resolveMs))) {
                return mayHaveBeenSent ? new CoinGateway.Result(Outcome.UNKNOWN, null, "offer over: reconciling an earlier attempt by status query")
                        : new CoinGateway.Result(Outcome.FAILED, null, "not sent: offer ended before execution");
            }
        }
        return switch (op.type) {
            case CHARGE -> ctx.coin().charge(op.userId, op.amount, op.operationId);
            case CHECKPOINT_REWARD, STREAK_REWARD -> ctx.coin().credit(op.userId, op.amount, op.operationId);
            case REFUND -> ctx.coin().refund(op.userId, op.amount, op.operationId, op.relatedOperationId);
        };
    }

    Optional<CoinOperation> claim(String operationId) {
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Optional<CoinOperation> o = ctx.ledger().lockOperation(operationId);
            if (o.isEmpty()) return Optional.empty();
            CoinOperation op = o.get();
            if (op.status != OperationStatus.PENDING && op.status != OperationStatus.UNKNOWN) return Optional.empty();
            if (op.nextAttemptAt != null && op.nextAttemptAt.isAfter(now)) return Optional.empty();
            if (op.giveUpAt != null && now.isAfter(op.giveUpAt)) {
                op.status = OperationStatus.NEEDS_REVIEW;
                op.lastError = "retry window exhausted";
                ctx.ledger().updateOperation(op);
                ctx.ledger().alert("COIN_OPERATION_NEEDS_REVIEW", "review:" + op.operationId,
                        "{\"operation_id\":\"" + op.operationId + "\",\"type\":\"" + op.type + "\"}", now);
                return Optional.empty();
            }
            op.attempts++;
            op.executedAt = now;
            op.nextAttemptAt = now.plus(LEASE);
            ctx.ledger().updateOperation(op);
            return Optional.of(op);
        });
    }

    /**
     * Applies a charge outcome (brief 2026-10-20, R-46; handoff "Continue grant"). The offer leaves PAYMENT_PENDING by one
     * conditional transition: an ADMITTED purchase is granted when its confirmation is recorded before the offer's
     * resolveBy instant and the run can still continue, even after the visible and the arrival deadlines; any confirmed
     * debit that does not buy a continue gets exactly one refund. A definitive rejection reopens the same offer only
     * while a new purchase could still be admitted (accept deadline); after that the run ends without a continue.
     */
    public void applyChargeOutcome(String operationId, CoinGateway.Result r) {
        ctx.tx().run(() -> {
            Instant now = ctx.now();
            CoinOperation peek = ctx.ledger().findOperation(operationId).orElseThrow();
            Player player = ctx.players().lockOrCreatePlayer(peek.userId, now);
            Run run = ctx.runs().lockRun(peek.runId).orElseThrow();
            CoinOperation op = ctx.ledger().lockOperation(operationId).orElseThrow();
            if (op.status == OperationStatus.SUCCEEDED || op.status == OperationStatus.FAILED || op.status == OperationStatus.NEEDS_REVIEW) return;
            Offer offer = ctx.runs().lockOffer(op.offerId).orElseThrow();
            if (r.providerRef() != null) op.providerRef = r.providerRef();
            // The resolution bound is decided first: at or after it the run no longer waits, whatever the outcome says.
            Instant resolveBy = offer.resolveBy(ctx.config(run.configVersion).paymentResolutionMs());
            if (run.state == RunState.PAYMENT_PENDING && !now.isBefore(resolveBy)) {
                settlement.finish(run, player, FinishReason.continue_failed, resolveBy);
                offer = ctx.runs().lockOffer(op.offerId).orElseThrow();
            }

            switch (r.outcome()) {
                case SUCCEEDED -> {
                    op.status = OperationStatus.SUCCEEDED;
                    op.confirmedAt = now;
                    op.nextAttemptAt = null;
                    ctx.ledger().updateOperation(op);
                    boolean granted = false;
                    if (offer.state == OfferState.PAYMENT_PENDING && run.state == RunState.PAYMENT_PENDING) {
                        if (canContinue(run, player)) {
                            offer.state = OfferState.GRANTED;
                            offer.closedAt = now;
                            ctx.runs().updateOffer(offer);
                            run.continuesUsed++;
                            run.state = RunState.READY;
                            run.readySince = now;
                            run.nextDeadlineAt = now.plusMillis(ctx.config(run.configVersion).inactivityMs());
                            run.missQuestionId = null;            // the miss no longer ends the run
                            run.missAt = null;
                            ctx.runs().updateRun(run);
                            granted = true;
                            emit("continue_succeeded", op, run);
                        } else {
                            FinishReason why = player.blocked ? FinishReason.blocked
                                    : !ctx.settings().gameEnabled() ? FinishReason.game_off : FinishReason.continue_failed;
                            if (why == FinishReason.continue_failed) run.noOfferReason = NoOfferReason.no_products.name();
                            settlement.finish(run, player, why, now);
                        }
                    }
                    if (!granted) createRefund(op, now);
                }
                case FAILED -> {
                    op.status = OperationStatus.FAILED;
                    op.lastError = r.error();
                    op.nextAttemptAt = null;
                    ctx.ledger().updateOperation(op);
                    if (offer.state == OfferState.PAYMENT_PENDING && run.state == RunState.PAYMENT_PENDING) {
                        if (offer.admits(now)) {
                            offer.state = OfferState.OPEN;        // known rejection: the same offer stays actionable while a purchase can be admitted
                            offer.operationId = null;
                            ctx.runs().updateOffer(offer);
                            run.state = RunState.OFFER_OPEN;
                            run.nextDeadlineAt = offer.acceptDeadline.plusMillis(ctx.config(run.configVersion).timeoutGraceMs());
                            ctx.runs().updateRun(run);
                        } else {                                  // definitive failure after the admission window: no life, the run ends
                            settlement.finish(run, player, FinishReason.continue_failed, now);
                        }
                    }
                    emit("continue_failed", op, run);
                }
                default -> {                                       // UNKNOWN / PENDING: reconcile through the status query
                    op.status = OperationStatus.UNKNOWN;
                    op.lastError = r.error();
                    op.nextAttemptAt = now.plus(backoff(op.attempts));
                    ctx.ledger().updateOperation(op);
                }
            }
        });
    }

    /** Rewards and refunds. A credit the provider rejects is never dropped silently: it goes to manual review. */
    public void applyCreditOutcome(String operationId, CoinGateway.Result r) {
        ctx.tx().run(() -> {
            Instant now = ctx.now();
            CoinOperation op = ctx.ledger().lockOperation(operationId).orElseThrow();
            if (op.status != OperationStatus.PENDING && op.status != OperationStatus.UNKNOWN) return;
            if (r.providerRef() != null) op.providerRef = r.providerRef();
            switch (r.outcome()) {
                case SUCCEEDED -> {
                    op.status = OperationStatus.SUCCEEDED;
                    op.confirmedAt = now;
                    op.nextAttemptAt = null;
                    ctx.ledger().updateOperation(op);
                    if (op.type.isCredit()) monitorPayout(op, now);
                }
                case FAILED -> {
                    op.status = OperationStatus.NEEDS_REVIEW;
                    op.lastError = r.error();
                    op.nextAttemptAt = null;
                    ctx.ledger().updateOperation(op);
                    ctx.ledger().alert("COIN_OPERATION_REJECTED", "rejected:" + op.operationId,
                            "{\"operation_id\":\"" + op.operationId + "\",\"type\":\"" + op.type + "\"}", now);
                }
                default -> {
                    op.status = OperationStatus.UNKNOWN;
                    op.lastError = r.error();
                    op.nextAttemptAt = now.plus(backoff(op.attempts));
                    ctx.ledger().updateOperation(op);
                }
            }
        });
    }

    /** One refund per confirmed charge (unique index on related_operation_id). Outside the payout cap (R-47). */
    void createRefund(CoinOperation charge, Instant now) {
        if (ctx.ledger().refundOf(charge.operationId).isPresent()) return;
        CoinOperation r = new CoinOperation();
        r.operationId = Ids.newId();
        r.userId = charge.userId;
        r.runId = charge.runId;
        r.offerId = charge.offerId;
        r.type = OperationType.REFUND;
        r.amount = charge.amount;
        r.status = OperationStatus.PENDING;
        r.relatedOperationId = charge.operationId;
        r.createdAt = now;
        r.nextAttemptAt = now;
        r.giveUpAt = now.plusSeconds(72 * 3600);
        ctx.ledger().insertOperation(r);
    }

    private boolean canContinue(Run run, Player player) {
        if (player.blocked || !ctx.settings().gameEnabled()) return false;
        GameConfig cfg = ctx.config(run.configVersion);
        CatalogProduct ref = ctx.catalog().product(run.catalogVersion, run.referenceDkp).orElseThrow();
        return selection.canReveal(run.catalogVersion, cfg, ref.priceToman(), run.score, ctx.runs().runProductDkps(run.runId));
    }

    /** Monitoring only (R-47): no player-facing effect, no eligibility change. Counted by actual payment day (F-03). */
    private void monitorPayout(CoinOperation op, Instant now) {
        long total = ctx.ledger().addPayout(BusinessCalendar.dateOf(now), op.amount);
        Integer active = ctx.settings().activeConfigVersion();
        Long threshold = active == null ? null : ctx.config(active).payoutAlertThreshold();
        if (threshold != null && total > threshold && ctx.ledger().claimPayoutAlert(BusinessCalendar.dateOf(now))) {
            ctx.ledger().alert("DAILY_PAYOUT_THRESHOLD", "payout:" + BusinessCalendar.dateOf(now),
                    "{\"date\":\"" + BusinessCalendar.dateOf(now) + "\",\"total\":" + total + ",\"threshold\":" + threshold + "}", now);
        }
    }

    static Duration backoff(int attempts) {
        long seconds = Math.min(60, 1L << Math.min(6, Math.max(0, attempts - 1)));
        return Duration.ofSeconds(seconds);
    }

    private void emit(String name, CoinOperation op, Run run) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("operation_id", op.operationId);
        e.put("run_id", run.runId);
        e.put("offer_id", op.offerId);
        e.put("continues", run.continuesUsed);
        e.put("cost", op.amount);
        ctx.tx().afterCommit(() -> ctx.events().emit(name, name + ":" + op.operationId, e));
    }

    /**
     * Worker entry point. Different players' operations run in parallel (bounded), one player's in order, so a slow
     * provider call delays only its own player's queue instead of the whole batch (claims keep each operation single-owner).
     */
    public int processDue(int limit) {
        List<com.digikala.supercoin.gamification.games.priceexpert.application.port.out.LedgerRepository.DueOperation> due =
                ctx.tx().inTransaction(() -> ctx.ledger().dueOperations(ctx.now(), limit));
        Map<String, List<String>> byUser = new LinkedHashMap<>();
        due.forEach(d -> byUser.computeIfAbsent(d.userId(), k -> new java.util.ArrayList<>()).add(d.operationId()));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(WORKER_PARALLELISM, Thread.ofVirtual().factory())) {
            for (List<String> ids : byUser.values()) pool.submit(() -> ids.forEach(id -> {
                try { process(id); } catch (RuntimeException e) { LOG.warning("operation " + id + " failed: " + e); }
            }));
        }                                                                      // close() waits for the batch
        return due.size();
    }
}
