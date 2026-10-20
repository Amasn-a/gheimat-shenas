package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Admin;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Role;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Paid continue (R-39, R-43, R-46, R-50, R-63, F-02): T22–T29, T51 against the in-project fake DigiCoin provider. */
class PaymentIT {
    static TestEnv env;
    static final Admin EDITOR = new Admin("editor", Role.EDITOR);

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }
    @AfterEach void gameOn() { env.m.admin.setAvailability(EDITOR, true, null, "test reset"); }

    record Miss(Caller c, String user, String runId, String questionId, long refPrice) {}

    static String user() { return "p" + UUID.randomUUID().toString().substring(0, 8); }

    /** Debits minus refunds actually applied at the (fake) provider for this user. Rewards are credits and not counted. */
    static long netCharge(String user) {
        return env.count("SELECT coalesce(sum(CASE kind WHEN 'CHARGE' THEN amount WHEN 'REFUND' THEN -amount ELSE 0 END), 0) FROM pe_fake_wallet_tx WHERE user_id = ? AND applied", "u_" + user);
    }

    /** start → (correct × n) → wrong answer; returns the miss without activating the offer. */
    Miss miss(int correctFirst) {
        String u = user();
        Caller c = env.caller(u);
        String run = env.m.runs.start(c).run().runId();
        for (int i = 0; i < correctFirst; i++) {
            RunView q = env.m.runs.reveal(c, run);
            env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(1000));
        }
        RunView q = env.m.runs.reveal(c, run);
        AnswerView a = env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(run))), q.question().t0().plusMillis(1500));
        assertEquals("wrong", a.result());
        assertTrue(a.continuePossible());
        assertEquals("AWAITING_OFFER", a.run().state());
        return new Miss(c, u, run, q.question().questionId(), q.reference().price());
    }

    ActivationView activateAfterFeedback(Miss m) {
        env.clock.advance(1_700);                                     // the feedback hold happens on the client first
        return env.m.runs.activateOffer(m.c(), m.runId());
    }

    // ------------------------------------------------------------------------------------------------ T51 (F-02)

    @Test void t51_offerWindowStartsAtActivation_andRetriesReturnTheSameOffer() {
        Miss m = miss(0);
        assertEquals(0, env.count("SELECT count(*) FROM pe_offer WHERE run_id = ?", m.runId()), "no offer exists before activation");
        Instant activatedAt = env.clock.instant().plusMillis(1_700);
        ActivationView a = activateAfterFeedback(m);
        assertEquals(activatedAt, a.offer().openedAt());
        assertEquals(activatedAt.plusMillis(10_000), a.offer().deadline());
        env.clock.advance(3_000);
        ActivationView again = env.m.runs.activateOffer(m.c(), m.runId());
        assertEquals(a.offer().offerId(), again.offer().offerId());
        assertEquals(a.offer().deadline(), again.offer().deadline(), "a retry never renews the window");
        assertEquals(1, env.count("SELECT count(*) FROM pe_offer WHERE run_id = ?", m.runId()));
    }

    @Test void t51_gameOffOrLowBalanceRejectActivation() {
        Miss m = miss(0);
        env.m.admin.setAvailability(EDITOR, false, "به‌روزرسانی", "test");
        ActivationView a = activateAfterFeedback(m);
        assertNull(a.offer());
        assertEquals("FINISHED", a.run().state());
        env.m.admin.setAvailability(EDITOR, true, null, "test");
        Miss m2 = miss(0);
        env.balance(m2.user(), 9);
        ActivationView b = activateAfterFeedback(m2);
        assertNull(b.offer());
        assertEquals("no_funds", b.noOfferReason());
    }

    @Test void t51_unactivatedMissEndsAfterTheActivationWait() {
        Miss m = miss(0);
        env.clock.advance(182_000);                                      // miss was received at t0 + 1.5 s; activation wait 180 s
        env.m.deadlines.sweep(100);
        assertEquals("wrong", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", m.runId()));
        assertEquals(0, env.count("SELECT count(*) FROM pe_offer WHERE run_id = ?", m.runId()));
    }

    // ------------------------------------------------------------------------------------------------ T22, T24

    @Test void t22_confirmedBeforeExpiryGivesOneContinueAndOneDebit() throws Exception {
        Miss m = miss(3);
        ActivationView a = activateAfterFeedback(m);
        List<OperationView> ops = Collections.synchronizedList(new ArrayList<>());
        env.parallel(5, i -> ops.add(env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant())));   // double taps / retries
        assertEquals(1, ops.stream().map(OperationView::operationId).distinct().count(), "one operation per offer");
        RunView after = env.m.queries.run(m.c(), m.runId());
        assertEquals("READY", after.state());
        assertEquals(3, after.score());
        assertEquals("cp_3", after.highestCheckpoint().checkpointId());
        assertEquals(m.refPrice(), after.reference().price(), "same reference after a continue");
        assertEquals(1, after.continuesUsed());
        assertEquals(110, env.walletBalance(m.user()), "exactly one debit of 10");
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHARGE'", m.runId()));
        // the failed comparison is not drawn again
        long failed = env.scalar("SELECT comparison_dkp FROM pe_question WHERE question_id = ?", m.questionId());
        RunView q = env.m.runs.reveal(m.c(), m.runId());
        assertNotEquals(failed, (long) env.scalar("SELECT comparison_dkp FROM pe_question WHERE question_id = ?", q.question().questionId()));
    }

    @Test void t24_continueLimitUsedSkipsTheOffer_andNewKeysCannotBypassIt() {
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        RunView q = env.m.runs.reveal(m.c(), m.runId());
        AnswerView w = env.m.runs.answer(m.c(), q.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(m.runId()))), q.question().t0().plusMillis(900));
        assertFalse(w.continuePossible());
        assertEquals("limit_used", w.noOfferReason());
        assertTrue(w.runFinished());
        OperationView replay = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());   // old offer: same operation, no new charge
        assertEquals("succeeded", replay.status());
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHARGE'", m.runId()));
        assertTrue(env.m.queries.result(m.c(), m.runId()).continueLimitUsed());
    }

    // ------------------------------------------------------------------------------------------------ T23

    @Test void t23_noOrUnknownBalanceGivesNoOffer_andSameRunCoinsCannotFundIt() {
        Miss low = miss(0);
        env.balance(low.user(), 4);
        ActivationView a = activateAfterFeedback(low);
        assertNull(a.offer());
        assertEquals("no_funds", a.noOfferReason());
        assertEquals("FINISHED", a.run().state());

        Miss unknown = miss(0);
        env.script(unknown.user(), "[{\"op\":\"balance\",\"outcome\":\"unreadable\"}]");
        ActivationView b = activateAfterFeedback(unknown);
        assertNull(b.offer(), "an unreadable balance counts as too low (R-63)");
        assertEquals("no_funds", b.noOfferReason());

        // 5 coins of checkpoint 3 earned in this run are paid only at its end, so 5 + 5 cannot fund a 10-coin continue
        String u = user();
        env.balance(u, 5);
        Caller c = env.caller(u);
        String run = env.m.runs.start(c).run().runId();
        for (int i = 0; i < 3; i++) { RunView q = env.m.runs.reveal(c, run); env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(800)); }
        RunView q = env.m.runs.reveal(c, run);
        env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(run))), q.question().t0().plusMillis(800));
        env.clock.advance(1_700);
        assertNull(env.m.runs.activateOffer(c, run).offer());
        assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHARGE'", run));
    }

    // ------------------------------------------------------------------------------------------------ T25, T26, T28

    @Test void t25_t26_unconfirmedAtExpiryEndsTheRun_lateConfirmationIsRefundedOnce() {
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"late_success\",\"late_ms\":20000}]");
        OperationView op = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        assertEquals("pending", op.status());
        assertEquals("PAYMENT_PENDING", op.run().state());
        assertEquals(a.offer().deadline(), op.run().offer().deadline(), "the ring keeps the same deadline during payment");
        env.clock.set(a.offer().deadline().minusMillis(1));
        env.m.deadlines.sweep(100);
        assertEquals("PAYMENT_PENDING", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", m.runId()));
        env.clock.set(a.offer().deadline());                            // at the deadline the offer is over
        env.m.deadlines.sweep(100);
        assertEquals("continue_failed", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", m.runId()));
        assertEquals("ENDED_UNCONFIRMED", env.scalar("SELECT state FROM pe_offer WHERE offer_id = ?", a.offer().offerId()));
        assertEquals(1, env.count("SELECT plays_used FROM pe_daily_participation WHERE user_id = ?", m.c().userId()), "the play stays used (R-51)");
        OperationView mid = env.m.runs.operation(m.c(), op.operationId());
        assertEquals("expired_unconfirmed", mid.status());
        assertEquals("unknown", mid.chargeStatus());
        // the coin service confirms later: refund exactly once, run never resurrected
        env.clock.advance(25_000);
        env.drainLedger();
        env.drainLedger();
        env.m.payments.processDue(100);
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND'", op.operationId()));
        assertEquals(0, netCharge(m.user()), "no net charge for an ungranted continue");
        OperationView end = env.m.runs.operation(m.c(), op.operationId());
        assertEquals("refunded", end.status());
        assertEquals("confirmed", end.chargeStatus());
        assertEquals("FINISHED", end.run().state());
    }

    @Test void t28_confirmationAtTheDeadlineIsLate_oneMillisecondBeforeIsInTime() {
        for (long early : new long[]{0, 1}) {
            Miss m = miss(0);
            ActivationView a = activateAfterFeedback(m);
            long lateMs = java.time.Duration.between(env.clock.instant(), a.offer().deadline()).toMillis() - early;
            env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"late_success\",\"late_ms\":" + lateMs + "}]");
            OperationView op = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
            env.clock.set(a.offer().deadline().minusMillis(early));     // the confirmation becomes visible exactly now
            // due on the test clock (the database's now() is wall-clock time and says nothing about the test clock)
            env.sql("UPDATE pe_coin_operation SET next_attempt_at = ? WHERE operation_id = ?",
                    java.time.OffsetDateTime.ofInstant(env.clock.instant().minusSeconds(3600), java.time.ZoneOffset.UTC), op.operationId());
            env.m.payments.process(op.operationId());                   // before any sweep
            String runState = env.scalar("SELECT state FROM pe_run WHERE run_id = ?", m.runId());
            if (early == 0) {
                assertEquals("FINISHED", runState, "confirmation recorded at the deadline grants nothing");
                assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND'", op.operationId()));
            } else {
                assertEquals("READY", runState, "confirmation 1 ms before the deadline grants the continue");
                assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND'", op.operationId()));
            }
        }
    }

    /** Makes an operation due on the test clock (retry backoff and lease elapsed). */
    static void due(String operationId) {
        env.sql("UPDATE pe_coin_operation SET next_attempt_at = ? WHERE operation_id = ?",
                java.time.OffsetDateTime.ofInstant(env.clock.instant().minusSeconds(1), java.time.ZoneOffset.UTC), operationId);
    }

    @Test void retryAfterTheOfferEndedNeverSendsAFreshDebit_unresolvedGoesToReview() {
        // review of 716d889: attempt 1 timed out without reaching the provider; the retry runs after the offer deadline
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_not_applied\"}]");
        OperationView op = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        String id = op.operationId();
        assertEquals("UNKNOWN", env.scalar("SELECT status FROM pe_coin_operation WHERE operation_id = ?", id));

        env.clock.set(a.offer().deadline().plusSeconds(5));
        due(id);
        env.m.payments.process(id);                                    // provider: not found; offer over → no debit
        assertEquals(0, env.count("SELECT count(*) FROM pe_fake_wallet_tx WHERE provider_ref = ?", id), "no fresh debit after the offer ended");
        assertEquals("UNKNOWN", env.scalar("SELECT status FROM pe_coin_operation WHERE operation_id = ?", id), "still confirming nothing landed");
        assertEquals("FINISHED", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", m.runId()));

        assertEquals("expired_unconfirmed", env.m.runs.operation(m.c(), id).status(), "the player is told it is unconfirmed, not that it failed");
        env.drainLedgerFully();                                          // two more minutes of status queries, never a send
        assertEquals(0, env.count("SELECT count(*) FROM pe_fake_wallet_tx WHERE provider_ref = ?", id));
        assertEquals("UNKNOWN", env.scalar("SELECT status FROM pe_coin_operation WHERE operation_id = ?", id));

        // the provider never shows it: at the end of the retry window it goes to manual review with an alert, not to FAILED
        env.clock.set(((java.sql.Timestamp) env.scalar("SELECT give_up_at FROM pe_coin_operation WHERE operation_id = ?", id)).toInstant().plusSeconds(1));
        due(id);
        env.m.payments.process(id);
        assertEquals("NEEDS_REVIEW", env.scalar("SELECT status FROM pe_coin_operation WHERE operation_id = ?", id));
        assertEquals(1, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'COIN_OPERATION_NEEDS_REVIEW' AND details->>'operation_id' = ?", id));
        assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND'", id), "nothing to refund");
        assertEquals(0, netCharge(m.user()));
    }

    @Test void anEarlierAttemptLandingAfterTheOfferEndedIsRefundedNotRepeated() {
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        long lateMs = java.time.Duration.between(env.clock.instant(), a.offer().deadline()).toMillis() + 10_000;
        env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"late_success\",\"late_ms\":" + lateMs + "}]");   // debited, visible 10 s after the deadline
        String id = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant()).operationId();
        env.clock.set(a.offer().deadline().plusSeconds(12));
        due(id);
        env.m.payments.process(id);
        env.drainLedgerFully();
        assertEquals(1, env.count("SELECT count(*) FROM pe_fake_wallet_tx WHERE user_id = ? AND kind = 'CHARGE'", "u_" + m.user()), "one debit, never a second");
        assertEquals(0, netCharge(m.user()), "the late debit is refunded");
    }

    @Test void knownRejectionKeepsTheSameOfferActionable() {
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"failed\"}]");
        OperationView op = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        assertEquals("failed", op.status());
        assertEquals("OFFER_OPEN", op.run().state());
        assertEquals(a.offer().deadline(), op.run().offer().deadline());
        OperationView retry = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        assertEquals("succeeded", retry.status());
        assertEquals(110, env.walletBalance(m.user()));
    }

    // ------------------------------------------------------------------------------------------------ T27

    @Test void t27_confirmedDebitThatCannotBuyAContinueIsRefunded_gameOff_block_noProducts() {
        // game off while the payment is in flight
        Miss g = miss(0);
        ActivationView ga = activateAfterFeedback(g);
        env.script(g.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_applied\"}]");
        OperationView gop = env.m.runs.continueOffer(g.c(), ga.offer().offerId(), env.clock.instant());
        assertEquals("pending", gop.status());
        env.m.admin.setAvailability(EDITOR, false, "به‌روزرسانی", "test");
        assertEquals("game_off", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", g.runId()));
        env.clock.advance(2_000);
        env.drainLedger();
        assertEquals(0, netCharge(g.user()), "charged then refunded once");
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND' AND status = 'SUCCEEDED'", gop.operationId()));
        env.m.admin.setAvailability(EDITOR, true, null, "test");

        // admin block while the payment is in flight
        Miss b = miss(0);
        ActivationView ba = activateAfterFeedback(b);
        env.script(b.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_applied\"}]");
        OperationView bop = env.m.runs.continueOffer(b.c(), ba.offer().offerId(), env.clock.instant());
        env.m.admin.block(EDITOR, b.c().userId(), "test block", "review-1");
        assertEquals("blocked", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", b.runId()));
        env.clock.advance(2_000);
        env.drainLedger();
        assertEquals(0, netCharge(b.user()));
        assertEquals(120, env.walletBalance(b.user()), "a blocked run pays no streak or checkpoint reward");
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND'", bop.operationId()));
        env.m.admin.unblock(EDITOR, b.c().userId(), "test");

        // confirmed before the deadline but no product can follow (catalog exhausted for this run)
        Miss n = miss(0);
        ActivationView na = activateAfterFeedback(n);
        env.script(n.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_applied\"}]");
        OperationView nop = env.m.runs.continueOffer(n.c(), na.offer().offerId(), env.clock.instant());
        int cat = ((Number) env.scalar("SELECT catalog_version FROM pe_run WHERE run_id = ?", n.runId())).intValue();
        env.sql("UPDATE pe_catalog_product SET eligible = FALSE, exclude_reason = 'test: exhausted' WHERE catalog_version = ? AND dkp NOT IN (SELECT reference_dkp FROM pe_run WHERE run_id = ?)", cat, n.runId());
        try {
            env.clock.advance(1_000);
            env.drainLedger();
            assertEquals("continue_failed", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", n.runId()));
            assertEquals(0, netCharge(n.user()));
        } finally {
            env.sql("UPDATE pe_catalog_product SET eligible = TRUE, exclude_reason = NULL WHERE catalog_version = ? AND exclude_reason = 'test: exhausted'", cat);
        }
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND'", nop.operationId()));
    }

    // ------------------------------------------------------------------------------------------------ T29 crash recovery

    @Test void t29_crashAfterDebitBeforeLocalResult_recoversWithOneDebit() {
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_applied\"}]");
        OperationView op = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        assertEquals("pending", op.status());
        env.rebuild();                                                   // the process dies; nothing in memory survives
        env.clock.advance(1_500);
        env.drainLedger();
        assertEquals("READY", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", m.runId()), "confirmed before the deadline: continue granted");
        assertEquals(110, env.walletBalance(m.user()));
        assertEquals(1, env.count("SELECT count(*) FROM pe_fake_wallet_tx WHERE provider_ref = ?", op.operationId()), "one debit at the provider");
    }

    @Test void t29_crashAfterOutboxWriteBeforeDelivery_andAfterRefundBeforeAck() {
        // a reward written with the finish but never delivered before the crash
        String u = user();
        Caller c = env.caller(u);
        String run = env.m.runs.start(c).run().runId();
        for (int i = 0; i < 3; i++) { RunView q = env.m.runs.reveal(c, run); env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(700)); }
        env.m.runs.leave(c, run);
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD' AND status = 'PENDING'", run));
        env.rebuild();
        env.drainLedger();
        env.drainLedger();
        assertEquals(126, env.walletBalance(u), "checkpoint 5 + streak 1, each once");

        // a refund the provider applied but whose answer was lost
        Miss m = miss(0);
        ActivationView a = activateAfterFeedback(m);
        env.script(m.user(), "[{\"op\":\"charge\",\"outcome\":\"unknown_applied\"},{\"op\":\"refund\",\"outcome\":\"unknown_applied\"}]");
        OperationView op = env.m.runs.continueOffer(m.c(), a.offer().offerId(), env.clock.instant());
        env.clock.set(a.offer().deadline());
        env.m.deadlines.sweep(100);                                      // unconfirmed at the deadline → run ends
        env.drainLedger();                                               // charge confirmed late → refund sent, answer lost
        env.rebuild();
        env.clock.advance(5_000);
        env.drainLedger();
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE related_operation_id = ? AND type = 'REFUND' AND status = 'SUCCEEDED'", op.operationId()));
        assertEquals(1, env.count("SELECT count(*) FROM pe_fake_wallet_tx WHERE kind = 'REFUND' AND user_id = ?", m.c().userId()), "one refund at the provider");
        assertEquals(0, netCharge(m.user()));
    }
}
