package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.DailyRewardDay;
import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.DailyRewardView;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CoinOperation;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationStatus;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Player;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.StreakPolicy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily reward modal «جایزه‌ی روزانه» (owner brief 2026-10-20). The earning rule is unchanged (spec «استریک و پاداش
 * روزانه»): the first finished run of a start day records that day's reward once, at the configured amount. What
 * changed is delivery: the recorded reward waits as CLAIMABLE and is credited only when the player claims it. Opening
 * the modal claims nothing; a claim is one locked transition CLAIMABLE → PENDING, so repeated taps, retries with any key
 * and a second device cannot credit a day twice (the per-day unique index stays the last guard).
 */
public final class DailyRewardService {
    /** PROPOSED: a claim still unconfirmed after this long is shown as delayed instead of processing. */
    static final Duration PROCESSING_FOR = Duration.ofMinutes(2);
    static final int DAYS_SHOWN = 7;

    private final PriceExpertContext ctx;
    private final PaymentService payments;

    public DailyRewardService(PriceExpertContext ctx, PaymentService payments) {
        this.ctx = ctx;
        this.payments = payments;
    }

    public DailyRewardView status(Caller caller) {
        return ctx.tx().inTransaction(() -> view(caller.userId(), ctx.now()));
    }

    public DailyRewardView claim(Caller caller) {
        List<String> claimed = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Player p = ctx.players().lockOrCreatePlayer(caller.userId(), now);      // serialises claims of all devices
            if (p.blocked) throw GameException.conflict("PLAYER_BLOCKED", "player blocked");
            List<String> ids = new ArrayList<>();
            for (CoinOperation op : ctx.ledger().lockClaimableStreakRewards(caller.userId())) {
                op.status = OperationStatus.PENDING;
                op.claimedAt = now;
                op.nextAttemptAt = now;                                         // the worker delivers it if this request dies
                op.giveUpAt = now.plusSeconds(72 * 3600);
                ctx.ledger().updateOperation(op);
                ids.add(op.operationId);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("operation_id", op.operationId); e.put("reward_date", String.valueOf(op.capDay)); e.put("amount", op.amount);
                ctx.tx().afterCommit(() -> ctx.events().emit("daily_reward_claimed", "daily_reward_claimed:" + op.operationId, e));
            }
            return ids;
        });
        claimed.forEach(payments::process);                                      // immediate attempt outside any transaction
        return status(caller);
    }

    /** Caller holds a transaction. Also used by bootstrap. */
    DailyRewardView view(String userId, Instant now) {
        LocalDate today = BusinessCalendar.dateOf(now);
        List<LocalDate> eligible = ctx.players().streakEligibleDates(userId, today.minusDays(400));
        StreakPolicy.Streak streak = StreakPolicy.compute(eligible, today);
        Set<LocalDate> attended = new HashSet<>(eligible);
        List<DailyRewardDay> days = new ArrayList<>();
        for (int i = DAYS_SHOWN - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            days.add(new DailyRewardDay(d, attended.contains(d), i == 0));
        }
        List<CoinOperation> claimable = ctx.ledger().lockClaimableStreakRewards(userId);
        String state;
        List<CoinOperation> shown;
        if (!claimable.isEmpty()) { state = "claimable"; shown = claimable; }
        else {
            shown = ctx.ledger().latestStreakClaim(userId);
            if (shown.isEmpty()) state = "none";
            else if (shown.stream().allMatch(o -> o.status == OperationStatus.SUCCEEDED)) state = "claimed";
            else if (shown.stream().anyMatch(o -> o.status == OperationStatus.PENDING || o.status == OperationStatus.UNKNOWN)
                    && shown.get(0).claimedAt.plus(PROCESSING_FOR).isAfter(now)) state = "processing";
            else state = "delayed";
        }
        int amount = shown.stream().mapToInt(o -> o.amount).sum();
        Instant claimedAt = shown.isEmpty() ? null : shown.get(0).claimedAt;
        return new DailyRewardView(state, amount, streak.days(), days, shown.stream().map(o -> o.operationId).toList(), claimedAt, now);
    }
}
