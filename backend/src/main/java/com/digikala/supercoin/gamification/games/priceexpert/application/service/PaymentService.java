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

        if (op.type == OperationType.CHARGE && op.attempts == 1 && op.status == OperationStatus.PENDING && !chargeStillWanted(op)) {
            // Never sent (this is the first attempt) and the offer is over: abandon without touching the wallet.
            applyChargeOutcome(op.operationId, new CoinGateway.Result(Outcome.FAILED, null, "not sent: offer ended before execution"));
            return;
        }
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
        if (op.attempts > 1 || op.status == OperationStatus.UNKNOWN) {
            CoinGateway.Result q = ctx.coin().query(op.operationId);
            if (q.outcome() != Outcome.NOT_FOUND) return q;
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

    private boolean chargeStillWanted(CoinOperation op) {
        return ctx.tx().inTransaction(() -> ctx.runs().findOffer(op.offerId)
                .map(o -> o.state == OfferState.PAYMENT_PENDING && o.openAt(ctx.now())).orElse(false));
    }

    /**
     * Applies a charge outcome (R-39, R-43, R-46; handoff "Continue grant"). The offer leaves PAYMENT_PENDING by one
     * conditional transition: GRANTED only if the confirmation is recorded strictly before the offer deadline and the
     * run can still continue; any confirmed debit that does not buy a continue gets exactly one refund.
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
            // The offer deadline is decided first: at or after it the offer is over (R-43), whatever the outcome says.
            if (run.state == RunState.PAYMENT_PENDING && !offer.openAt(now)) {
                settlement.finish(run, player, FinishReason.continue_failed, offer.deadline);
                offer = ctx.runs().lockOffer(op.offerId).orElseThrow();
            }

            switch (r.outcome()) {
                case SUCCEEDED -> {
                    op.status = OperationStatus.SUCCEEDED;
                    op.confirmedAt = now;
                    op.nextAttemptAt = null;
                    ctx.ledger().updateOperation(op);
                    boolean granted = false;
                    if (offer.state == OfferState.PAYMENT_PENDING && offer.openAt(now) && run.state == RunState.PAYMENT_PENDING) {
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
                    if (offer.state == OfferState.PAYMENT_PENDING && offer.openAt(now) && run.state == RunState.PAYMENT_PENDING) {
                        offer.state = OfferState.OPEN;            // known rejection: the same offer stays actionable until its deadline
                        offer.operationId = null;
                        ctx.runs().updateOffer(offer);
                        run.state = RunState.OFFER_OPEN;
                        run.nextDeadlineAt = offer.deadline;
                        ctx.runs().updateRun(run);
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
        return selection.hasNextQuestion(run.catalogVersion, cfg, ref.priceToman(), run.score, ctx.runs().runProductDkps(run.runId));
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

    /** Worker entry point. */
    public int processDue(int limit) {
        List<String> ids = ctx.tx().inTransaction(() -> ctx.ledger().dueOperationIds(ctx.now(), limit));
        for (String id : ids) {
            try { process(id); } catch (RuntimeException e) { LOG.warning("operation " + id + " failed: " + e); }
        }
        return ids.size();
    }
}
