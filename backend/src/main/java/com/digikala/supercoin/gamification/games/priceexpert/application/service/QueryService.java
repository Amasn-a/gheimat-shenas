package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignRepository.Entitlement;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Locked;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.StreakPolicy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Player reads. Reads that may observe a due deadline apply it first (under locks) so they never show a stale state. */
public final class QueryService {
    /** A credit still pending after this long is shown as delayed («واریز با تأخیر انجام می‌شه»). Proposed. */
    static final Duration DELAYED_AFTER = Duration.ofSeconds(10);
    public static final String PRODUCT_LINK_TEMPLATE = "https://www.digikala.com/product/dkp-%d/";

    private final PriceExpertContext ctx;
    private final RunService runs;
    private final ViewAssembler views;

    public QueryService(PriceExpertContext ctx, RunService runs, ViewAssembler views) {
        this.ctx = ctx;
        this.runs = runs;
        this.views = views;
    }

    public BootstrapView bootstrap(Caller caller) {
        OptionalLong balance = ctx.coin().balance(caller.userId());          // outside the transaction; may be unknown
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Player player = ctx.players().lockOrCreatePlayer(caller.userId(), now);
            var settings = ctx.settings();
            Access access = player.blocked ? new Access("blocked", null)
                    : !settings.gameEnabled() ? new Access("game_off", settings.playerMessage()) : new Access("ok", null);
            int cfgVersion = ctx.activeConfigVersion();
            GameConfig cfg = ctx.config(cfgVersion);
            LocalDate today = BusinessCalendar.dateOf(now);

            ActiveRun active = null;
            Optional<Run> ar = ctx.runs().lockActiveRunOfUser(caller.userId());
            if (ar.isPresent()) {
                Locked l = runs.lockForCommand(caller, ar.get().runId, now);
                Run run = l.run();
                if (!run.isFinished()) {
                    Instant offerDeadline = null;
                    String opId = null;
                    if (run.state == RunState.OFFER_OPEN || run.state == RunState.PAYMENT_PENDING) {
                        Offer o = ctx.runs().latestOfferOfRun(run.runId).orElseThrow();
                        offerDeadline = o.deadline;
                        opId = o.operationId;
                    }
                    active = new ActiveRun(run.runId, run.state.name(), caller.deviceId().equals(run.deviceId), run.score,
                            ViewAssembler.checkpoint(ctx.config(run.configVersion), run.highestCheckpointId), offerDeadline, opId);
                }
            }
            player = ctx.players().findPlayer(caller.userId()).orElseThrow();
            int playsUsed = ctx.players().playsUsed(caller.userId(), today);
            StreakPolicy.Streak streak = StreakPolicy.compute(ctx.players().streakEligibleDates(caller.userId(), today.minusDays(400)), today);
            String rewardToday = ctx.ledger().streakRewardOf(caller.userId(), today)
                    .map(op -> op.status == OperationStatus.SUCCEEDED ? "paid" : "pending").orElse("none");
            CheckpointView best = bestCheckpoint(caller.userId(), cfg);
            CampaignBanner banner = ctx.campaigns().liveAt(now).flatMap(c -> cfg.enabledCheckpoints().stream()
                    .filter(cp -> c.codeCheckpointIds().contains(cp.id())).findFirst()
                    .map(cp -> new CampaignBanner(c.campaignId(), c.name(), cp.requiredCorrect(), cp.title(), c.codeAmountToman()))).orElse(null);
            return new BootstrapView(access, !player.onboarded, new Quota(Math.max(0, cfg.playsPerDay() - playsUsed), cfg.playsPerDay(),
                    BusinessCalendar.nextMidnight(now)), player.record, best, new Streak(streak.days(), rewardToday, streak.lostNotice()),
                    balance.isPresent() ? balance.getAsLong() : null, active,
                    ViewAssembler.runConfig(cfg, cfgVersion, ctx.activeCatalogVersion()), cfg.streakReward(), banner, now);
        });
    }

    private CheckpointView bestCheckpoint(String userId, GameConfig cfg) {
        // Highest checkpoint title ever reached is not stored separately in P0; the record run's checkpoint is used.
        return ctx.players().findPlayer(userId).map(p -> p.recordRunId).flatMap(ctx.runs()::findRun)
                .map(r -> ViewAssembler.checkpoint(ctx.config(r.configVersion), r.highestCheckpointId)).orElse(null);
    }

    public RunView run(Caller caller, String runId) {
        return ctx.tx().inTransaction(() -> {
            Locked l = runs.lockForCommand(caller, runId, ctx.now());
            return views.run(l.run(), l.player().record, caller.deviceId());
        });
    }

    public ResultView result(Caller caller, String runId) {
        OptionalLong balance = ctx.coin().balance(caller.userId());
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Locked l = runs.lockForCommand(caller, runId, now);
            Run run = l.run();
            if (!run.isFinished()) throw runs.stateError("RUN_NOT_FINISHED", run, l.player(), caller);
            GameConfig cfg = ctx.config(run.configVersion);
            List<CoinOperation> ops = ctx.ledger().operationsOfRun(run.runId);
            RewardStatus cpReward = ops.stream().filter(o -> o.type == OperationType.CHECKPOINT_REWARD).findFirst()
                    .map(o -> rewardStatus(o, now)).orElse(new RewardStatus(0, 0, run.finishReason == FinishReason.blocked && run.highestCheckpointId != null ? "cancelled" : "none"));
            RewardStatus streakReward = ops.stream().filter(o -> o.type == OperationType.STREAK_REWARD).findFirst().map(o -> rewardStatus(o, now)).orElse(null);
            LocalDate today = BusinessCalendar.dateOf(now);
            StreakPolicy.Streak streak = StreakPolicy.compute(ctx.players().streakEligibleDates(run.userId, today.minusDays(400)), today);
            NextGoal next = cfg.enabledCheckpoints().stream().filter(cp -> cp.requiredCorrect() > run.score).findFirst()
                    .map(cp -> new NextGoal(cp.requiredCorrect() - run.score, cp.title(), cp.reward(), cp.requiredCorrect())).orElse(null);
            int playsUsed = ctx.players().playsUsed(run.userId, today);
            int activeCfgVersion = ctx.activeConfigVersion();
            GameConfig active = ctx.config(activeCfgVersion);
            boolean limitUsed = NoOfferReason.limit_used.name().equals(run.noOfferReason);
            return new ResultView(run.runId, run.score, run.finishReason.name(), run.noOfferReason, l.player().record,
                    Boolean.TRUE.equals(run.newRecord), run.recordBefore == null ? 0 : run.recordBefore,
                    run.finishReason == FinishReason.blocked ? null : ViewAssembler.checkpoint(cfg, run.highestCheckpointId), cpReward,
                    new StreakResult(streak.days(), streakReward, streak.lostNotice()), run.continuesUsed, limitUsed, next,
                    new Quota(Math.max(0, active.playsPerDay() - playsUsed), active.playsPerDay(), BusinessCalendar.nextMidnight(now)),
                    balance.isPresent() ? balance.getAsLong() : null, campaignCode(run), run.finishedAt, now);
        });
    }

    private RewardStatus rewardStatus(CoinOperation o, Instant now) {
        // a held part is never paid yet, so the reward as a whole is delayed
        String status = o.heldAmount > 0 ? "delayed" : switch (o.status) {
            case SUCCEEDED -> "succeeded";
            case HELD, NEEDS_REVIEW, UNKNOWN -> "delayed";
            case FAILED -> "delayed";
            case PENDING -> o.createdAt.plus(DELAYED_AFTER).isBefore(now) ? "delayed" : "pending";
        };
        return new RewardStatus(o.amount + o.heldAmount, o.heldAmount, status);
    }

    private CampaignCode campaignCode(Run run) {
        if (run.campaignOutcome == null || run.campaignOutcome.equals("not_reached") || run.campaignOutcome.equals("already_issued")) return null;
        Campaign c = ctx.campaigns().find(run.campaignId).orElse(null);
        if (c == null) return null;
        if (run.campaignOutcome.equals("sold_out")) return new CampaignCode("sold_out", null, c.codeAmountToman(), c.terms(), null);
        Optional<Entitlement> e = ctx.campaigns().entitlementOfRun(run.runId);
        if (e.isEmpty()) return null;
        if (!"ISSUED".equals(e.get().status())) return new CampaignCode("pending", null, c.codeAmountToman(), c.terms(), null);
        return new CampaignCode("issued", ctx.cipher().decrypt(e.get().codeCiphertext()), e.get().amountToman(), c.terms(), e.get().expiresAt());
    }

    public ProductsView products(Caller caller, String runId) {
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Locked l = runs.lockForCommand(caller, runId, now);
            Run run = l.run();
            if (!run.isFinished()) throw runs.stateError("RUN_NOT_FINISHED", run, l.player(), caller);   // no open-question leak
            List<Question> qs = ctx.runs().questionsOfRun(run.runId);
            List<ProductItem> items = new ArrayList<>();
            long firstRef = qs.isEmpty() ? run.referenceDkp : qs.get(0).referenceDkp;
            CatalogProduct first = ctx.catalog().product(run.catalogVersion, firstRef).orElseThrow();
            items.add(item(1, "reference", first, first.priceToman(), null, false));
            String lossProduct = null;
            for (Question q : qs) {
                CatalogProduct p = ctx.catalog().product(run.catalogVersion, q.comparisonDkp).orElseThrow();
                boolean loss = q.questionId.equals(run.lossQuestionId);
                if (loss) lossProduct = String.valueOf(p.dkp());
                String result = switch (q.state) { case CORRECT -> "correct"; case WRONG -> "wrong"; case TIMED_OUT -> "timeout"; default -> "unanswered"; };
                Long price = q.state == QuestionState.UNANSWERED ? null : q.comparisonPrice;   // revealed only for settled questions
                items.add(item(items.size() + 1, "question", p, price, result, loss));
            }
            return new ProductsView(run.runId, items, lossProduct, now);
        });
    }

    private static ProductItem item(int pos, String role, CatalogProduct p, Long price, String result, boolean loss) {
        return new ProductItem(pos, role, String.valueOf(p.dkp()), String.valueOf(p.variantId()), p.displayTitle(), p.title(), p.imageUrl(),
                price, result, loss, String.format(PRODUCT_LINK_TEMPLATE, p.dkp()));
    }

    public ShareView share(Caller caller, String runId) {
        return ctx.tx().inTransaction(() -> {
            Locked l = runs.lockForCommand(caller, runId, ctx.now());
            Run run = l.run();
            if (!run.isFinished()) throw runs.stateError("RUN_NOT_FINISHED", run, l.player(), caller);
            CheckpointView cp = run.finishReason == FinishReason.blocked ? null : ViewAssembler.checkpoint(ctx.config(run.configVersion), run.highestCheckpointId);
            return new ShareView(run.score, cp == null ? null : cp.title(), cp == null ? null : cp.badgeAssetId(), l.player().streakDays,
                    "قیمت‌شناس", null);                                       // no host deep-link contract supplied: image only (R-56)
        });
    }
}
