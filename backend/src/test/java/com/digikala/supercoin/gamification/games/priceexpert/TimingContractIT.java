package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner brief of 2026-10-20, timing contract. Times in the names are milliseconds after the authoritative start
 * (question t0 / offer start). "Arrival" is the trusted server receive time; "now" is when processing happens.
 * <ul>
 *   <li>Answer: 10,000 ms visible window; an initial request is admitted when it ARRIVES at or before t0 + 12,500
 *       (inclusive: 12,500 is in, 12,501 is out). Admission is final: finishing later never turns it into a timeout.</li>
 *   <li>Extra life: 10,000 ms visible window; a purchase is admitted when it arrives at or before offer start + 15,000
 *       (inclusive). Once admitted, the charge may complete after both deadlines and its confirmed outcome applies.</li>
 * </ul>
 */
class TimingContractIT {
    static TestEnv env;

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }

    static String user() { return "t" + UUID.randomUUID().toString().substring(0, 8); }

    /** Makes an operation due on the test clock (retry backoff and lease elapsed). */
    static void due(String operationId) {
        env.sql("UPDATE pe_coin_operation SET next_attempt_at = ? WHERE operation_id = ?",
                java.time.OffsetDateTime.ofInstant(env.clock.instant().minusSeconds(1), java.time.ZoneOffset.UTC), operationId);
    }

    record Q(Caller c, String runId, QuestionView q) {
        Instant at(long ms) { return q.t0().plusMillis(ms); }
    }

    Q open() {
        Caller c = env.caller(user());
        String run = env.m.runs.start(c).run().runId();
        return new Q(c, run, env.m.runs.reveal(c, run).question());
    }

    String right(Q x) { return env.rightChoice(x.runId()); }

    AnswerView answer(Q x, String choice, long arrivalMs, long nowMs) {
        env.clock.set(x.at(nowMs));                                       // processing happens now; the request arrived earlier
        return env.m.runs.answer(x.c(), x.q().questionId(), Choice.valueOf(choice), x.at(arrivalMs));
    }

    // ------------------------------------------------------------------------------------------------ answers

    @Test void clickAt9900_arrivesAt12000_respondsAt14000_isEvaluated() {
        Q x = open();
        String r = right(x);
        AnswerView a = answer(x, r, 12_000, 14_000);
        assertEquals("correct", a.result(), "an admitted answer is evaluated even though it finishes after the arrival deadline");
        assertEquals(1, a.score());
    }

    @Test void arrivalAfterTheVisibleWindowIsNotARejectionReason() {
        Q x = open();
        AnswerView a = answer(x, TestEnv.flip(right(x)), 10_800, 10_800);
        assertEquals("wrong", a.result(), "10,800 ms is after the visible 10 s but before 12,500 ms: evaluated, not expired");
    }

    @Test void arrivalBoundaryIsInclusiveAt12500() {
        for (long ms : new long[]{12_499, 12_500}) {
            Q x = open();
            assertEquals("correct", answer(x, right(x), ms, ms).result(), "arrival at " + ms);
        }
        Q x = open();
        String r = right(x);
        GameException e = assertThrows(GameException.class, () -> answer(x, r, 12_501, 12_501));
        assertEquals("QUESTION_EXPIRED", e.code());
    }

    @Test void newRequestAt12600IsRejected_andTheTimeoutIsStampedAtTheArrivalDeadline() {
        Q x = open();
        String r = right(x);
        GameException e = assertThrows(GameException.class, () -> answer(x, r, 12_600, 12_600));
        assertEquals("QUESTION_EXPIRED", e.code());
        assertNull(env.scalar("SELECT received_at FROM pe_question WHERE question_id = ?", x.q().questionId()), "a late request records nothing");
        env.clock.set(x.at(12_500 + 1_000));                              // the timeout settles after the grace, stamped at the deadline
        env.m.deadlines.sweep(100);
        assertEquals("TIMED_OUT", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", x.q().questionId()));
        assertEquals(x.at(12_500), ((java.sql.Timestamp) env.scalar("SELECT settled_at FROM pe_question WHERE question_id = ?", x.q().questionId())).toInstant());
    }

    @Test void admittedAt12000_retryAndStatusAfter12500_returnTheSameResult_noNewAnswer() {
        Q x = open();
        String r = right(x);
        AnswerView first = answer(x, r, 12_000, 12_100);
        // a retry (same intent, any key, even a different choice) arriving after the deadline gets the settled answer
        AnswerView retry = answer(x, TestEnv.flip(r), 13_000, 13_000);
        assertEquals(first.result(), retry.result());
        assertEquals(first.choice(), retry.choice());
        assertEquals(1, retry.score(), "one answer, one point");
        RunView status = env.m.queries.run(x.c(), x.runId());
        assertEquals(x.q().questionId(), status.lastResult().questionId());
        assertEquals("correct", status.lastResult().result());
        assertEquals(1, env.count("SELECT count(*) FROM pe_question WHERE run_id = ? AND state = 'CORRECT'", x.runId()));
    }

    @Test void admittedRequestThatDiedBeforeItsRunLockIsSettledAsTheAnswerNotATimeout() {
        Q x = open();
        String r = right(x);
        // the receipt transaction committed at arrival 12,400 ms; the request then died before its main transaction
        assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.ctx.runs().recordAnswerReceipt(x.q().questionId(), x.c().userId(),
                x.c().deviceId(), Choice.valueOf(r), x.at(12_400))));
        env.clock.set(x.at(14_000));
        env.m.deadlines.sweep(100);
        assertEquals("CORRECT", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", x.q().questionId()), "admitted, so never a timeout");
        AnswerView retry = answer(x, r, 15_000, 15_000);                  // the client's status retry gets that result
        assertEquals("correct", retry.result());
        assertEquals(0, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'ON_TIME_ANSWER_TIMED_OUT' AND details->>'question_id' = ?", x.q().questionId()));
    }

    @Test void anAdmittedAnswerAndTheExpiryPathRacingSettleItOnce() throws Exception {
        for (int i = 0; i < 6; i++) {
            Q x = open();
            String r = right(x);
            // admitted exactly at the inclusive boundary; its retry and the sweep then race to settle it
            assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.ctx.runs().recordAnswerReceipt(x.q().questionId(), x.c().userId(),
                    x.c().deviceId(), Choice.valueOf(r), x.at(12_500))));
            env.clock.set(x.at(13_500));                                  // the sweep is due now
            AtomicReference<String> answered = new AtomicReference<>();
            env.parallel(2, k -> {
                if (k == 0) {
                    try { answered.set(env.m.runs.answer(x.c(), x.q().questionId(), Choice.valueOf(r), x.at(13_500)).result()); }
                    catch (GameException e) { answered.set(e.code()); }
                } else env.m.deadlines.sweep(100);
            });
            assertEquals("correct", answered.get(), "the admitted answer stands, whoever takes the lock first");
            assertEquals("CORRECT", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", x.q().questionId()));
            assertEquals(1, env.count("SELECT score FROM pe_run WHERE run_id = ?", x.runId()), "one transition");
        }
    }

    // ------------------------------------------------------------------------------------------------ extra life

    record O(Caller c, String user, String runId, OfferView offer) {
        Instant at(long ms) { return offer.openedAt().plusMillis(ms); }
    }

    O offer() {
        String u = user();
        Caller c = env.caller(u);
        String run = env.m.runs.start(c).run().runId();
        RunView q = env.m.runs.reveal(c, run);
        env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(run))), q.question().t0().plusMillis(1_000));
        env.clock.advance(1_700);
        ActivationView a = env.m.runs.activateOffer(c, run);
        assertNotNull(a.offer());
        assertEquals(a.offer().openedAt().plusMillis(10_000), a.offer().deadline(), "visible window");
        assertEquals(a.offer().openedAt().plusMillis(15_000), a.offer().acceptDeadline(), "arrival deadline");
        return new O(c, u, run, a.offer());
    }

    OperationView buy(O o, long arrivalMs, long nowMs) {
        env.clock.set(o.at(nowMs));
        return env.m.runs.continueOffer(o.c(), o.offer().offerId(), o.at(arrivalMs));
    }

    @Test void purchaseArrivalBoundaryIsInclusiveAt15000() {
        for (long ms : new long[]{10_001, 14_999, 15_000}) {
            O o = offer();
            OperationView op = buy(o, ms, ms);
            assertEquals("succeeded", op.status(), "arrival at " + ms + " ms is admitted");
            assertEquals("READY", op.run().state());
        }
        O o = offer();
        GameException e = assertThrows(GameException.class, () -> buy(o, 15_001, 15_001));
        assertEquals("OFFER_EXPIRED", e.code());
        assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE offer_id = ?", o.offer().offerId()), "nothing charged");
        assertEquals(120, env.walletBalance(o.user()));
    }

    @Test void clickAt9800_admittedAt14000_completesAt18000_appliesTheConfirmedOutcome() {
        O o = offer();
        env.script(o.user(), "[{\"op\":\"charge\",\"outcome\":\"late_success\",\"late_ms\":4000}]");
        OperationView op = buy(o, 14_000, 14_000);
        assertEquals("pending", op.status(), "admitted and processing");
        assertFalse(op.definitive());
        assertEquals(o.offer().offerId(), op.offerId());
        env.clock.set(o.at(16_000));
        env.m.deadlines.sweep(100);                                        // past both deadlines: the admitted purchase is not failed
        assertEquals("PAYMENT_PENDING", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", o.runId()));
        // a retry or a second key after the deadline retrieves the admitted operation instead of creating one
        OperationView again = buy(o, 16_500, 16_500);
        assertEquals(op.operationId(), again.operationId());
        env.clock.set(o.at(18_000));
        due(op.operationId());
        env.m.payments.process(op.operationId());
        OperationView done = env.m.runs.operation(o.c(), op.operationId());
        assertEquals("succeeded", done.status());
        assertTrue(done.definitive());
        assertEquals("READY", done.run().state(), "the extra life is granted after its confirmation, not at the visible zero");
        assertEquals(1, done.run().continuesUsed());
        assertEquals(115, env.walletBalance(o.user()), "one debit");
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE offer_id = ? AND type = 'CHARGE'", o.offer().offerId()));
    }

    @Test void receiptRecordedOnTimeIsAdmittedByTheExpiryPath_notExpired() {
        O o = offer();
        assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.ctx.runs().recordPurchaseReceipt(o.offer().offerId(), o.c().userId(),
                o.c().deviceId(), o.at(14_900))));                       // the request then died before its run lock
        env.clock.set(o.at(15_000 + 999));
        env.m.deadlines.sweep(100);
        assertEquals("OFFER_OPEN", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", o.runId()), "not yet: within the grace");
        env.clock.set(o.at(15_000 + 1_000));
        env.m.deadlines.sweep(100);
        assertEquals("PAYMENT_PENDING", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", o.runId()), "admitted, not expired");
        env.drainLedger();
        assertEquals("READY", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", o.runId()));
        assertEquals(115, env.walletBalance(o.user()));
        OperationView retry = buy(o, 17_000, 17_000);                     // the client's retry finds that operation
        assertEquals("succeeded", retry.status());
    }

    @Test void anOnTimePurchaseWhoseReceiptLostTheRaceIsRefusedWithAnAlert_notSilently() {
        O o = offer();
        env.clock.set(o.at(16_000));
        env.m.deadlines.sweep(100);                                        // expired: no receipt was recorded in time
        GameException e = assertThrows(GameException.class, () -> buy(o, 14_900, 16_200));
        assertEquals("OFFER_EXPIRED", e.code());
        assertEquals(1, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'ON_TIME_PURCHASE_EXPIRED' AND details->>'offer_id' = ?", o.offer().offerId()));
        assertEquals(120, env.walletBalance(o.user()));
    }

    @Test void unansweredOfferExpiresAtTheArrivalDeadline_notAtTheVisibleZero() {
        O o = offer();
        env.clock.set(o.at(10_000));
        env.m.deadlines.sweep(100);
        assertEquals("OFFER_OPEN", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", o.runId()), "a purchase can still arrive");
        env.clock.set(o.at(16_000));
        env.m.deadlines.sweep(100);
        assertEquals("expired", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", o.runId()));
        assertEquals(o.at(15_000), ((java.sql.Timestamp) env.scalar("SELECT finished_at FROM pe_run WHERE run_id = ?", o.runId())).toInstant());
    }

    @Test void definitiveFailureAfterTheArrivalDeadlineEndsTheRunWithoutALife() {
        O o = offer();
        env.script(o.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_not_applied\"},{\"op\":\"charge\",\"outcome\":\"failed\"}]");
        OperationView op = buy(o, 14_000, 14_000);
        assertEquals("pending", op.status());
        env.clock.set(o.at(16_000));
        due(op.operationId());
        env.m.payments.process(op.operationId());                          // not found at the provider → sent again (still waiting) → rejected
        OperationView end = env.m.runs.operation(o.c(), op.operationId());
        assertEquals("failed", end.status());
        assertEquals("FINISHED", end.run().state());
        assertEquals("continue_failed", end.run().finishReason());
        assertEquals(0, end.run().continuesUsed());
        assertEquals(120, env.walletBalance(o.user()), "no debit");
    }

    @Test void skipAndLeaveAreRefusedWhileAnAdmittedPurchaseIsProcessed() {
        O o = offer();
        env.script(o.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_not_applied\"}]");
        buy(o, 12_000, 12_000);
        assertEquals("OPERATION_PENDING", assertThrows(GameException.class, () -> env.m.runs.skip(o.c(), o.offer().offerId())).code());
        assertEquals("OPERATION_PENDING", assertThrows(GameException.class, () -> env.m.runs.leave(o.c(), o.runId())).code());
    }

    // ------------------------------------------------------------------------------------------------ deployment

    @Test void startupMigratesAnOlderActiveVersionToTheTimingContract_onceAndAudited_runsKeepTheirPinnedTiming() {
        int before = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        // an existing database: the active version still has the R-45 values (as rewritten by migration V4)
        env.sql("UPDATE pe_config_version SET payload = payload || '{\"input_ms\":10500,\"accept_ms\":11000,\"offer_accept_ms\":10000,\"payment_resolution_ms\":0}'::jsonb WHERE version = ?", before);
        env.rebuild();                                                    // a process start reads it fresh
        Caller pinned = env.caller(user());
        String oldRun = env.m.runs.start(pinned).run().runId();
        assertEquals(10_500, env.m.queries.run(pinned, oldRun).runConfig().inputMs());
        env.m.admin.ensureLaunchConfig();                                  // what PriceExpertServer does on start-up
        env.m.admin.ensureLaunchConfig();                                  // a second instance / restart changes nothing more
        int after = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        assertNotEquals(before, after);
        assertEquals(1, env.count("SELECT count(*) FROM pe_config_version WHERE version > ? AND created_by = 'system:migration'", before), "published once");
        assertEquals(1, env.count("SELECT count(*) FROM pe_audit_event WHERE actor = 'system:migration' AND action = 'config.publish' AND target = ?", "config:" + after));
        RunConfigView now = env.m.queries.bootstrap(env.caller(user())).config();
        assertEquals(10_000, now.inputMs());
        assertEquals(12_500, now.acceptMs());
        assertEquals(15_000, now.offerAcceptMs());
        assertEquals(10_500, env.m.queries.run(pinned, oldRun).runConfig().inputMs(), "a run already started keeps its pinned version");
        env.m.runs.leave(pinned, oldRun);
    }
}
