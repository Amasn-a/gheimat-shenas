package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OfferState;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.QuestionState;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.RunState;

import java.util.Optional;

/** Builds response views. Called inside the transaction that produced the state, so a view is never torn. */
public final class ViewAssembler {
    private final PriceExpertContext ctx;

    public ViewAssembler(PriceExpertContext ctx) { this.ctx = ctx; }

    public RunView run(Run run, int record, String deviceId) {
        GameConfig cfg = ctx.config(run.configVersion);
        CatalogProduct ref = ctx.catalog().product(run.catalogVersion, run.referenceDkp).orElseThrow();
        QuestionView question = null;
        // response minimisation: another device of the same account learns that a question is open, not which product
        if (run.state == RunState.QUESTION_OPEN && deviceId != null && deviceId.equals(run.deviceId)) {
            Question q = ctx.runs().openQuestion(run.runId).orElseThrow();
            question = questionView(q, run.catalogVersion);
        }
        LastResult last = ctx.runs().latestSettledQuestion(run.runId).map(ViewAssembler::lastResult).orElse(null);
        OfferView offer = null;
        OperationRef op = null;
        Optional<Offer> o = ctx.runs().latestOfferOfRun(run.runId);
        if (o.isPresent() && (run.state == RunState.OFFER_OPEN || run.state == RunState.PAYMENT_PENDING)) {
            offer = offerView(o.get(), cfg);
            if (o.get().operationId != null) op = new OperationRef(o.get().operationId, "pending");
        }
        return new RunView(run.runId, run.state.name(), run.finishReason == null ? null : run.finishReason.name(), run.noOfferReason,
                run.score, record, run.continuesUsed, checkpoint(cfg, run.highestCheckpointId), new ReferenceCard(ref.displayTitle(),
                ref.imageUrl(), ref.priceToman()), question, last, offer, op,
                run.state == RunState.AWAITING_OFFER ? run.nextDeadlineAt : null,
                deviceId != null && deviceId.equals(run.deviceId), runConfig(cfg, run.configVersion, run.catalogVersion), ctx.now());
    }

    public QuestionView questionView(Question q, int catalogVersion) {
        CatalogProduct p = ctx.catalog().product(catalogVersion, q.comparisonDkp).orElseThrow();
        return new QuestionView(q.questionId, new ProductCard(p.displayTitle(), p.imageUrl()), q.releasedAt, q.inputDeadline, q.acceptDeadline);
    }

    public static LastResult lastResult(Question q) {
        if (q.state == QuestionState.OPEN || q.state == QuestionState.UNANSWERED) return null;
        String result = switch (q.state) { case CORRECT -> "correct"; case WRONG -> "wrong"; default -> "timeout"; };
        String direction = q.comparisonPrice == q.referencePrice ? "equal" : q.comparisonPrice > q.referencePrice ? "higher" : "lower";
        return new LastResult(q.questionId, result, q.choice == null ? null : q.choice.name(), q.comparisonPrice, q.equalPrices(),
                direction, q.settledAt);
    }

    public static OfferView offerView(Offer o, GameConfig cfg) {
        return new OfferView(o.offerId, o.state.name(), o.openedAt, o.deadline, o.acceptDeadline, o.resolveBy(cfg.paymentResolutionMs()), o.cost);
    }

    public static CheckpointView checkpoint(GameConfig cfg, String checkpointId) {
        if (checkpointId == null) return null;
        return cfg.checkpoints().stream().filter(c -> c.id().equals(checkpointId)).findFirst()
                .map(ViewAssembler::checkpoint).orElse(null);
    }

    public static CheckpointView checkpoint(GameConfig.Checkpoint c) {
        return new CheckpointView(c.id(), c.title(), c.badgeAssetId(), c.reward(), c.requiredCorrect(), c.comparisonPercent());
    }

    public static RunConfigView runConfig(GameConfig cfg, int configVersion, int catalogVersion) {
        return new RunConfigView(configVersion, catalogVersion, cfg.inputMs(), cfg.acceptMs(), cfg.offerMs(), cfg.offerAcceptMs(), cfg.checkpointAutoContinueMs(),
                cfg.enabledCheckpoints().stream().map(ViewAssembler::checkpoint).toList(),
                new ContinueRuleView(cfg.continueRule().enabled(), cfg.continueRule().cost(), cfg.continueRule().cap()),
                features(cfg), cfg.payOnCrossing(), cfg.recordReward(), cfg.recordRewardAbove());
    }

    /** A configuration without switches (older rows) has everything on, as {@link GameConfig#effectiveStreakReward()} assumes. */
    public static FeaturesView features(GameConfig cfg) {
        GameConfig.Features f = cfg.features();
        return f == null ? new FeaturesView(true, true, true, true) : new FeaturesView(f.streak(), f.share(), f.products(), f.haptics());
    }

    static boolean liveOffer(Offer o) { return o.state == OfferState.OPEN || o.state == OfferState.PAYMENT_PENDING; }
}
