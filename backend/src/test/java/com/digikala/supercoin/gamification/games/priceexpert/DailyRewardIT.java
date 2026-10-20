package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Admin;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Role;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Daily reward modal (owner brief 2026-10-20). Earning is the spec rule, unchanged: the first finished run of a start day
 * records that day's reward (1 DigiCoin at launch); opening the game is not attendance. Delivery is claim-based: the
 * reward waits as CLAIMABLE until «دریافت کن», and only a confirmed credit counts as received.
 */
class DailyRewardIT {
    static TestEnv env;
    static final Admin EDITOR = new Admin("editor", Role.EDITOR);
    static final ZoneId TEH = ZoneId.of("Asia/Tehran");

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }

    static String user() { return "d" + UUID.randomUUID().toString().substring(0, 8); }
    static String finishOneRun(Caller c) { String run = env.m.runs.start(c).run().runId(); env.m.runs.leave(c, run); return run; }
    static long credited(String user) {
        return env.count("SELECT coalesce(sum(amount), 0) FROM pe_fake_wallet_tx WHERE user_id = ? AND kind = 'CREDIT' AND applied", "u_" + user);
    }

    @Test void openingTheGameIsNotAttendance_theFirstFinishedRunRecordsOneClaimableReward() {
        String u = user();
        Caller c = env.caller(u);
        DailyRewardView none = env.m.queries.bootstrap(c).dailyReward();
        assertEquals("none", none.state());
        assertEquals(0, none.streakDays());
        assertEquals(7, none.days().size());
        assertTrue(none.days().get(6).today());
        assertTrue(none.days().stream().noneMatch(DailyRewardDay::attended));
        String run = env.m.runs.start(c).run().runId();
        assertEquals("none", env.m.queries.bootstrap(c).dailyReward().state(), "a started, unfinished run is not attendance");
        env.m.runs.leave(c, run);
        DailyRewardView earned = env.m.queries.bootstrap(c).dailyReward();
        assertEquals("claimable", earned.state());
        assertEquals(1, earned.amount(), "the configured amount (1 at launch)");
        assertEquals(1, earned.streakDays());
        assertTrue(earned.days().get(6).attended());
        finishOneRun(c);                                                  // a second finished run the same day earns nothing more
        assertEquals(1, env.m.queries.bootstrap(c).dailyReward().amount());
        assertEquals(0, credited(u), "nothing is credited before the claim");
        env.drainLedger();
        assertEquals(0, credited(u), "the worker never pays an unclaimed reward");
    }

    @Test void openingTheModalClaimsNothing_aClaimCreditsOnceDespiteTapsRetriesAndASecondDevice() throws Exception {
        String u = user();
        Caller phone = env.caller(u, 0), tablet = env.caller(u, 1);
        finishOneRun(phone);
        for (int i = 0; i < 3; i++) assertEquals("claimable", env.m.dailyRewards.status(phone).state(), "reading is not claiming");
        List<DailyRewardView> out = Collections.synchronizedList(new ArrayList<>());
        env.parallel(6, i -> out.add(env.m.dailyRewards.claim(i % 2 == 0 ? phone : tablet)));
        env.drainLedger();
        assertEquals(1, credited(u), "exactly one credit for the day");
        assertEquals(1, env.count("SELECT count(*) FROM pe_coin_operation WHERE user_id = ? AND type = 'STREAK_REWARD'", "u_" + u));
        DailyRewardView after = env.m.dailyRewards.status(tablet);
        assertEquals("claimed", after.state());
        assertEquals(1, after.amount());
        assertTrue(out.stream().allMatch(v -> Set.of("claimed", "processing").contains(v.state())), "every caller sees the one claim");
        assertEquals("claimed", env.m.dailyRewards.claim(phone).state(), "a late repeat changes nothing");
        env.drainLedger();
        assertEquals(1, credited(u));
    }

    @Test void anUnconfirmedClaimIsProcessing_thenConfirmed_aRejectedOneIsDelayedNotLost() {
        String u = user();
        Caller c = env.caller(u);
        finishOneRun(c);
        env.script(u, "[{\"op\":\"credit\",\"outcome\":\"unknown_not_applied\"}]");
        DailyRewardView v = env.m.dailyRewards.claim(c);
        assertEquals("processing", v.state(), "network or provider uncertainty is not a failure");
        assertEquals(0, credited(u));
        env.clock.advance(2_000);
        env.drainLedgerFully();
        assertEquals("claimed", env.m.dailyRewards.status(c).state());
        assertEquals(1, credited(u));

        String u2 = user();
        Caller c2 = env.caller(u2);
        finishOneRun(c2);
        env.script(u2, "[{\"op\":\"credit\",\"outcome\":\"failed\"}]");
        DailyRewardView rejected = env.m.dailyRewards.claim(c2);
        assertEquals("delayed", rejected.state(), "a rejected credit goes to review; the player sees a delay, never a silent loss");
        assertEquals(1, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'COIN_OPERATION_REJECTED' AND details->>'operation_id' = ?", rejected.operationIds().get(0)));
    }

    @Test void streakDaysAndAttendedDaysFollowTheExistingRules_andUnclaimedDaysAddUp() {
        Instant saved = env.clock.instant();
        try {
            LocalDate d = LocalDate.of(2026, 11, 2);
            String u = user();
            Caller c = env.caller(u);
            env.clock.set(ZonedDateTime.of(d, LocalTime.of(10, 0), TEH).toInstant());
            finishOneRun(c);
            env.clock.set(ZonedDateTime.of(d.plusDays(1), LocalTime.of(23, 58), TEH).toInstant());
            String late = env.m.runs.start(c).run().runId();
            env.clock.set(ZonedDateTime.of(d.plusDays(2), LocalTime.of(0, 3), TEH).toInstant());
            env.m.runs.leave(c, late);                                    // finished after midnight: the day is its START day (d + 1)
            DailyRewardView v = env.m.queries.bootstrap(c).dailyReward(); // first entry of d + 2, nothing played yet today
            assertEquals(2, v.streakDays(), "d and d + 1, still alive until d + 2 ends");
            assertEquals("claimable", v.state());
            assertEquals(2, v.amount(), "two unclaimed days, one reward each");
            List<DailyRewardDay> days = v.days();
            assertEquals(d.plusDays(2), days.get(6).date());
            assertFalse(days.get(6).attended());
            assertTrue(days.get(5).attended());
            assertTrue(days.get(4).attended());
            env.m.dailyRewards.claim(c);
            env.drainLedger();
            assertEquals(2, credited(u));
            assertEquals(List.of(d, d.plusDays(1)), env.m.ctx.tx().inTransaction(() -> env.m.ctx.ledger().latestStreakClaim(c.userId()))
                    .stream().map(o -> o.capDay).toList(), "each reward stays on its own day's cap");
            env.clock.set(ZonedDateTime.of(d.plusDays(4), LocalTime.of(9, 0), TEH).toInstant());
            assertEquals(0, env.m.queries.bootstrap(c).dailyReward().streakDays(), "a day without a finished run resets the streak");
        } finally { env.clock.set(saved); }
    }

    @Test void aBlockedPlayerCannotClaim_theRewardWaits() {
        String u = user();
        Caller c = env.caller(u);
        finishOneRun(c);
        env.m.admin.block(EDITOR, c.userId(), "review", "r-1");
        assertEquals("PLAYER_BLOCKED", assertThrows(GameException.class, () -> env.m.dailyRewards.claim(c)).code());
        env.m.admin.unblock(EDITOR, c.userId(), "ok");
        assertEquals("claimable", env.m.dailyRewards.status(c).state());
        env.m.dailyRewards.claim(c);
        env.drainLedger();
        assertEquals(1, credited(u));
    }
}
