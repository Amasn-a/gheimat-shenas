package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.*;

import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Applies due transitions from persisted absolute deadlines (handoff "Clocks and scheduling"). Used by the sweeper and
 * at the start of every read and command, so a missed sweep, a restart or a closed browser never changes an outcome.
 * Finalisation timestamps equal the deadline, not the time this code runs.
 */
public final class DeadlineService {
    private final PriceExpertContext ctx;
    private final SettlementService settlement;

    public DeadlineService(PriceExpertContext ctx, SettlementService settlement) {
        this.ctx = ctx;
        this.settlement = settlement;
    }

    /** Caller holds the player and run locks. Returns true when the run changed. */
    public boolean applyDue(Run run, Player player, Instant now) {
        if (run.isFinished()) return false;
        GameConfig cfg = ctx.config(run.configVersion);
        if (player.blocked) {                                     // safety net; blocking finishes runs immediately
            settlement.finish(run, player, FinishReason.blocked, now);
            return true;
        }
        boolean gameOn = ctx.settings().gameEnabled();
        switch (run.state) {
            case READY -> {
                if (!gameOn) { settlement.finish(run, player, FinishReason.game_off, now); return true; }   // next question break
                Instant limit = run.readySince.plusMillis(cfg.inactivityMs());
                if (!now.isBefore(limit)) { settlement.finish(run, player, FinishReason.inactive, limit); return true; }
            }
            case QUESTION_OPEN -> {                                // a released question keeps its timing even when the game is off
                Question q = ctx.runs().openQuestion(run.runId).orElseThrow();
                if (!now.isBefore(q.acceptDeadline.plusMillis(cfg.timeoutGraceMs()))) {
                    // Locked so this decision and a receipt being recorded are serialised. A receipt recorded on time is
                    // the answer, not a timeout, even when its request has not reached the run yet or died (R-45).
                    q = ctx.runs().lockQuestion(q.questionId).orElseThrow();
                    if (q.receivedAt != null && q.choice != null) settleAnswer(run, player, q, cfg, now);
                    else timeout(run, player, q, cfg);
                    return true;
                }
            }
            case AWAITING_OFFER -> {
                if (!gameOn) { finishAfterMiss(run, player, NoOfferReason.game_off, now); return true; }
                if (!now.isBefore(run.nextDeadlineAt)) { finishAfterMiss(run, player, null, run.nextDeadlineAt); return true; }
            }
            case OFFER_OPEN -> {
                Offer o = ctx.runs().latestOfferOfRun(run.runId).orElseThrow();
                if (!gameOn) { settlement.finish(run, player, FinishReason.game_off, now); return true; }   // no continue after game-off
                if (!now.isBefore(o.acceptDeadline.plusMillis(cfg.timeoutGraceMs()))) {
                    // Locked so this decision and a purchase receipt being recorded are serialised. A purchase that ARRIVED by
                    // the accept deadline is admitted even when its request has not reached the run yet or died.
                    o = ctx.runs().lockOffer(o.offerId).orElseThrow();
                    if (o.state == OfferState.OPEN && o.purchaseReceivedAt != null && o.admits(o.purchaseReceivedAt)
                            && run.continuesUsed < cfg.continueRule().cap()) admitPurchase(run, o, cfg, now);
                    else settlement.finish(run, player, FinishReason.expired, o.acceptDeadline);
                    return true;
                }
            }
            case PAYMENT_PENDING -> {
                Offer o = ctx.runs().latestOfferOfRun(run.runId).orElseThrow();
                // An admitted purchase is not failed by the offer deadlines: the run waits for the definitive outcome until
                // resolveBy (PROPOSED bound); still unconfirmed then, it ends; a later debit is refunded (R-43 path, R-51).
                Instant resolveBy = o.resolveBy(cfg.paymentResolutionMs());
                if (!now.isBefore(resolveBy)) { settlement.finish(run, player, FinishReason.continue_failed, resolveBy); return true; }
            }
            default -> { }
        }
        return false;
    }

    /**
     * Turns an admitted purchase into its charge operation (outbox row) and moves offer and run to PAYMENT_PENDING; the
     * caller holds the player, run and offer locks and has checked admission and the continue cap. Returns the operation id.
     */
    String admitPurchase(Run run, Offer offer, GameConfig cfg, Instant now) {
        CoinOperation op = new CoinOperation();
        op.operationId = Ids.newId();
        op.userId = run.userId;
        op.runId = run.runId;
        op.offerId = offer.offerId;
        op.type = OperationType.CHARGE;
        op.amount = offer.cost;                                          // cost snapshot of this offer, never from the client
        op.status = OperationStatus.PENDING;
        op.createdAt = now;
        op.nextAttemptAt = now;                                          // the worker picks it up if no request attempts it
        op.giveUpAt = now.plusSeconds(72 * 3600);
        ctx.ledger().insertOperation(op);
        offer.state = OfferState.PAYMENT_PENDING;
        offer.operationId = op.operationId;
        offer.purchaseReceivedAt = null;
        ctx.runs().updateOffer(offer);
        run.state = RunState.PAYMENT_PENDING;
        run.nextDeadlineAt = offer.resolveBy(cfg.paymentResolutionMs());
        ctx.runs().updateRun(run);
        Map<String, Object> e = Map.of("offer_id", offer.offerId, "operation_id", op.operationId);
        ctx.tx().afterCommit(() -> ctx.events().emit("continue_requested", "continue_requested:" + op.operationId, e));
        return op.operationId;
    }

