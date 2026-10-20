package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Offer;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Question;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs, questions and offers. Lock order inside a transaction: player → run → question → offer → ledger rows.
 * {@code update*} methods are conditional on the loaded state revision and fail loudly when it moved.
 */
public interface RunRepository {
    Optional<Run> lockRun(String runId);
    /** Like lockRun but returns empty instead of waiting when another transaction holds the row (sweeper). */
    Optional<Run> tryLockRun(String runId);
    Optional<Run> findRun(String runId);
    Optional<Run> lockActiveRunOfUser(String userId);
    Optional<Run> findActiveRunOfUser(String userId);
    void insertRun(Run run);
    void updateRun(Run run);
    List<String> dueRunIds(Instant now, int limit);
    /** Active (not finished) run ids after {@code afterRunId} in id order: keyset pages over every active run. */
    List<String> activeRunIds(String afterRunId, int limit);

    void insertQuestion(Question q);
    Optional<Question> findQuestion(String questionId);
    Optional<Question> lockQuestion(String questionId);
    void updateQuestion(Question q);
    /**
     * Records an answer receipt (choice + trusted arrival time) on an OPEN question of the caller's own run on its own
     * device, only if it arrived by the accept deadline and no receipt exists yet (first receipt wins). Takes only the
     * question row lock, so it never waits behind a run lock. Returns true when recorded.
     */
    boolean recordAnswerReceipt(String questionId, String userId, String deviceId, Choice choice, Instant receivedAt);
    Optional<Question> openQuestion(String runId);
    Optional<Question> latestSettledQuestion(String runId);
    List<Question> questionsOfRun(String runId);
    /** Every product introduced in the run: initial reference, all comparisons and the current reference. */
    Set<Long> runProductDkps(String runId);

    void insertOffer(Offer offer);
    Optional<Offer> findOffer(String offerId);
    Optional<Offer> lockOffer(String offerId);
    void updateOffer(Offer offer);
    Optional<Offer> latestOfferOfRun(String runId);
}
