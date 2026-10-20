package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.LaunchConfig;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Admission, device replacement, reveal, answer timing, races, recovery, leaving and version pinning. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class RunLifecycleIT {
    static TestEnv env;

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }

    static String user() { return "u" + UUID.randomUUID().toString().substring(0, 8); }

    RunView reveal(Caller c, String runId) { return env.m.runs.reveal(c, runId); }

    AnswerView answer(Caller c, RunView afterReveal, boolean correct, long msAfterT0) {
        String right = env.rightChoice(afterReveal.runId());
        String ch = correct ? right : TestEnv.flip(right);
        Instant received = afterReveal.question().t0().plusMillis(msAfterT0);
        return env.m.runs.answer(c, afterReveal.question().questionId(), Choice.valueOf(ch), received);
    }

    static GameException expect(String code, Runnable r) {
        GameException e = assertThrows(GameException.class, r::run);
        assertEquals(code, e.code(), e.getMessage());
        return e;
    }

    // ------------------------------------------------------------------------------------------------ T01, T02

    @Test void t01_startConsumesOneAllowance_andTheSameDeviceCannotStartTwice() {
        String u = user();
        Caller c = env.caller(u);
        StartView s = env.m.runs.start(c);
        assertEquals("READY", s.run().state());
        assertEquals(2, s.quota().playsLeft());
        assertEquals(1, env.count("SELECT plays_used FROM pe_daily_participation WHERE user_id = ?", c.userId()));
        // a retried start from the same device admits nothing new (HTTP replays the stored response for the same key;
        // without the key the domain refuses a second open run and deducts nothing)
        GameException e = expect("ACTIVE_RUN_EXISTS", () -> env.m.runs.start(c));
        assertEquals(s.run().runId(), e.details().get("run_id"));
        assertEquals(1, env.count("SELECT plays_used FROM pe_daily_participation WHERE user_id = ?", c.userId()));
    }

    @Test void t02_quotaExhaustionAdmitsNoRun() {
        String u = user();
        Caller c = env.caller(u);
        for (int i = 0; i < 3; i++) {
            StartView s = env.m.runs.start(c);
            env.m.runs.leave(c, s.run().runId());
        }
        GameException e = expect("DAILY_QUOTA_EXHAUSTED", () -> env.m.runs.start(c));
        assertNotNull(e.details().get("resets_at"));
        BootstrapView b = env.m.queries.bootstrap(c);
        assertEquals(0, b.quota().playsLeft());
        assertEquals(3, env.count("SELECT count(*) FROM pe_run WHERE user_id = ?", c.userId()));
    }

    // ------------------------------------------------------------------------------------------------ T03, T04, T15

    @Test void t03_otherDeviceReplacesTheRun_settledOnce_openQuestionAddsNothing() {
        String u = user();
        Caller a = env.caller(u, 0), b = env.caller(u, 1);
        String run1 = env.m.runs.start(a).run().runId();
        // reach checkpoint 3 on device A, then leave a question open
        for (int i = 0; i < 3; i++) { RunView q = reveal(a, run1); answer(a, q, true, 2000); }
        reveal(a, run1);
        // device B cannot resume A's run
        expect("ACTIVE_RUN_OTHER_DEVICE", () -> env.m.runs.reveal(b, run1));
        BootstrapView boot = env.m.queries.bootstrap(b);
        assertFalse(boot.activeRun().onThisDevice());
        // B starts: A's run ends as replaced, its checkpoint reward is created once, the open question is unanswered
        StartView s = env.m.runs.start(b);
        assertEquals(run1, s.replacedRun().runId());
        assertEquals(3, s.replacedRun().score());
        assertEquals(5, s.replacedRun().checkpointReward());
        assertEquals("replaced", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", run1));
        assertEquals("UNANSWERED", env.scalar("SELECT state FROM pe_question WHERE run_id = ? ORDER BY seq DESC LIMIT 1", run1));
        assertEquals(3, env.count("SELECT score FROM pe_run WHERE run_id = ?", run1));
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD' AND amount = 5", run1));
        // A discovers the replacement on its next request
        expect("RUN_REPLACED", () -> env.m.runs.reveal(a, run1));
        // the products list of the replaced run never shows the unanswered question's price
        ProductsView pv = env.m.queries.products(a, run1);
        assertNull(pv.items().get(pv.items().size() - 1).gamePrice());
        assertEquals("unanswered", pv.items().get(pv.items().size() - 1).result());
        assertNull(pv.lossProductId());
    }

    @Test void t04_otherDeviceWithoutPlaysLeavesTheOldRunUnchanged() {
        String u = user();
        Caller a = env.caller(u, 0), b = env.caller(u, 1);
        for (int i = 0; i < 2; i++) env.m.runs.leave(a, env.m.runs.start(a).run().runId());
        String run3 = env.m.runs.start(a).run().runId();                       // third and last play
        expect("DAILY_QUOTA_EXHAUSTED", () -> env.m.runs.start(b));
        assertEquals("READY", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", run3));
    }

    @Test void t04_concurrentStartsCannotExceedQuotaOrOpenTwoRuns() throws Exception {
        String u = user();
        AtomicInteger ok = new AtomicInteger();
        env.parallel(12, i -> {
            try { env.m.runs.start(env.caller(u, i)); ok.incrementAndGet(); } catch (GameException e) { /* expected for most */ }
        });
        assertTrue(ok.get() >= 1 && ok.get() <= 3, "admitted " + ok.get());
        assertEquals(ok.get(), env.count("SELECT plays_used FROM pe_daily_participation WHERE user_id = ?", "u_" + u));
        assertEquals(1, env.count("SELECT count(*) FROM pe_run WHERE user_id = ? AND state <> 'FINISHED'", "u_" + u));
    }

    @Test void t15_leaveDuringOpenQuestionKeepsCheckpointAndAddsNoCorrectness() {
        Caller c = null;
        String run = null;
        for (int attempt = 0; attempt < 8 && run == null; attempt++) {    // the real catalog is sparse at some bands: retry a path
            c = env.caller(user());
            String r = env.m.runs.start(c).run().runId();
            boolean open = true;
            for (int i = 0; i < 4 && open; i++) {                        // 3 correct answers, then a fourth question left open
                RunView q = reveal(c, r);
                open = q.question() != null;
                if (open && i < 3) answer(c, q, true, 1500);
            }
            if (open) run = r;
        }
        assertNotNull(run, "no path with a fourth question in 8 attempts");
        RunView after = env.m.runs.leave(c, run);
        assertEquals("FINISHED", after.state());
        assertEquals("left", after.finishReason());
        assertEquals(3, after.score());
        assertEquals("UNANSWERED", env.scalar("SELECT state FROM pe_question WHERE run_id = ? ORDER BY seq DESC LIMIT 1", run));
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run));
        assertNull(env.m.queries.products(c, run).lossProductId(), "leaving is not a loss target");
    }

    // ------------------------------------------------------------------------------------------------ T05, T06, T07

    @Test void t05_referenceVisible_comparisonSecretUntilSettled() throws Exception {
        String u = user();
        Caller c = env.caller(u);
        StartView s = env.m.runs.start(c);
        assertTrue(s.run().reference().price() > 0, "reference price is intentionally public");
        assertNull(s.run().question());
        RunView q = reveal(c, s.run().runId());
        long secret = env.scalar("SELECT comparison_price FROM pe_question WHERE run_id = ? AND state = 'OPEN'", q.runId());
        long dkp = env.scalar("SELECT comparison_dkp FROM pe_question WHERE run_id = ? AND state = 'OPEN'", q.runId());
        for (Object view : List.of(q, env.m.queries.run(c, q.runId()), env.m.queries.bootstrap(c))) {
            String json = Json.write(view);
            assertFalse(json.contains("\"" + secret + "\"") || json.matches(".*[^0-9]" + secret + "[^0-9].*"), "hidden price leaked: " + json);
            assertFalse(json.contains(String.valueOf(dkp)), "comparison product id leaked");
        }
        expect("RUN_NOT_FINISHED", () -> env.m.queries.products(c, q.runId()));
        expect("RUN_NOT_FINISHED", () -> env.m.queries.result(c, q.runId()));
    }

    @Test void t06_revealRetryReturnsTheSameQuestionAndDeadlines() {
        Caller c = env.caller(user());
        String run = env.m.runs.start(c).run().runId();
        RunView a = reveal(c, run);
        env.clock.advance(3000);
        RunView b = reveal(c, run);
        assertEquals(a.question().questionId(), b.question().questionId());
        assertEquals(a.question().t0(), b.question().t0());
        assertEquals(a.question().inputDeadline(), b.question().inputDeadline());
        assertEquals(a.question().product().displayTitle(), b.question().product().displayTitle());
        assertEquals(1, env.count("SELECT count(*) FROM pe_question WHERE run_id = ?", run));
    }

    @Test void t07_deadlinesFollowR45() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        assertEquals(q.question().t0().plusMillis(10_500), q.question().inputDeadline());
        assertEquals(q.question().t0().plusMillis(11_000), q.question().acceptDeadline());
        assertEquals(10_500, q.runConfig().inputMs());
        assertEquals(11_000, q.runConfig().acceptMs());
    }

    // ------------------------------------------------------------------------------------------------ T08, T09, T10, T11

    @Test void t08_inclusiveServerCutoffAt11000ms() {
        for (long at : new long[]{10_999, 11_000}) {
            Caller c = env.caller(user());
            RunView q = reveal(c, env.m.runs.start(c).run().runId());
            AnswerView a = answer(c, q, true, at);
            assertEquals("correct", a.result(), "received at t0 + " + at + " ms must be accepted");
        }
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        GameException e = expect("QUESTION_EXPIRED", () -> answer(c, q, true, 11_001));
        // the timeout becomes final no earlier than acceptDeadline + grace and is stamped at acceptDeadline
        env.clock.set(q.question().acceptDeadline().plusMillis(999));
        env.m.deadlines.sweep(100);
        assertEquals("QUESTION_OPEN", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", q.runId()));
        env.clock.set(q.question().acceptDeadline().plusMillis(1_000));
        env.m.deadlines.sweep(100);
        assertEquals("TIMED_OUT", env.scalar("SELECT state FROM pe_question WHERE run_id = ?", q.runId()));
        Instant settled = ((java.sql.Timestamp) env.scalar("SELECT settled_at FROM pe_question WHERE run_id = ?", q.runId())).toInstant();
        assertEquals(q.question().acceptDeadline(), settled);
        assertNotNull(e.details().get("state"));
    }

    @Test void t09_slowImageOrLateResponseNeverExtendsTheDeadline() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        env.clock.advance(8_000);                                     // image arrives late, response read late
        RunView again = env.m.queries.run(c, q.runId());
        assertEquals(q.question().inputDeadline(), again.question().inputDeadline(), "reload keeps the original deadline");
        assertEquals(q.question().acceptDeadline(), again.question().acceptDeadline());
    }

    @Test void t10_duplicateAndConflictingAnswersYieldOneResult() throws Exception {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        String right = env.rightChoice(q.runId());
        List<AnswerView> results = Collections.synchronizedList(new ArrayList<>());
        env.parallel(8, i -> results.add(env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(i % 2 == 0 ? right : TestEnv.flip(right)),
                q.question().t0().plusMillis(3000 + i))));
        assertEquals(8, results.size());
        Set<String> distinct = new HashSet<>();
        results.forEach(r -> distinct.add(r.result() + "/" + r.choice()));
        assertEquals(1, distinct.size(), "one immutable outcome: " + distinct);
        int score = (int) env.count("SELECT score FROM pe_run WHERE run_id = ?", q.runId());
        assertTrue(score <= 1);
        assertEquals(1, env.count("SELECT count(*) FROM pe_question WHERE run_id = ? AND state IN ('CORRECT','WRONG')", q.runId()));
    }

    @Test void t11_answerBeatsTheTimeoutWorker_andDeadlinesSurviveRestart() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        // an answer received in time but processed during the grace window still wins over the sweeper
        env.clock.set(q.question().acceptDeadline().plusMillis(400));
        env.m.deadlines.sweep(100);
        AnswerView a = answer(c, q, true, 10_990);
        assertEquals("correct", a.result());
        // restart with a question open: the new process finalises the old deadline, stamped at the deadline
        RunView q2 = reveal(c, q.runId());
        env.rebuild();
        env.clock.set(q2.question().acceptDeadline().plusMillis(5_000));
        assertTrue(env.m.deadlines.sweep(100) >= 1);
        Instant settled = ((java.sql.Timestamp) env.scalar("SELECT settled_at FROM pe_question WHERE question_id = ?", q2.question().questionId())).toInstant();
        assertEquals(q2.question().acceptDeadline(), settled);
    }

    @Test void t11b_onTimeAnswerProcessedAfterTheGraceStillWins() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        // review of 716d889: received at t0 + 10,990 ms, processed at t0 + 12,001 ms (accept deadline + grace + 1 ms)
        env.clock.set(q.question().t0().plusMillis(12_001));
        AnswerView a = answer(c, q, true, 10_990);
        assertEquals("correct", a.result(), "the recorded arrival time decides, not the processing time");
        assertEquals(1, a.score());
        assertEquals(0, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'ON_TIME_ANSWER_TIMED_OUT'"));
    }

    @Test void t11c_recordedReceiptBeatsTheSweep_evenIfItsRequestDied() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        String qid = q.question().questionId(), right = env.rightChoice(q.runId());
        Instant received = q.question().t0().plusMillis(10_800);
        // another device of the account cannot record an answer
        expect("ACTIVE_RUN_OTHER_DEVICE", () -> env.m.runs.answer(env.caller(c.userId().substring(2), 1), qid, Choice.valueOf(right), received));
        assertNull(env.scalar("SELECT received_at FROM pe_question WHERE question_id = ?", qid));
        // the request recorded its receipt, then stalled before taking the run lock (or its process died)
        assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.ctx.runs().recordAnswerReceipt(qid, c.userId(), c.deviceId(), Choice.valueOf(TestEnv.flip(right)), received)));
        env.clock.set(q.question().acceptDeadline().plusMillis(5_000));
        env.m.deadlines.sweep(100);
        assertEquals("WRONG", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", qid), "settled as the answer, not as a timeout");
        assertEquals(received, ((java.sql.Timestamp) env.scalar("SELECT miss_at FROM pe_run WHERE run_id = ?", q.runId())).toInstant(), "miss stamped at the receipt");
        // the first receipt wins: a later retry with the other choice gets the settled outcome
        assertEquals("wrong", env.m.runs.answer(c, qid, Choice.valueOf(right), received).result());
    }

    @Test void t11d_leaveBeforeAnUnsettledReceiptKeepsR59() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        String qid = q.question().questionId();
        assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.ctx.runs().recordAnswerReceipt(qid, c.userId(), c.deviceId(),
                Choice.valueOf(env.rightChoice(q.runId())), q.question().t0().plusMillis(2_000))));
        RunView after = env.m.runs.leave(c, q.runId());                 // the leave reaches the run before the answer request
        assertEquals("left", after.finishReason());
        assertEquals(0, after.score(), "an unsettled answer gives no correctness on leave (R-59)");
        assertEquals("UNANSWERED", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", qid));
        assertNull(env.scalar("SELECT choice FROM pe_question WHERE question_id = ?", qid));
    }

    // ------------------------------------------------------------------------------------------------ T13, T14, T16

    @Test void t13_acceptedAnswerWithLostResponseIsRecoveredNotLost() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        answer(c, q, true, 2000);                                     // the response is "lost": the client only reads the run
        RunView view = env.m.queries.run(c, q.runId());
        assertEquals("READY", view.state());
        assertEquals(1, view.score());
        assertEquals(q.question().questionId(), view.lastResult().questionId());
        assertEquals("correct", view.lastResult().result());
        // a retried answer with any key returns the stored result and adds nothing
        AnswerView again = env.m.runs.answer(c, q.question().questionId(), Choice.higher, q.question().t0().plusMillis(2500));
        assertEquals("correct", again.result());
        assertEquals(1, env.count("SELECT score FROM pe_run WHERE run_id = ?", q.runId()));
    }

    @Test void t14_reloadIsNotLeaving_inactivityEndsBetweenQuestionsOnly() {
        Caller c = env.caller(user());
        String run = env.m.runs.start(c).run().runId();
        env.clock.advance(170_000);
        assertEquals("READY", env.m.queries.run(c, run).state());     // reload / background: nothing changes
        RunView q = reveal(c, run);                                    // a released question resets inactivity... until READY again
        answer(c, q, true, 1000);
        env.clock.advance(179_000);
        assertEquals("READY", env.m.queries.run(c, run).state());
        env.clock.advance(2_000);
        RunView ended = env.m.queries.run(c, run);
        assertEquals("FINISHED", ended.state());
        assertEquals("inactive", ended.finishReason());
    }

    @Test void t16_feedbackCarriesOnlyTheAuthorisedPrice() {
        Caller c = env.caller(user());
        RunView q = reveal(c, env.m.runs.start(c).run().runId());
        long secret = env.scalar("SELECT comparison_price FROM pe_question WHERE run_id = ? AND state = 'OPEN'", q.runId());
        AnswerView a = answer(c, q, false, 2000);
        assertEquals("wrong", a.result());
        assertEquals(secret, a.revealedPrice());
        assertEquals(secret > q.reference().price() ? "higher" : "lower", a.direction());
        assertFalse(a.equalPrices());
    }

    // ------------------------------------------------------------------------------------------------ T21

    @Test void t21_publishedConfigAndCatalogDoNotChangeAnActiveRun() throws Exception {
        Caller c = env.caller(user());
        StartView s = env.m.runs.start(c);
        int cfgV = s.run().runConfig().configVersion(), catV = s.run().runConfig().catalogVersion();
        // publish a config with a different checkpoint reward and a new catalog version
        var admin = new com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Admin("ed", com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Role.EDITOR);
        GameConfig base = LaunchConfig.value();
        var cps = new ArrayList<>(base.checkpoints());
        cps.set(0, new GameConfig.Checkpoint("cp_3", 3, "قیمت‌یاب تازه‌کار", "badge_3", 6, true));
        GameConfig changed = new GameConfig(base.playsPerDay(), base.inputMs(), base.acceptMs(), base.offerMs(), base.inactivityMs(), base.offerActivationMs(),
                base.timeoutGraceMs(), base.checkpointAutoContinueMs(), cps, base.continueRule(), base.streakReward(), base.bands(), null, null, base.features());
        var draft = env.m.admin.saveDraft(admin, changed, null);
        assertTrue(draft.errors().isEmpty());
        env.m.admin.publish(admin, draft.version(), cfgV, draft.revision(), "test");
        try {
            env.m.catalog.importAndPublish(com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.ProductDatabaseReader.read(TestEnv.CATALOG, null), "test-2", "test");
            for (int i = 0; i < 3; i++) answer(c, reveal(c, s.run().runId()), true, 1000);
            RunView view = env.m.queries.run(c, s.run().runId());
            assertEquals(cfgV, view.runConfig().configVersion());
            assertEquals(catV, view.runConfig().catalogVersion());
            assertEquals(5, view.highestCheckpoint().rewardAmount(), "pinned checkpoint value, not the new 6");
            // bands follow the score: changing checkpoints did not move them
            RunView q4 = reveal(c, s.run().runId());
            assertEquals(1L, ((Number) env.scalar("SELECT band_index FROM pe_question WHERE question_id = ?", q4.question().questionId())).longValue());
            // a new run gets the new versions
            env.m.runs.leave(c, s.run().runId());
            StartView s2 = env.m.runs.start(c);
            assertEquals(draft.version(), s2.run().runConfig().configVersion());
            assertEquals(catV + 1, s2.run().runConfig().catalogVersion());
            env.m.runs.leave(c, s2.run().runId());
        } finally {
            var back = env.m.admin.saveDraft(admin, LaunchConfig.value(), null);
            env.m.admin.publish(admin, back.version(), draft.version(), back.revision(), "restore");
        }
    }
}
