package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Admin;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Role;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Campaign;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationType;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.LaunchConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Run;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Settlement: highest checkpoint only, block/game-off, record, streak, cap (R-47, F-03), campaigns (R-29/R-37/R-48), midnight. */
class RewardsIT {
    static TestEnv env;
    static final Admin EDITOR = new Admin("editor", Role.EDITOR);

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }
    @AfterEach void reset() { env.m.admin.setAvailability(EDITOR, true, null, "reset"); }

    static String user() { return "r" + UUID.randomUUID().toString().substring(0, 8); }

    String playTo(Caller c, int correct) {
        String run = env.m.runs.start(c).run().runId();
        if (!correct(c, run, correct)) throw new TestAbortedForCatalog(run);
        return run;
    }
    /** false when the run ended early with no_products (sparse real catalog at high bands). */
    boolean correct(Caller c, String run, int n) {
        for (int i = 0; i < n; i++) {
            RunView q = env.m.runs.reveal(c, run);
            if (q.question() == null) return false;
            env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(900));
        }
        return true;
    }
    static final class TestAbortedForCatalog extends RuntimeException { TestAbortedForCatalog(String run) { super("run " + run + " ran out of products"); } }

    /** A fresh account whose run reached exactly {@code score}; retries with another account if the catalog ran out. */
    record Reached(Caller c, String run) {}
    Reached reach(int score) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Caller c = env.caller(user());
            try { return new Reached(c, playTo(c, score)); } catch (TestAbortedForCatalog e) { /* try another random path */ }
        }
        throw new AssertionError("could not reach score " + score + " in 8 attempts");
    }

    // ------------------------------------------------------------------------------------------------ T30, T31

    @Test void t30_highestCheckpointOnly_finalisedOnce() {
        Reached x = reach(7);
        Caller c = x.c();
        String run = x.run();
        env.m.runs.leave(c, run);
        env.m.runs.leave(c, run);                                        // repeated finalisation
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run));
        assertEquals(20, env.count("SELECT amount FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run), "20, not 5 + 10 + 20");
        env.drainLedger();
        ResultView r = env.m.queries.result(c, run);
        assertEquals("cp_7", r.checkpoint().checkpointId());
        assertEquals(20, r.checkpointReward().amount());
        assertEquals("succeeded", r.checkpointReward().status());
    }

    @Test void t31_blockCancelsThatRunsRewardsRecordAndStreakDay_butNotOtherRuns() {
        String u = user();
        Caller c = env.caller(u);
        String first = playTo(c, 3);
        env.m.runs.leave(c, first);                                      // earns cp 3 + streak, record 3
        env.drainLedger();
        String second = playTo(c, 5);                                    // would be a new record and cp 5
        env.m.admin.block(EDITOR, c.userId(), "abuse review", "review-42");
        assertEquals("blocked", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", second));
        assertEquals(0, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ?", second), "no reward of any kind from the blocked run");
        assertEquals(3, env.count("SELECT record FROM pe_player WHERE user_id = ?", c.userId()), "a blocked run sets no record");
        assertEquals(2, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND status = 'SUCCEEDED'", first), "earlier rewards stay");
        GameException e = assertThrows(GameException.class, () -> env.m.runs.start(c));
        assertEquals("PLAYER_BLOCKED", e.code());
        assertEquals("blocked", env.m.queries.bootstrap(c).access().status());
        env.m.admin.unblock(EDITOR, c.userId(), "reviewed");
        assertEquals("ok", env.m.queries.bootstrap(c).access().status());
    }

    @Test void t31_flaggedAccountsArePaidNormally() {
        // flags are information only (R-60): a review with outcome "suspicious" changes nothing about payouts
        String u = user();
        Caller c = env.caller(u);
        env.m.admin.review(EDITOR, c.userId(), null, "suspicious", "fast answers");
        String run = playTo(c, 3);
        env.m.runs.leave(c, run);
        env.drainLedger();
        assertEquals("succeeded", env.m.queries.result(c, run).checkpointReward().status());
    }

    // ------------------------------------------------------------------------------------------------ T32

    @Test void t32_gameOffBlocksStarts_endsRunsAtTheBreak_noNewOffer() {
        Caller ready = env.caller(user());
        String readyRun = playTo(ready, 3);
        Caller asking = env.caller(user());
        String askingRun = playTo(asking, 0);
        RunView open = env.m.runs.reveal(asking, askingRun);
        env.m.admin.setAvailability(EDITOR, false, "به‌روزرسانی", "test");
        assertEquals("game_off", env.scalar("SELECT finish_reason FROM pe_run WHERE run_id = ?", readyRun));
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", readyRun), "highest checkpoint reward kept");
        assertEquals("QUESTION_OPEN", env.scalar("SELECT state FROM pe_run WHERE run_id = ?", askingRun), "a released question keeps its deadline");
        AnswerView w = env.m.runs.answer(asking, open.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(askingRun))), open.question().t0().plusMillis(800));
        assertFalse(w.continuePossible());
        assertEquals("game_off", w.noOfferReason());
        GameException e = assertThrows(GameException.class, () -> env.m.runs.start(env.caller(user())));
        assertEquals("GAME_UNAVAILABLE", e.code());
        assertEquals("game_off", env.m.queries.bootstrap(env.caller(user())).access().status());
    }

    // ------------------------------------------------------------------------------------------------ T33

    @Test void t33_recordFromQuestionOne_continuedScoreCounts_tieIsNotNew() {
        String u = user();
        Caller c = env.caller(u);
        String r1 = playTo(c, 2);
        env.m.runs.leave(c, r1);
        StartView s = env.m.runs.start(c);
        assertEquals(2, s.run().record(), "record shown from the first question");
        correct(c, s.run().runId(), 1);
        RunView q = env.m.runs.reveal(c, s.run().runId());
        env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(s.run().runId()))), q.question().t0().plusMillis(900));
        env.clock.advance(1_700);
        ActivationView a = env.m.runs.activateOffer(c, s.run().runId());
        env.m.runs.continueOffer(c, a.offer().offerId(), env.clock.instant());
        correct(c, s.run().runId(), 2);                                   // 3 after the continue
        env.m.runs.leave(c, s.run().runId());
        ResultView r = env.m.queries.result(c, s.run().runId());
        assertTrue(r.isNewRecord());
        assertEquals(3, r.record());
        assertEquals(2, r.previousRecord());
        String r3 = playTo(c, 3);
        env.m.runs.leave(c, r3);
        assertFalse(env.m.queries.result(c, r3).isNewRecord(), "a tie is not a new record");
    }

    // ------------------------------------------------------------------------------------------------ T34, T40

    @Test void t34_t40_streakAndQuotaBelongToTheStartDay_acrossMidnight() {
        Instant saved = env.clock.instant();
        try {
            ZoneId teh = ZoneId.of("Asia/Tehran");
            LocalDate d = LocalDate.of(2026, 10, 21);
            env.clock.set(ZonedDateTime.of(d, LocalTime.of(23, 59, 30), teh).toInstant());
            String u = user();
            Caller c = env.caller(u);
            String run = playTo(c, 3);                                    // started on D
            env.clock.set(ZonedDateTime.of(d.plusDays(1), LocalTime.of(0, 2), teh).toInstant());
            env.m.runs.leave(c, run);                                     // finished on D + 1
            assertEquals(d, ((java.sql.Date) env.scalar("SELECT start_business_date FROM pe_run WHERE run_id = ?", run)).toLocalDate());
            assertEquals(1, env.count("SELECT plays_used FROM pe_daily_participation WHERE user_id = ? AND business_date = ?", c.userId(), d));
            assertEquals(0, env.count("SELECT count(*) FROM pe_daily_participation WHERE user_id = ? AND business_date = ? AND plays_used > 0", c.userId(), d.plusDays(1)));
            assertEquals(d, ((java.sql.Date) env.scalar("SELECT cap_day FROM pe_coin_operation WHERE run_id = ? AND type = 'STREAK_REWARD'", run)).toLocalDate());
            assertEquals(d, ((java.sql.Date) env.scalar("SELECT cap_day FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run)).toLocalDate(), "F-03: run reward on the start-day cap");
            // a second run on D + 1 earns D + 1's streak day once; the streak is 2 days
            String run2 = playTo(c, 0);
            env.m.runs.leave(c, run2);
            String run3 = playTo(c, 0);
            env.m.runs.leave(c, run3);
            assertEquals(2, env.count("SELECT count(*) FROM pe_coin_operation WHERE user_id = ? AND type = 'STREAK_REWARD'", c.userId()), "one streak reward per start day");
            assertEquals(2, env.m.queries.bootstrap(c).streak().days());
            // a day without a finished run breaks it
            env.clock.set(ZonedDateTime.of(d.plusDays(3), LocalTime.of(10, 0), teh).toInstant());
            BootstrapView b = env.m.queries.bootstrap(c);
            assertEquals(0, b.streak().days());
            assertTrue(b.streak().lostNotice());
        } finally { env.clock.set(saved); }
    }

    // ------------------------------------------------------------------------------------------------ T35, T36, T53

    @Test void t35_capHoldsTheExcess_highWaterSurvivesConfigChanges_concurrentCreditsCannotOvershoot() throws Exception {
        String u = user();
        Caller c = env.caller(u);
        LocalDate today = com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar.dateOf(env.clock.instant());
        env.m.runs.leave(c, env.m.runs.start(c).run().runId());          // admission pins today's cap high-water
        assertEquals(121, env.count("SELECT cap_high_water FROM pe_daily_cap WHERE business_date = ?", today), "3 × 40 + 1 at launch");
        env.sql("DELETE FROM pe_daily_credit WHERE user_id = ?", c.userId());
        env.sql("INSERT INTO pe_daily_credit (user_id, cap_day, credited) VALUES (?, ?, 118)", c.userId(), today);
        String run = playTo(c, 3);
        env.m.runs.leave(c, run);
        // today's streak reward was already earned; checkpoint 5 → 3 granted (up to 121) + 2 held, with an alert
        assertEquals(3, env.count("SELECT amount FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run));
        assertEquals(2, env.count("SELECT held_amount FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run));
        assertEquals(1, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'DAILY_CAP_HELD' AND details->>'user_id' = ?", c.userId()));
        assertEquals("delayed", env.m.queries.result(c, run).checkpointReward().status(), "held is shown as delayed, never as paid");

        // raising the cap mid-day raises the high-water; lowering it again does not lower today's cap
        int active = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        GameConfig b = LaunchConfig.value();
        GameConfig four = new GameConfig(4, b.inputMs(), b.acceptMs(), b.offerMs(), b.inactivityMs(), b.offerActivationMs(), b.timeoutGraceMs(),
                b.checkpointAutoContinueMs(), b.checkpoints(), b.continueRule(), b.streakReward(), b.bands(), null, null, b.features());
        var d1 = env.m.admin.saveDraft(EDITOR, four, null);
        env.m.admin.publish(EDITOR, d1.version(), active, d1.revision(), "test");
        var d2 = env.m.admin.saveDraft(EDITOR, LaunchConfig.value(), null);
        env.m.admin.publish(EDITOR, d2.version(), d1.version(), d2.revision(), "restore");
        assertEquals(161, env.count("SELECT cap_high_water FROM pe_daily_cap WHERE business_date = ?", today));

        // 20 concurrent credits of 10 for one account and day: never above the cap
        String v = user();
        Run r = new Run();
        r.runId = "synthetic"; r.userId = "u_" + v; r.startBusinessDate = today; r.configVersion = d2.version();
        AtomicInteger n = new AtomicInteger();
        env.parallel(20, i -> env.m.ctx.tx().run(() -> {
            Run x = new Run(); x.runId = "syn" + i; x.userId = r.userId; x.startBusinessDate = today; x.configVersion = r.configVersion;
            env.m.settlement.createCredit(x, OperationType.CHECKPOINT_REWARD, 10, env.clock.instant());
            n.incrementAndGet();
        }));
        assertEquals(20, n.get());
        assertEquals(161, env.count("SELECT sum(amount) FROM pe_coin_operation WHERE user_id = ?", r.userId));
        assertEquals(39, env.count("SELECT sum(held_amount) FROM pe_coin_operation WHERE user_id = ?", r.userId));
        assertEquals(161, env.count("SELECT credited FROM pe_daily_credit WHERE user_id = ? AND cap_day = ?", r.userId, today));
    }

    @Test void t36_refundsOutsideTheCap_payoutThresholdIsMonitoringOnly() {
        String u = user();
        Caller c = env.caller(u);
        LocalDate today = com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar.dateOf(env.clock.instant());
        // a refunded continue does not touch the account's daily credit
        String run = env.m.runs.start(c).run().runId();
        RunView q = env.m.runs.reveal(c, run);
        env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(TestEnv.flip(env.rightChoice(run))), q.question().t0().plusMillis(900));
        env.clock.advance(1_700);
        ActivationView a = env.m.runs.activateOffer(c, run);
        env.script(u, "[{\"op\":\"charge\",\"outcome\":\"late_success\",\"late_ms\":15000}]");
        env.m.runs.continueOffer(c, a.offer().offerId(), env.clock.instant());
        env.clock.set(a.offer().deadline());
        env.m.deadlines.sweep(100);
        env.clock.advance(16_000);
        env.drainLedger();
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND type = 'REFUND' AND status = 'SUCCEEDED'", run));
        assertEquals(1, env.count("SELECT credited FROM pe_daily_credit WHERE user_id = ? AND cap_day = ?", c.userId(), today), "only the streak reward counts");

        // payout alert: set a threshold of 1 and pay a reward → an alert, no player-facing change
        int active = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        GameConfig b = LaunchConfig.value();
        GameConfig withAlert = new GameConfig(b.playsPerDay(), b.inputMs(), b.acceptMs(), b.offerMs(), b.inactivityMs(), b.offerActivationMs(), b.timeoutGraceMs(),
                b.checkpointAutoContinueMs(), b.checkpoints(), b.continueRule(), b.streakReward(), b.bands(), 1L, null, b.features());
        var d = env.m.admin.saveDraft(EDITOR, withAlert, null);
        env.m.admin.publish(EDITOR, d.version(), active, d.revision(), "test");
        try {
            Caller c2 = env.caller(user());
            String r2 = playTo(c2, 3);
            env.m.runs.leave(c2, r2);
            env.drainLedger();
            assertTrue(env.count("SELECT count(*) FROM pe_alert WHERE kind = 'DAILY_PAYOUT_THRESHOLD'") >= 1);
            ResultView res = env.m.queries.result(c2, r2);
            assertEquals("succeeded", res.checkpointReward().status());
            assertEquals("ok", env.m.queries.bootstrap(c2).access().status());
        } finally {
            var back = env.m.admin.saveDraft(EDITOR, LaunchConfig.value(), null);
            env.m.admin.publish(EDITOR, back.version(), d.version(), back.revision(), "restore");
        }
    }

    @Test void t53_delayedCreditUsesItsStartDayCapOnce_andKeepsTheRealPaymentTime() {
        Instant saved = env.clock.instant();
        try {
            ZoneId teh = ZoneId.of("Asia/Tehran");
            LocalDate d = LocalDate.of(2026, 10, 24);
            env.clock.set(ZonedDateTime.of(d, LocalTime.of(23, 50), teh).toInstant());
            String u = user();
            Caller c = env.caller(u);
            env.script(u, "[{\"op\":\"credit\",\"outcome\":\"unknown_not_applied\"},{\"op\":\"credit\",\"outcome\":\"unknown_not_applied\"},{\"op\":\"credit\",\"outcome\":\"unknown_not_applied\"}]");
            String run = playTo(c, 3);
            env.m.runs.leave(c, run);
            env.drainLedger();                                           // provider does not answer before midnight
            env.clock.set(ZonedDateTime.of(d.plusDays(1), LocalTime.of(0, 30), teh).toInstant());
            env.drainLedgerFully();
            assertEquals(2, env.count("SELECT count(*) FROM pe_coin_operation WHERE run_id = ? AND status = 'SUCCEEDED'", run));
            assertEquals(d, ((java.sql.Date) env.scalar("SELECT cap_day FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run)).toLocalDate());
            Instant confirmed = ((java.sql.Timestamp) env.scalar("SELECT confirmed_at FROM pe_coin_operation WHERE run_id = ? AND type = 'CHECKPOINT_REWARD'", run)).toInstant();
            assertEquals(d.plusDays(1), confirmed.atZone(teh).toLocalDate(), "actual credit time is kept separately");
            assertEquals(6, env.count("SELECT credited FROM pe_daily_credit WHERE user_id = ? AND cap_day = ?", c.userId(), d));
            assertEquals(0, env.count("SELECT count(*) FROM pe_daily_credit WHERE user_id = ? AND cap_day = ?", c.userId(), d.plusDays(1)), "no second day's cap consumed");
            assertEquals(1, env.count("SELECT count(*) FROM pe_fake_wallet_tx WHERE user_id = ? AND kind = 'CREDIT' AND amount = 5", c.userId()), "one entitlement, one credit");
        } finally { env.clock.set(saved); }
    }

    // ------------------------------------------------------------------------------------------------ T37, T38

    Campaign campaign(int cap, long budget) {
        Instant now = env.clock.instant();
        return env.m.admin.createCampaign(EDITOR, new Campaign(null, "کمپین آزمایشی", now.minusSeconds(60), now.plusSeconds(86_400 * 3), List.of("cp_3"),
                50_000, 10, "قابل استفاده برای خرید بالای ۵۰۰ هزار تومان.", cap, budget, 0, 0, "SCHEDULED"));
    }

    @Test void t37_t38_campaignCodesAddToCoins_onePerAccount_capAndBudgetHoldUnderRaces() throws Exception {
        Campaign camp = campaign(3, 150_000);
        try {
            String u = user();
            Caller c = env.caller(u);
            assertNotNull(env.m.queries.bootstrap(c).campaign(), "G01.READY.CAMPAIGN");
            String r1 = playTo(c, 3);
            env.m.runs.leave(c, r1);
            env.drainLedger();
            env.m.campaigns.processDue(10);
            ResultView res = env.m.queries.result(c, r1);
            assertEquals("issued", res.campaignCode().status());
            assertTrue(res.campaignCode().code().startsWith("DEV-"), "development fake codes are labelled");
            assertEquals("succeeded", res.checkpointReward().status(), "code is in addition to DigiCoin");
            String stored = env.scalar("SELECT code_ciphertext FROM pe_campaign_entitlement WHERE run_id = ?", r1);
            assertFalse(stored.contains(res.campaignCode().code()), "codes are encrypted at rest");
            // a later qualifying run in the same campaign: coins only, no second card
            String r2 = playTo(c, 3);
            env.m.runs.leave(c, r2);
            assertNull(env.m.queries.result(c, r2).campaignCode());
            assertEquals(1, env.count("SELECT count(*) FROM pe_campaign_entitlement WHERE user_id = ?", c.userId()));

            // 8 accounts reach the code checkpoint at the same time; only 2 codes are left (cap 3, one issued)
            List<Caller> callers = new ArrayList<>();
            List<String> runs = new ArrayList<>();
            for (int i = 0; i < 8; i++) { Caller x = env.caller(user()); callers.add(x); runs.add(playTo(x, 3)); }
            env.parallel(8, i -> env.m.runs.leave(callers.get(i), runs.get(i)));
            assertEquals(3, env.count("SELECT codes_issued FROM pe_campaign WHERE campaign_id = ?", camp.campaignId()));
            assertEquals(150_000, env.count("SELECT budget_used_toman FROM pe_campaign WHERE campaign_id = ?", camp.campaignId()));
            long soldOut = runs.stream().filter(r -> "sold_out".equals(env.scalar("SELECT campaign_outcome FROM pe_run WHERE run_id = ?", r))).count();
            assertEquals(6, soldOut);
            String soldRun = runs.stream().filter(r -> "sold_out".equals(env.scalar("SELECT campaign_outcome FROM pe_run WHERE run_id = ?", r))).findFirst().orElseThrow();
            Caller soldCaller = callers.get(runs.indexOf(soldRun));
            env.drainLedger();
            ResultView sold = env.m.queries.result(soldCaller, soldRun);
            assertEquals("sold_out", sold.campaignCode().status());
            assertEquals(5, sold.checkpointReward().amount(), "sold out keeps the DigiCoin reward");
        } finally { env.m.admin.endCampaign(EDITOR, camp.campaignId(), "test end"); }
    }

    // ------------------------------------------------------------------------------------------------ A01 switches

    @Test void featureSwitchesReachThePlayerAndAreEnforced() {
        // review of 716d889: share / products / haptics switches never reached the player; streak-off still advertised a reward
        int active = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        GameConfig b = LaunchConfig.value();
        GameConfig off = new GameConfig(b.playsPerDay(), b.inputMs(), b.acceptMs(), b.offerMs(), b.inactivityMs(), b.offerActivationMs(), b.timeoutGraceMs(),
                b.checkpointAutoContinueMs(), b.checkpoints(), b.continueRule(), b.streakReward(), b.bands(), b.payoutAlertThreshold(),
                b.priceChangeThresholdBp(), new GameConfig.Features(false, false, false, false));
        var d = env.m.admin.saveDraft(EDITOR, off, null);
        env.m.admin.publish(EDITOR, d.version(), active, d.revision(), "switches off");
        Caller c = env.caller(user());
        String run;
        try {
            BootstrapView boot = env.m.queries.bootstrap(c);
            assertEquals(new FeaturesView(false, false, false, false), boot.config().features());
            assertEquals(0, boot.streakReward(), "no streak reward is advertised while the streak switch is off");
            run = env.m.runs.start(c).run().runId();
            assertFalse(env.m.queries.run(c, run).runConfig().features().haptics());
            env.m.runs.leave(c, run);
            assertEquals("FEATURE_DISABLED", assertThrows(GameException.class, () -> env.m.queries.products(c, run)).code());
            assertEquals("FEATURE_DISABLED", assertThrows(GameException.class, () -> env.m.queries.share(c, run)).code());
        } finally {
            var restore = env.m.admin.saveDraft(EDITOR, LaunchConfig.value(), null);
            env.m.admin.publish(EDITOR, restore.version(), d.version(), restore.revision(), "restore");
        }
        // the finished run keeps its pinned version; a new run under the restored version has everything
        assertEquals("FEATURE_DISABLED", assertThrows(GameException.class, () -> env.m.queries.share(c, run)).code());
        Caller c2 = env.caller(user());
        assertEquals(new FeaturesView(true, true, true, true), env.m.queries.bootstrap(c2).config().features());
        assertEquals(1, env.m.queries.bootstrap(c2).streakReward());
        String run2 = env.m.runs.start(c2).run().runId();
        env.m.runs.leave(c2, run2);
        assertFalse(env.m.queries.products(c2, run2).items().isEmpty());
        assertNotNull(env.m.queries.share(c2, run2));
    }

    // ------------------------------------------------------------------------------------------------ draft revisions

    static GameConfig plays(int n) {
        GameConfig b = LaunchConfig.value();
        return new GameConfig(n, b.inputMs(), b.acceptMs(), b.offerMs(), b.inactivityMs(), b.offerActivationMs(), b.timeoutGraceMs(),
                b.checkpointAutoContinueMs(), b.checkpoints(), b.continueRule(), b.streakReward(), b.bands(), b.payoutAlertThreshold(),
                b.priceChangeThresholdBp(), b.features());
    }

    @Test void anEditorCannotPublishOrOverwriteADraftChangedByAnotherEditor() {
        // review of b023340, finding 6: A reviewed 4 plays, B changed the shared draft to 5, A published and 5 went live
        Admin b = new Admin("editor-b", Role.EDITOR);
        int active = env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion);
        var seenByA = env.m.admin.saveDraft(EDITOR, plays(4), null);
        var savedByB = env.m.admin.saveDraft(b, plays(5), seenByA.revision());
        assertNotEquals(seenByA.revision(), savedByB.revision());
        assertEquals("DRAFT_CHANGED", assertThrows(GameException.class,
                () -> env.m.admin.publish(EDITOR, seenByA.version(), active, seenByA.revision(), "A publishes what A reviewed")).code());
        assertEquals("DRAFT_CHANGED", assertThrows(GameException.class, () -> env.m.admin.saveDraft(EDITOR, plays(6), seenByA.revision())).code());
        assertEquals("DRAFT_CHANGED", assertThrows(GameException.class, () -> env.m.admin.saveDraft(EDITOR, plays(6), null)).code(),
                "a draft exists: saving without its revision would overwrite it unseen");
        assertEquals(active, (int) env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion), "nothing was published");
        assertEquals(5, env.m.admin.config(EDITOR).draft().config().playsPerDay(), "B's content is intact");
        assertEquals("DRAFT_CHANGED", assertThrows(GameException.class, () -> env.m.admin.discardDraft(EDITOR, savedByB.version(), seenByA.revision())).code());
        env.m.admin.discardDraft(EDITOR, savedByB.version(), env.m.admin.config(EDITOR).draftRevision());   // after reloading
        assertNull(env.m.admin.config(EDITOR).draft());
    }
}
