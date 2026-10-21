package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redesign 2026-10-21: checkpoints 5/10/15/20 pay 0/1/3/5 each, once, at the valid crossing; the record reward (1) once per
 * run when the record frozen at run start is above 20 and the run's score reaches it; a run pinned to an older version
 * still gets the highest checkpoint at run end.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class RedesignRewardsIT {
    static TestEnv env;

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }

    static String user() { return "d" + UUID.randomUUID().toString().substring(0, 8); }

    static final class OutOfProducts extends RuntimeException {}

    /** One correct answer; throws OutOfProducts when the run ended with no_products (sparse real catalog at high bands). */
    static AnswerView correct(Caller c, String run) {
        RunView q = env.m.runs.reveal(c, run);
        if (q.question() == null) throw new OutOfProducts();
        return env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(900));
    }

    static List<String> rewardRows(String run) { return RewardsIT.rewardRowsOf(env, run); }

    /**
     * A fresh account whose all-time record is {@code record}; its run is played to {@code score}, {@code atScore} checks it
     * there, then one more correct answer is given (returned as {@code next}). Another account when the path runs out.
     */
    record Played(Caller c, String run, AnswerView next) {}
    static Played withRecord(int record, int score, java.util.function.BiConsumer<Caller, String> atScore) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Caller c = env.caller(user());
            env.m.runs.leave(c, env.m.runs.start(c).run().runId());       // creates the player row
            env.sql("UPDATE pe_player SET record = ? WHERE user_id = ?", record, c.userId());
            String run = env.m.runs.start(c).run().runId();
            try {
                for (int i = 0; i < score; i++) correct(c, run);
                atScore.accept(c, run);
                return new Played(c, run, correct(c, run));
            } catch (OutOfProducts e) { /* another random path */ }
        }
        throw new AssertionError("could not reach score " + (score + 1) + " in 8 attempts");
    }

    @Test void a_eachCheckpointPaysItsOwnRewardOnceAtCrossing_replaysAddNothing() {
        Map<Integer, String> expected = Map.of(5, "cp_5", 10, "cp_10", 15, "cp_15", 20, "cp_20");
        Map<Integer, Integer> reward = Map.of(5, 0, 10, 1, 15, 3, 20, 5);
        for (int attempt = 0; attempt < 8; attempt++) {
            Caller c = env.caller(user());
            String run = env.m.runs.start(c).run().runId();
            List<String> rows = new ArrayList<>();
            try {
                for (int s = 1; s <= 20; s++) {
                    RunView q = env.m.runs.reveal(c, run);
                    if (q.question() == null) throw new OutOfProducts();
                    Choice right = Choice.valueOf(env.rightChoice(run));
                    AnswerView a = env.m.runs.answer(c, q.question().questionId(), right, q.question().t0().plusMillis(900));
                    AnswerView replay = env.m.runs.answer(c, q.question().questionId(), right, q.question().t0().plusMillis(950));
                    assertEquals(s, a.score());
                    assertEquals(s, replay.score(), "a replay settles nothing again");
                    if (expected.containsKey(s)) {
                        assertEquals(expected.get(s), a.checkpointReached().checkpointId());
                        assertEquals(expected.get(s), replay.checkpointReached().checkpointId(), "a replay reports the same checkpoint");
                        if (reward.get(s) > 0) {
                            rows.add(expected.get(s) + ":" + reward.get(s));
                            assertEquals(reward.get(s), a.checkpointReward().amount(), "registered at the crossing");
                            assertEquals(a.checkpointReward(), replay.checkpointReward());
                        } else {
                            assertNull(a.checkpointReward(), "cp_5 pays 0: no ledger row");
                        }
                    } else {
                        assertNull(a.checkpointReached());
                    }
                    assertNull(a.recordReward());
                    assertEquals(rows.stream().sorted().toList(), rewardRows(run), "after answer " + s);
                }
            } catch (OutOfProducts e) { continue; }
            env.m.runs.leave(c, run);
            env.m.runs.leave(c, run);
            assertEquals(List.of("cp_10:1", "cp_15:3", "cp_20:5"), rewardRows(run), "finish adds no run-end credit");
            env.drainLedger();
            ResultView r = env.m.queries.result(c, run);
            assertEquals("cp_20", r.checkpoint().checkpointId());
            assertEquals(9, r.checkpointReward().amount(), "0 + 1 + 3 + 5");
            assertEquals("succeeded", r.checkpointReward().status());
            return;
        }
        fail("could not reach 20 in 8 attempts");
    }

    @Test void b_recordOf20_isNotAboveTheFloor_noRecordRewardAt20Or21() {
        Played p = withRecord(20, 20, (c, run) -> assertEquals(List.of("cp_10:1", "cp_15:3", "cp_20:5"), rewardRows(run)));
        assertEquals(21, p.next().score());                              // a new record, still no record reward
        assertNull(p.next().recordReward());
        env.m.runs.leave(p.c(), p.run());
        assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'RECORD_REWARD'", p.run()));
    }

    @Test void c_recordOf21_paysOnceAt21_notAt20() {
        Played p = withRecord(21, 20, (c, run) ->
                assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'RECORD_REWARD'", run), "20 has not reached 21"));
        assertEquals(21, p.next().score());
        assertEquals(1, p.next().recordReward().amount());
        assertEquals(List.of("cp_10:1", "cp_15:3", "cp_20:5", "record:21:1"), rewardRows(p.run()));
    }

    @Test void d_recordOf27_paysOnceAt27_nothingMoreAt28_orOnReads() {
        Played p = withRecord(27, 27, (c, run) -> assertEquals(List.of("cp_10:1", "cp_15:3", "cp_20:5", "record:27:1"), rewardRows(run)));
        assertEquals(28, p.next().score());
        assertNull(p.next().recordReward());
        env.m.runs.leave(p.c(), p.run());
        env.drainLedger();
        ResultView r1 = env.m.queries.result(p.c(), p.run());
        ResultView r2 = env.m.queries.result(p.c(), p.run());
        env.m.queries.run(p.c(), p.run());
        assertEquals(List.of("cp_10:1", "cp_15:3", "cp_20:5", "record:27:1"), rewardRows(p.run()), "nothing more at 28, and reads create nothing");
        assertEquals(10, r1.checkpointReward().amount(), "9 from checkpoints + 1 record reward");
        assertEquals(r1.checkpointReward(), r2.checkpointReward());
        assertTrue(r1.isNewRecord());
    }

    @Test void e_runPinnedToALegacyVersion_isPaidTheHighestCheckpointAtRunEnd() {
        int active = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        // a version published before the redesign (the validator refuses pay_on_crossing = false, so written directly)
        env.sql("UPDATE pe_config_version SET payload = payload || '{\"pay_on_crossing\":false,\"record_reward\":0}'::jsonb WHERE version = ?", active);
        env.rebuild();
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                Caller c = env.caller(user());
                String run = env.m.runs.start(c).run().runId();
                try { for (int i = 0; i < 15; i++) correct(c, run); } catch (OutOfProducts e) { continue; }
                assertEquals(List.of(), rewardRows(run), "nothing is paid at a crossing under the old rule");
                env.m.runs.leave(c, run);
                env.m.runs.leave(c, run);
                assertEquals(List.of("run-end:3"), rewardRows(run), "highest checkpoint only (3), not 1 + 3, once");
                return;
            }
            fail("could not reach 15 in 8 attempts");
        } finally {
            env.sql("UPDATE pe_config_version SET payload = payload || '{\"pay_on_crossing\":true,\"record_reward\":1}'::jsonb WHERE version = ?", active);
            env.rebuild();
        }
    }
}
