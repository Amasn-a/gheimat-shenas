package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignRepository.Entitlement;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.LedgerRepository.DailyCredit;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.PlayerRepository.Participation;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.StreakPolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one conditional finish every ending goes through (handoff "Run endings"). Must be called inside a transaction
 * holding the player row lock and the run row lock. Rewards are created once per run by unique keys; external
 * delivery happens later through the ledger worker.
 */
public final class SettlementService {
    private final PriceExpertContext ctx;

    public SettlementService(PriceExpertContext ctx) { this.ctx = ctx; }

    public void finish(Run run, Player player, FinishReason reason, Instant at) {
        if (run.isFinished()) return;
        GameConfig cfg = ctx.config(run.configVersion);

        ctx.runs().openQuestion(run.runId).ifPresent(q -> {           // leave / block / replacement: no correctness (R-59)
            q.state = QuestionState.UNANSWERED;
            q.choice = null;                                          // an answer receipt not yet settled does not count
            q.receivedAt = null;
            q.settledAt = at;
            ctx.runs().updateQuestion(q);
        });
        ctx.runs().latestOfferOfRun(run.runId).filter(ViewAssembler::liveOffer).ifPresent(o -> {
            if (o.state == OfferState.PAYMENT_PENDING) o.state = OfferState.ENDED_UNCONFIRMED;   // a later confirmation is refunded (R-43)
            else o.state = switch (reason) { case skipped -> OfferState.SKIPPED; case expired -> OfferState.EXPIRED; default -> OfferState.CANCELLED; };
            o.closedAt = at;
            ctx.runs().updateOffer(o);
        });

        run.state = RunState.FINISHED;
        run.finishReason = reason;
        run.finishedAt = at;
        run.nextDeadlineAt = null;
        run.lossQuestionId = reason.endsOnMiss() ? run.missQuestionId : null;   // UX-5: only a terminal miss is a loss target
        run.recordBefore = player.record;
        run.newRecord = false;

        boolean blocked = reason == FinishReason.blocked;
        Map<String, Object> event = new LinkedHashMap<>();
        if (!blocked) {
            // Record (spec: highest score of a finished run; a tie is not a new record; continued score counts).
            if (run.score > player.record) {
                player.record = run.score;
                player.recordRunId = run.runId;
                run.newRecord = true;
            }
            // Streak day = run start day; the first finished run of a start day earns that day's reward once.
            Participation p = ctx.players().lockParticipation(run.userId, run.startBusinessDate);
            ctx.players().saveParticipation(new Participation(p.userId(), p.date(), p.playsUsed(), p.finishedRuns() + 1, true));
            if (cfg.effectiveStreakReward() > 0 && ctx.ledger().streakRewardOf(run.userId, run.startBusinessDate).isEmpty()) {
                createCredit(run, OperationType.STREAK_REWARD, cfg.effectiveStreakReward(), at);
            }
            LocalDate today = BusinessCalendar.dateOf(at);
            List<LocalDate> dates = ctx.players().streakEligibleDates(run.userId, today.minusDays(400));
            StreakPolicy.Streak s = StreakPolicy.compute(dates, today);
            player.streakDays = s.days();
            player.streakLastDate = s.lastDate();

            // Highest checkpoint only, paid at run end (R-17); never cumulative.
            GameConfig.Checkpoint cp = cfg.checkpoints().stream().filter(c -> c.id().equals(run.highestCheckpointId)).findFirst().orElse(null);
            if (cp != null && cp.reward() > 0) createCredit(run, OperationType.CHECKPOINT_REWARD, cp.reward(), at);

            run.campaignOutcome = campaignOutcome(run, cfg, at);
        }
        ctx.runs().updateRun(run);
        ctx.players().updatePlayer(player, at);

        event.put("run_id", run.runId);
        event.put("score", run.score);
        event.put("reason", reason.name());
        event.put("start_date", run.startBusinessDate.toString());
        event.put("continues", run.continuesUsed);
        event.put("checkpoint_id", run.highestCheckpointId);
        ctx.tx().afterCommit(() -> ctx.events().emit("session_end", "session_end:" + run.runId, event));
    }

    /**
     * Writes a reward credit into the ledger against the run's start-day cap (R-47, F-03). The part above the cap is
     * held (operation status HELD / held_amount) and raises an alert; nothing above the cap is ever sent to the wallet.
     */
    public CoinOperation createCredit(Run run, OperationType type, int amount, Instant now) {
        LocalDate capDay = run.startBusinessDate;
        int cap = ctx.ledger().capHighWater(capDay, ctx.config(run.configVersion).dailyAccountCap(), now);
        DailyCredit dc = ctx.ledger().lockDailyCredit(run.userId, capDay);
        int allowed = Math.max(0, cap - dc.credited());
        int grant = Math.min(amount, allowed);
        int held = amount - grant;
        ctx.ledger().saveDailyCredit(run.userId, capDay, new DailyCredit(dc.credited() + grant, dc.held() + held));

        CoinOperation op = new CoinOperation();
        op.operationId = Ids.newId();
        op.userId = run.userId;
        op.runId = run.runId;
        op.type = type;
        op.amount = grant;
        op.heldAmount = held;
        op.status = grant > 0 ? OperationStatus.PENDING : OperationStatus.HELD;
        op.capDay = capDay;
        op.createdAt = now;
        op.nextAttemptAt = grant > 0 ? now : null;
        op.giveUpAt = now.plusSeconds(72 * 3600);
        ctx.ledger().insertOperation(op);
        if (held > 0) {
            ctx.ledger().alert("DAILY_CAP_HELD", "cap:" + op.operationId,
                    "{\"user_id\":\"" + run.userId + "\",\"operation_id\":\"" + op.operationId + "\",\"held\":" + held + ",\"cap_day\":\"" + capDay + "\",\"cap\":" + cap + "}", now);
        }
        return op;
    }

    /** R-29/R-37/R-48: one bound code per account and campaign, within code cap and budget; sold out keeps the coins. */
    private String campaignOutcome(Run run, GameConfig cfg, Instant now) {
        if (run.campaignId == null) return null;
        Optional<Campaign> camp = ctx.campaigns().find(run.campaignId);
        if (camp.isEmpty()) return null;
        Campaign c = camp.get();
        boolean reached = cfg.checkpoints().stream().anyMatch(cp -> c.codeCheckpointIds().contains(cp.id()) && cp.requiredCorrect() <= run.score);
        if (!reached) return "not_reached";
        if (ctx.campaigns().entitlement(c.campaignId(), run.userId).isPresent()) return "already_issued";
        if (!ctx.campaigns().reserveCode(c.campaignId(), c.codeAmountToman())) return "sold_out";
        ctx.campaigns().insertEntitlement(new Entitlement(Ids.newId(), c.campaignId(), run.userId, run.runId, "PENDING", c.codeAmountToman(),
                null, null, null, 0, now, null, now, null));
        return "pending";
    }
}
