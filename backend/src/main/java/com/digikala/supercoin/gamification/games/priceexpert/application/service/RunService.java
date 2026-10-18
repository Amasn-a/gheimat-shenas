package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.PlayerRepository.Participation;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Player commands. Every command: lock the player row, lock the run row, apply due transitions, verify ownership and
 * device, then make one checked transition. Errors that follow a committed state change (e.g. the game was switched
 * off at a question break) are raised after the commit so the change is not rolled back.
 */
public final class RunService {

    /** Authenticated caller: user id from the verified token; device id is a routing hint, not proof (R-28). */
    public record Caller(String userId, String deviceId) {}

    private final PriceExpertContext ctx;
    private final SelectionService selection;
    private final SettlementService settlement;
    private final DeadlineService deadlines;
    private final PaymentService payments;
    private final ViewAssembler views;

    public RunService(PriceExpertContext ctx, SelectionService selection, SettlementService settlement, DeadlineService deadlines,
                      PaymentService payments, ViewAssembler views) {
        this.ctx = ctx;
        this.selection = selection;
        this.settlement = settlement;
        this.deadlines = deadlines;
        this.payments = payments;
        this.views = views;
    }

    // ------------------------------------------------------------------------------------------------ start (POST /runs)

    public StartView start(Caller caller) {
        Result<StartView> r = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Player player = ctx.players().lockOrCreatePlayer(caller.userId(), now);
            if (player.blocked) return Result.error(GameException.conflict("PLAYER_BLOCKED", "player blocked"));
            ConfigRepositorySettings s = ConfigRepositorySettings.of(ctx);
            if (!s.gameEnabled) return Result.error(new GameException("GAME_UNAVAILABLE", 409, "game is off", Map.of("message", s.message == null ? "" : s.message)));

            Optional<Run> activeOpt = ctx.runs().lockActiveRunOfUser(caller.userId());
            Run active = null;
            if (activeOpt.isPresent()) {
                active = activeOpt.get();
                deadlines.applyDue(active, player, now);
                if (active.isFinished()) active = null;
            }
            if (active != null) {
                if (caller.deviceId().equals(active.deviceId))
                    return Result.error(GameException.conflict("ACTIVE_RUN_EXISTS", "an active run exists on this device", Map.of("run_id", active.runId)));
                if (active.state == RunState.PAYMENT_PENDING) {
                    Offer o = ctx.runs().latestOfferOfRun(active.runId).orElseThrow();
                    return Result.error(GameException.conflict("OPERATION_PENDING", "a continue payment is being checked",
                            Map.of("run_id", active.runId, "operation_id", o.operationId == null ? "" : o.operationId, "offer_deadline", o.deadline.toString())));
                }
            }

            int cfgVersion = s.activeConfigVersion;
            GameConfig cfg = ctx.config(cfgVersion);
            LocalDate day = BusinessCalendar.dateOf(now);
            Participation part = ctx.players().lockParticipation(caller.userId(), day);
            if (part.playsUsed() >= cfg.playsPerDay()) {                  // no quota: an old run on another device stays unchanged (R-28)
                return Result.error(GameException.conflict("DAILY_QUOTA_EXHAUSTED", "no plays left today",
                        Map.of("resets_at", BusinessCalendar.nextMidnight(now).toString())));
            }
            int catalogVersion = s.activeCatalogVersion;
            CatalogProduct ref = selection.chooseInitialReference(catalogVersion, cfg, caller.userId(), day).orElse(null);
            if (ref == null) return Result.error(new GameException("GAME_UNAVAILABLE", 409, "no playable products", null));

            String newRunId = Ids.newId();
            ReplacedRun replaced = null;
            if (active != null) {                                            // accepted start on another device replaces the old run
                active.replacedByRunId = newRunId;
                settlement.finish(active, player, FinishReason.replaced, now);
                GameConfig oldCfg = ctx.config(active.configVersion);
                String cpId = active.highestCheckpointId;
                int reward = oldCfg.checkpoints().stream().filter(c -> c.id().equals(cpId)).mapToInt(GameConfig.Checkpoint::reward).findFirst().orElse(0);
                replaced = new ReplacedRun(active.runId, active.score, reward);
            }

            ctx.ledger().capHighWater(day, cfg.dailyAccountCap(), now);     // pin the start day's cap high-water (R-47, F-03)
            Run run = new Run();
            run.runId = newRunId;
            run.userId = caller.userId();
            run.deviceId = caller.deviceId();
            run.state = RunState.READY;
            run.startBusinessDate = day;
            run.startedAt = now;
            run.configVersion = cfgVersion;
            run.catalogVersion = catalogVersion;
            run.campaignId = ctx.campaigns().liveAt(now).map(Campaign::campaignId).orElse(null);
            run.referenceDkp = ref.dkp();
            run.nextSeq = 1;
            run.readySince = now;
            run.nextDeadlineAt = now.plusMillis(cfg.inactivityMs());
            ctx.runs().insertRun(run);
            ctx.players().saveParticipation(new Participation(part.userId(), day, part.playsUsed() + 1, part.finishedRuns(), part.streakEligible()));
            ctx.players().markSeen(caller.userId(), day, ref.dkp(), now);

            int playsLeft = cfg.playsPerDay() - part.playsUsed() - 1;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("run_id", run.runId); e.put("start_date", day.toString()); e.put("play_number", part.playsUsed() + 1);
            ctx.tx().afterCommit(() -> ctx.events().emit("session_start", "session_start:" + run.runId, e));
            return Result.ok(new StartView(views.run(run, player.record, caller.deviceId()), replaced,
                    new Quota(playsLeft, cfg.playsPerDay(), BusinessCalendar.nextMidnight(now))));
        });
        return r.get();
    }

    // ------------------------------------------------------------------------------------------------ reveal

    public RunView reveal(Caller caller, String runId) {
        Result<RunView> r = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Locked l = lockForCommand(caller, runId, now);
            Run run = l.run;
            if (run.isFinished()) return Result.error(finishedError(run, l.player, caller));
            requireDevice(run, caller, l.player);
            if (run.state == RunState.QUESTION_OPEN) return Result.ok(views.run(run, l.player.record, caller.deviceId()));   // same question, any key
            if (run.state != RunState.READY) return Result.error(stateError("INVALID_STATE", run, l.player, caller));

            GameConfig cfg = ctx.config(run.configVersion);
            CatalogProduct ref = ctx.catalog().product(run.catalogVersion, run.referenceDkp).orElseThrow();
            LocalDate showDay = BusinessCalendar.dateOf(now);
            Optional<SelectionService.Selected> sel = selection.chooseComparison(run.catalogVersion, cfg, ref.priceToman(), run.score,
                    ctx.runs().runProductDkps(run.runId), run.userId, showDay);
            if (sel.isEmpty()) {                                             // G03.NO_PRODUCTS_LEFT: settle normally, no fabricated miss
                settlement.finish(run, l.player, FinishReason.no_products, now);
                return Result.ok(views.run(run, l.player.record, caller.deviceId()));
            }
            Question q = new Question();
            q.questionId = Ids.newId();
            q.runId = run.runId;
            q.seq = run.nextSeq;
            q.scoreBefore = run.score;
            q.bandIndex = sel.get().bandIndex();
            q.referenceDkp = ref.dkp();
            q.referencePrice = ref.priceToman();
            q.comparisonDkp = sel.get().product().dkp();
            q.comparisonPrice = sel.get().product().priceToman();
            Instant t0 = ctx.now();                                          // the release event: before any identifying data leaves
            q.releasedAt = t0;
            q.inputDeadline = t0.plusMillis(cfg.inputMs());
            q.acceptDeadline = t0.plusMillis(cfg.acceptMs());
            q.state = QuestionState.OPEN;
            ctx.runs().insertQuestion(q);
            run.state = RunState.QUESTION_OPEN;
            run.nextSeq++;
            run.nextDeadlineAt = q.acceptDeadline.plusMillis(cfg.timeoutGraceMs());
            ctx.runs().updateRun(run);
            ctx.players().markSeen(run.userId, BusinessCalendar.dateOf(t0), q.comparisonDkp, t0);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("run_id", run.runId); e.put("question_id", q.questionId); e.put("band", q.bandIndex); e.put("released_at", t0.toString());
            ctx.tx().afterCommit(() -> ctx.events().emit("question_released", "question_released:" + q.questionId, e));
            return Result.ok(views.run(run, l.player.record, caller.deviceId()));
        });
        return r.get();
    }

    // ------------------------------------------------------------------------------------------------ answer

    public AnswerView answer(Caller caller, String questionId, Choice choice, Instant receivedAt) {
        if (choice == null) throw GameException.validation("choice is required", Map.of("choice", "higher or lower"));
        Result<AnswerView> r = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Question peek = ctx.runs().findQuestion(questionId).orElseThrow(GameException::notFound);
            Locked l = lockForCommand(caller, peek.runId, now);
            Run run = l.run;
            Question q = ctx.runs().lockQuestion(questionId).orElseThrow();
            if (q.state == QuestionState.CORRECT || q.state == QuestionState.WRONG)   // first committed answer wins; replays get it
                return Result.ok(answerView(q, run, l.player, caller, null, null));
            if (q.state == QuestionState.TIMED_OUT)
                return Result.error(stateError("QUESTION_EXPIRED", run, l.player, caller));
            if (run.isFinished() || q.state == QuestionState.UNANSWERED) return Result.error(finishedError(run, l.player, caller));
            requireDevice(run, caller, l.player);
            if (receivedAt.isAfter(q.acceptDeadline))                        // inclusive cutoff: receivedAt <= acceptDeadline accepted (R-45)
                return Result.error(stateError("QUESTION_EXPIRED", run, l.player, caller));

            GameConfig cfg = ctx.config(run.configVersion);
            boolean correct = q.isCorrect(choice);
            q.choice = choice;
            q.receivedAt = receivedAt;
            q.settledAt = now;
            q.state = correct ? QuestionState.CORRECT : QuestionState.WRONG;
            ctx.runs().updateQuestion(q);
            GameConfig.Checkpoint reached = null;
            String noOffer = null;
            if (correct) {
                run.score++;
                for (GameConfig.Checkpoint cp : cfg.enabledCheckpoints()) if (cp.requiredCorrect() == run.score) reached = cp;
                if (reached != null) run.highestCheckpointId = reached.id();
                run.referenceDkp = q.comparisonDkp;                          // promotion: the answered product becomes the reference
                run.state = RunState.READY;
                run.readySince = now;
                run.nextDeadlineAt = now.plusMillis(cfg.inactivityMs());
                ctx.runs().updateRun(run);
                if (!ctx.settings().gameEnabled()) settlement.finish(run, l.player, FinishReason.game_off, now);
            } else {
                noOffer = deadlines.miss(run, l.player, q, cfg, receivedAt);
            }
            Map<String, Object> e = new HashMap<>();
            e.put("run_id", run.runId); e.put("question_id", q.questionId); e.put("result", correct ? "correct" : "wrong");
            e.put("received_ms_after_t0", receivedAt.toEpochMilli() - q.releasedAt.toEpochMilli());
            ctx.tx().afterCommit(() -> ctx.events().emit("answer_submitted", "answer:" + q.questionId, e));
            if (reached != null) {
                Map<String, Object> ce = Map.of("run_id", run.runId, "checkpoint_id", reached.id(), "config_version", run.configVersion);
                ctx.tx().afterCommit(() -> ctx.events().emit("checkpoint_reached", "checkpoint:" + run.runId + ":" + ce.get("checkpoint_id"), ce));
            }
            return Result.ok(answerView(q, run, l.player, caller, reached, noOffer));
        });
        return r.get();
    }

    private AnswerView answerView(Question q, Run run, Player player, Caller caller, GameConfig.Checkpoint reached, String noOffer) {
        LastResult lr = ViewAssembler.lastResult(q);
        boolean continuePossible = run.state == RunState.AWAITING_OFFER && q.questionId.equals(run.missQuestionId);
        String reason = noOffer != null ? noOffer : run.noOfferReason;
        return new AnswerView(q.questionId, lr.result(), lr.choice(), lr.equalPrices(), lr.revealedPrice(), lr.direction(), run.score,
                reached == null ? null : ViewAssembler.checkpoint(reached), continuePossible, reason, run.isFinished(),
                views.run(run, player.record, caller.deviceId()), ctx.now());
    }

    // ------------------------------------------------------------------------------------------------ offer activation (F-02)

    public ActivationView activateOffer(Caller caller, String runId) {
        // Phase 1: checks that need no external call.
        Result<ActivationView> pre = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Locked l = lockForCommand(caller, runId, now);
            Run run = l.run;
            if (run.state == RunState.OFFER_OPEN || run.state == RunState.PAYMENT_PENDING) return Result.ok(activationView(run, l.player, caller, null, null));
            if (run.isFinished()) return Result.ok(activationView(run, l.player, caller, null, null));
            requireDevice(run, caller, l.player);
            if (run.state != RunState.AWAITING_OFFER) return Result.error(stateError("INVALID_STATE", run, l.player, caller));
            GameConfig cfg = ctx.config(run.configVersion);
            CatalogProduct ref = ctx.catalog().product(run.catalogVersion, run.referenceDkp).orElseThrow();
            if (!selection.hasNextQuestion(run.catalogVersion, cfg, ref.priceToman(), run.score, ctx.runs().runProductDkps(run.runId))) {
                deadlines.finishAfterMiss(run, l.player, NoOfferReason.no_products, now);
                return Result.ok(activationView(run, l.player, caller, null, null));
            }
            return Result.ok(null);                                          // needs the balance
        });
        if (pre.get() != null) return pre.get();

        // Phase 2: balance read outside any transaction (2 s timeout in the adapter). Unknown = too low (R-63).
        OptionalLong balance = ctx.coin().balance(caller.userId());

        // Phase 3: recheck and open the authoritative window now that the feedback has finished.
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Locked l = lockForCommand(caller, runId, now);
            Run run = l.run;
            if (run.state != RunState.AWAITING_OFFER) return activationView(run, l.player, caller, null, null);
            GameConfig cfg = ctx.config(run.configVersion);
            int cost = cfg.continueRule().cost();
            if (balance.isEmpty() || balance.getAsLong() < cost) {           // G05.NO_FUNDS: no offer, not even a disabled one
                deadlines.finishAfterMiss(run, l.player, NoOfferReason.no_funds, now);
                return activationView(run, l.player, caller, balance.isPresent() ? balance.getAsLong() : null, null);
            }
            Offer o = new Offer();
            o.offerId = Ids.newId();
            o.runId = run.runId;
            o.questionId = run.missQuestionId;
            o.state = OfferState.OPEN;
            o.cost = cost;
            o.openedAt = now;
            o.deadline = now.plusMillis(cfg.offerMs());
            ctx.runs().insertOffer(o);
            run.state = RunState.OFFER_OPEN;
            run.nextDeadlineAt = o.deadline;
            ctx.runs().updateRun(run);
            Map<String, Object> e = Map.of("offer_id", o.offerId, "run_id", run.runId, "cost", cost, "deadline", o.deadline.toString());
            ctx.tx().afterCommit(() -> ctx.events().emit("continue_offer_shown", "offer:" + o.offerId, e));
            return activationView(run, l.player, caller, balance.getAsLong(), o);
        });
    }

    private ActivationView activationView(Run run, Player player, Caller caller, Long balance, Offer created) {
        Offer o = created;
        if (o == null && (run.state == RunState.OFFER_OPEN || run.state == RunState.PAYMENT_PENDING))
            o = ctx.runs().latestOfferOfRun(run.runId).orElse(null);
        return new ActivationView(o == null ? null : ViewAssembler.offerView(o), balance, run.noOfferReason,
                views.run(run, player.record, caller.deviceId()), ctx.now());
    }

    // ------------------------------------------------------------------------------------------------ continue (charge)

    public OperationView continueOffer(Caller caller, String offerId, Instant receivedAt) {
        Result<String> r = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Offer peek = ctx.runs().findOffer(offerId).orElseThrow(GameException::notFound);
            Locked l = lockForCommand(caller, peek.runId, now);
            Run run = l.run;
            Offer offer = ctx.runs().lockOffer(offerId).orElseThrow();
            if (offer.state == OfferState.PAYMENT_PENDING || offer.state == OfferState.GRANTED || offer.operationId != null && offer.state == OfferState.ENDED_UNCONFIRMED)
                return Result.ok(offer.operationId);                         // one live operation per offer
            if (run.isFinished()) {
                if (offer.state == OfferState.EXPIRED) return Result.error(stateError("OFFER_EXPIRED", run, l.player, caller));
                return Result.error(finishedError(run, l.player, caller));
            }
            requireDevice(run, caller, l.player);
            if (offer.state != OfferState.OPEN || run.state != RunState.OFFER_OPEN) return Result.error(stateError("INVALID_STATE", run, l.player, caller));
            if (!offer.openAt(receivedAt)) return Result.error(stateError("OFFER_EXPIRED", run, l.player, caller));
            GameConfig cfg = ctx.config(run.configVersion);
            if (run.continuesUsed >= cfg.continueRule().cap()) return Result.error(stateError("CONTINUE_LIMIT_USED", run, l.player, caller));

            CoinOperation op = new CoinOperation();
            op.operationId = Ids.newId();
            op.userId = run.userId;
            op.runId = run.runId;
            op.offerId = offer.offerId;
            op.type = OperationType.CHARGE;
            op.amount = offer.cost;                                          // cost snapshot of this offer, never from the client
            op.status = OperationStatus.PENDING;
            op.createdAt = now;
            op.nextAttemptAt = now;                                          // the worker picks it up if this request dies after commit
            op.giveUpAt = now.plusSeconds(72 * 3600);
            ctx.ledger().insertOperation(op);
            offer.state = OfferState.PAYMENT_PENDING;
            offer.operationId = op.operationId;
            ctx.runs().updateOffer(offer);
            run.state = RunState.PAYMENT_PENDING;
            run.nextDeadlineAt = offer.deadline;                             // the ring keeps running during G06 (R-39)
            ctx.runs().updateRun(run);
            Map<String, Object> e = Map.of("offer_id", offer.offerId, "operation_id", op.operationId);
            ctx.tx().afterCommit(() -> ctx.events().emit("continue_requested", "continue_requested:" + op.operationId, e));
            return Result.ok(op.operationId);
        });
        String operationId = r.get();
        payments.process(operationId);                                       // immediate attempt; the worker covers crashes and UNKNOWN
        return operation(caller, operationId);
    }

    // ------------------------------------------------------------------------------------------------ skip / leave / onboarding

    public RunView skip(Caller caller, String offerId) {
        Result<RunView> r = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Offer peek = ctx.runs().findOffer(offerId).orElseThrow(GameException::notFound);
            Locked l = lockForCommand(caller, peek.runId, now);
            Run run = l.run;
            Offer offer = ctx.runs().lockOffer(offerId).orElseThrow();
            if (offer.state == OfferState.PAYMENT_PENDING)                   // a decision is already being processed
                return Result.error(stateError("OPERATION_PENDING", run, l.player, caller));
            if (run.isFinished() || offer.state != OfferState.OPEN) return Result.ok(views.run(run, l.player.record, caller.deviceId()));
            requireDevice(run, caller, l.player);
            settlement.finish(run, l.player, FinishReason.skipped, now);
            return Result.ok(views.run(run, l.player.record, caller.deviceId()));
        });
        return r.get();
    }

    public RunView leave(Caller caller, String runId) {
        Result<RunView> r = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Locked l = lockForCommand(caller, runId, now);
            Run run = l.run;
            if (run.isFinished()) return Result.ok(views.run(run, l.player.record, caller.deviceId()));
            requireDevice(run, caller, l.player);
            if (run.state == RunState.PAYMENT_PENDING)                       // back is blocked while a payment is checked
                return Result.error(stateError("OPERATION_PENDING", run, l.player, caller));
            FinishReason reason = (run.state == RunState.OFFER_OPEN || run.state == RunState.AWAITING_OFFER) ? FinishReason.skipped : FinishReason.left;
            settlement.finish(run, l.player, reason, now);
            return Result.ok(views.run(run, l.player.record, caller.deviceId()));
        });
        return r.get();
    }

    public void onboarding(Caller caller) {
        ctx.tx().run(() -> {
            Player p = ctx.players().lockOrCreatePlayer(caller.userId(), ctx.now());
            if (!p.onboarded) { p.onboarded = true; ctx.players().updatePlayer(p, ctx.now()); }
        });
    }

    // ------------------------------------------------------------------------------------------------ operation status

    public OperationView operation(Caller caller, String operationId) {
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            CoinOperation peek = ctx.ledger().findOperation(operationId).orElseThrow(GameException::notFound);
            if (!peek.userId.equals(caller.userId()) || peek.type != OperationType.CHARGE) throw GameException.notFound();
            Locked l = lockForCommand(caller, peek.runId, now);
            CoinOperation op = ctx.ledger().findOperation(operationId).orElseThrow();
            Offer offer = ctx.runs().findOffer(op.offerId).orElseThrow();
            Optional<CoinOperation> refund = ctx.ledger().refundOf(op.operationId);
            String chargeStatus = op.status == OperationStatus.SUCCEEDED ? "confirmed" : op.status == OperationStatus.FAILED ? "failed" : "unknown";
            String refundStatus = refund.map(x -> x.status == OperationStatus.SUCCEEDED ? "succeeded" : "pending").orElse("none");
            String status;
            if (offer.state == OfferState.GRANTED) status = "succeeded";
            else if (op.status == OperationStatus.FAILED) status = "failed";
            else if ("succeeded".equals(refundStatus)) status = "refunded";
            else if (offer.state == OfferState.ENDED_UNCONFIRMED || offer.state == OfferState.CANCELLED || l.run.isFinished()) status = "expired_unconfirmed";
            else status = "pending";
            int refunded = refund.filter(x -> x.status == OperationStatus.SUCCEEDED).map(x -> x.amount).orElse(0);
            return new OperationView(op.operationId, status, chargeStatus, refundStatus, op.status == OperationStatus.SUCCEEDED ? op.amount : 0,
                    refunded, null, views.run(l.run, l.player.record, caller.deviceId()), now);
        });
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record Locked(Player player, Run run) {}

    /** Player lock, run lock, ownership (foreign ids are 404), then due transitions. */
    Locked lockForCommand(Caller caller, String runId, Instant now) {
        Player player = ctx.players().lockOrCreatePlayer(caller.userId(), now);
        Run run = ctx.runs().lockRun(runId).orElseThrow(GameException::notFound);
        if (!run.userId.equals(caller.userId())) throw GameException.notFound();
        deadlines.applyDue(run, player, now);
        return new Locked(player, run);
    }

    private void requireDevice(Run run, Caller caller, Player player) {
        if (!run.deviceId.equals(caller.deviceId()))                         // another device cannot resume this run (R-28)
            throw new GameException("ACTIVE_RUN_OTHER_DEVICE", 409, "this run is open on another device",
                    Map.of("state", views.run(run, player.record, caller.deviceId())));
    }

    GameException finishedError(Run run, Player player, Caller caller) {
        String code = switch (run.finishReason) {
            case replaced -> "RUN_REPLACED";
            case blocked -> "PLAYER_BLOCKED";
            case game_off -> "GAME_UNAVAILABLE";
            default -> "RUN_FINISHED";
        };
        return stateError(code, run, player, caller);
    }

    GameException stateError(String code, Run run, Player player, Caller caller) {
        return new GameException(code, 409, code.toLowerCase().replace('_', ' '), Map.of("state", views.run(run, player.record, caller.deviceId())));
    }

    /** Carries a value or an error raised only after the transaction committed. */
    record Result<T>(T value, GameException error) {
        static <T> Result<T> ok(T v) { return new Result<>(v, null); }
        static <T> Result<T> error(GameException e) { return new Result<>(null, e); }
        T get() { if (error != null) throw error; return value; }
    }

    /** Snapshot of the settings row used by start. */
    private record ConfigRepositorySettings(boolean gameEnabled, String message, int activeConfigVersion, int activeCatalogVersion) {
        static ConfigRepositorySettings of(PriceExpertContext ctx) {
            var s = ctx.settings();
            return new ConfigRepositorySettings(s.gameEnabled(), s.playerMessage(), ctx.activeConfigVersion(), ctx.activeCatalogVersion());
        }
    }
}