    /** Sweeper: applies due transitions to runs whose next deadline has passed. Skips players and runs locked by a live request. */
    public int sweep(int limit) {
        java.util.List<String> ids = ctx.tx().inTransaction(() -> ctx.runs().dueRunIds(ctx.now(), limit));
        for (String id : ids) {
            try {
                ctx.tx().run(() -> {
                    Instant now = ctx.now();
                    Optional<Run> peek = ctx.runs().findRun(id);
                    if (peek.isEmpty() || peek.get().isFinished()) return;
                    // never wait behind a busy player: the request holding the lock applies due transitions itself, and
                    // the next sweep retries; one slow player cannot stall everyone else's deadlines (lock order kept)
                    Optional<Player> p = ctx.players().tryLockPlayer(peek.get().userId);
                    if (p.isEmpty()) return;
                    ctx.runs().tryLockRun(id).ifPresent(run -> applyDue(run, p.get(), now));
                });
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(DeadlineService.class.getName()).warning("sweep of run " + id + " failed: " + e);
            }
        }
        return ids.size();
    }

    /**
     * Settles an OPEN question from its recorded receipt ({@code q.choice}, {@code q.receivedAt}); the caller holds the
     * player, run and question locks. A miss is stamped at the receipt time, not at the time this runs.
     */
    void settleAnswer(Run run, Player player, Question q, GameConfig cfg, Instant now) {
        boolean correct = q.isCorrect(q.choice);
        q.settledAt = now;
        q.state = correct ? QuestionState.CORRECT : QuestionState.WRONG;
        ctx.runs().updateQuestion(q);
        GameConfig.Checkpoint reached = null;
        if (correct) {
            run.score++;
            for (GameConfig.Checkpoint cp : cfg.enabledCheckpoints()) if (cp.requiredCorrect() == run.score) reached = cp;
            if (reached != null) run.highestCheckpointId = reached.id();
            run.referenceDkp = q.comparisonDkp;                          // promotion: the answered product becomes the reference
            run.state = RunState.READY;
            run.readySince = now;
            run.nextDeadlineAt = now.plusMillis(cfg.inactivityMs());
            ctx.runs().updateRun(run);
            if (!ctx.settings().gameEnabled()) settlement.finish(run, player, FinishReason.game_off, now);
        } else {
            miss(run, player, q, cfg, q.receivedAt);
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("run_id", run.runId); e.put("question_id", q.questionId); e.put("result", correct ? "correct" : "wrong");
        e.put("received_ms_after_t0", q.receivedAt.toEpochMilli() - q.releasedAt.toEpochMilli());
        ctx.tx().afterCommit(() -> ctx.events().emit("answer_submitted", "answer:" + q.questionId, e));
        if (reached != null) {
            Map<String, Object> ce = Map.of("run_id", run.runId, "checkpoint_id", reached.id(), "config_version", run.configVersion);
            ctx.tx().afterCommit(() -> ctx.events().emit("checkpoint_reached", "checkpoint:" + run.runId + ":" + ce.get("checkpoint_id"), ce));
        }
    }

    /** Stamped at acceptDeadline (R-45) even when finalised later. */
    void timeout(Run run, Player player, Question q, GameConfig cfg) {
        q.state = QuestionState.TIMED_OUT;
        q.settledAt = q.acceptDeadline;
        ctx.runs().updateQuestion(q);
        miss(run, player, q, cfg, q.acceptDeadline);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("run_id", run.runId); e.put("question_id", q.questionId); e.put("result", "timeout");
        ctx.tx().afterCommit(() -> ctx.events().emit("answer_submitted", "answer:" + q.questionId, e));
    }

    /**
     * After a wrong answer or timeout. If a continue is still possible in principle, the run waits for the post-feedback
     * activation (F-02); the 10 s offer window does not start here. Balance is checked at activation (R-50, R-63).
     */
    String miss(Run run, Player player, Question q, GameConfig cfg, Instant at) {
        run.missQuestionId = q.questionId;
        run.missAt = at;
        GameConfig.ContinueRule rule = cfg.continueRule();
        NoOfferReason reason = null;
        if (!rule.enabled() || rule.cap() == 0) reason = NoOfferReason.disabled;
        else if (run.continuesUsed >= rule.cap()) reason = NoOfferReason.limit_used;
        else if (!ctx.settings().gameEnabled()) reason = NoOfferReason.game_off;
        else if (player.blocked) reason = NoOfferReason.blocked;
        if (reason != null) {
            run.noOfferReason = reason.name();
            settlement.finish(run, player, q.state == QuestionState.TIMED_OUT ? FinishReason.timeout : FinishReason.wrong, at);
            return reason.name();
        }
        run.state = RunState.AWAITING_OFFER;
        run.nextDeadlineAt = at.plusMillis(cfg.offerActivationMs());
        ctx.runs().updateRun(run);
        return null;
    }

    /** Ends a run that was waiting after a miss; the finish reason is the miss itself (wrong / timeout). */
    void finishAfterMiss(Run run, Player player, NoOfferReason reason, Instant at) {
        Optional<Question> miss = ctx.runs().findQuestion(run.missQuestionId);
        FinishReason fr = miss.map(q -> q.state == QuestionState.TIMED_OUT ? FinishReason.timeout : FinishReason.wrong).orElse(FinishReason.wrong);
        if (reason != null) run.noOfferReason = reason.name();
        settlement.finish(run, player, fr, at);
    }
}
