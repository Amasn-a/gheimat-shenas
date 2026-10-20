package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.RunView;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignCodeProvider;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignRepository.Entitlement;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Admin;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Role;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.CampaignService;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.PriceExpertContext;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Campaign;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.JdbcAdminRepository;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the strict review of b023340 (findings 9, 16, 17, 23, 24, 30 and the server-side audit rules). */
class ReviewFixesIT {
    static TestEnv env;
    static final Admin EDITOR = new Admin("editor", Role.EDITOR);

    @BeforeAll static void up() throws Exception { env = TestEnv.create(null); }
    @AfterAll static void down() { env.close(); }

    static String user() { return "f" + UUID.randomUUID().toString().substring(0, 8); }

    // ------------------------------------------------------------------------------------------------ #9

    @Test void replacingARunOnAnotherDeviceKeepsTheDayStatistics() {
        String u = user();
        Caller a = env.caller(u, 0), b = env.caller(u, 1);
        env.m.runs.start(a);
        env.m.runs.start(b);                                            // replaces the run on device 0 (finished as replaced)
        String day = com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar.dateOf(env.clock.instant()).toString();
        assertEquals(2, env.count("SELECT plays_used FROM pe_daily_participation WHERE user_id = ? AND business_date = ?::date", a.userId(), day));
        assertEquals(1, env.count("SELECT finished_runs FROM pe_daily_participation WHERE user_id = ? AND business_date = ?::date", a.userId(), day),
                "the replaced run's settlement is not overwritten by the start that replaced it");
        assertEquals(Boolean.TRUE, env.scalar("SELECT streak_eligible FROM pe_daily_participation WHERE user_id = ? AND business_date = ?::date", a.userId(), day));
    }

    // ------------------------------------------------------------------------------------------------ #16

    Campaign draft(String name, Instant start, Instant end) {
        return new Campaign(null, name, start, end, List.of("cp_3"), 50_000, 10, "شرایط آزمایشی", 5, 250_000, 0, 0, null);
    }

    @Test void concurrentOverlappingCampaignsCannotBothBeCreated_andAnEndedCampaignStaysEnded() throws Exception {
        Instant s = env.clock.instant().plusSeconds(30 * 86_400), e = s.plusSeconds(86_400);
        AtomicInteger created = new AtomicInteger();
        env.parallel(6, i -> {
            try { env.m.admin.createCampaign(EDITOR, draft("کمپین " + i, s, e)); created.incrementAndGet(); }
            catch (GameException ex) { assertEquals("VALIDATION_FAILED", ex.code()); }
        });
        assertEquals(1, created.get(), "exactly one of the overlapping campaigns is created");
        String id = env.scalar("SELECT campaign_id FROM pe_campaign WHERE starts_at = ?", java.sql.Timestamp.from(s));
        // edits racing an end: whatever the interleaving, the campaign ends ended
        env.parallel(8, i -> {
            try {
                if (i % 2 == 0) env.m.admin.updateCampaign(EDITOR, id, draft("ویرایش " + i, s, e));
                else env.m.admin.endCampaign(EDITOR, id, "end " + i);
            } catch (GameException ex) { assertEquals("CAMPAIGN_RUNNING", ex.code()); }
        });
        assertEquals("ENDED", env.scalar("SELECT status FROM pe_campaign WHERE campaign_id = ?", id));
    }

    // ------------------------------------------------------------------------------------------------ #17

    @Test void providerExceptionsReachTheRetryCeilingAndAnAuditedRetry() {
        Instant s = env.clock.instant().plusSeconds(60 * 86_400);
        Campaign c = env.m.admin.createCampaign(EDITOR, draft("کمپین خطا", s, s.plusSeconds(86_400)));
        String id = "ent-" + UUID.randomUUID();
        env.m.ctx.tx().run(() -> env.m.ctx.campaigns().insertEntitlement(new Entitlement(id, c.campaignId(), "u_x", "run-x", "PENDING", 50_000,
                null, null, null, 0, env.clock.instant(), null, env.clock.instant(), null)));
        CampaignCodeProvider throwing = new CampaignCodeProvider() {
            @Override public Result issue(String e, String u, String camp, long amount, int days) { throw new IllegalStateException("provider down"); }
            @Override public boolean isFake() { return true; }
        };
        PriceExpertContext x = env.m.ctx;
        CampaignService broken = new CampaignService(new PriceExpertContext(x.tx(), x.runs(), x.players(), x.ledger(), x.catalog(), x.configs(),
                x.campaigns(), x.idempotency(), x.audit(), x.coin(), throwing, x.cipher(), x.events(), x.clock(), x.random(), x.bounds()));
        for (int i = 0; i < 40; i++) { broken.processDue(10); env.clock.advance(120_000); }
        assertEquals("NEEDS_REVIEW", env.scalar("SELECT status FROM pe_campaign_entitlement WHERE entitlement_id = ?", id));
        assertTrue(((Number) env.scalar("SELECT attempts FROM pe_campaign_entitlement WHERE entitlement_id = ?", id)).intValue() <= 20);
        assertEquals("provider error: IllegalStateException", env.scalar("SELECT last_error FROM pe_campaign_entitlement WHERE entitlement_id = ?", id));
        assertEquals(1, env.count("SELECT count(*) FROM pe_alert WHERE kind = 'CAMPAIGN_CODE_NEEDS_REVIEW' AND details->>'entitlement_id' = ?", id));
        // an operator lists it and re-queues it with a reason; the working provider then issues exactly one code
        assertTrue(env.m.admin.entitlementsNeedingReview(EDITOR).stream().anyMatch(m -> id.equals(m.get("entitlement_id"))));
        assertEquals("VALIDATION_FAILED", assertThrows(GameException.class, () -> env.m.admin.retryEntitlement(EDITOR, id, " ")).code());
        env.m.admin.retryEntitlement(EDITOR, id, "provider fixed");
        env.m.campaigns.processDue(10);
        assertEquals("ISSUED", env.scalar("SELECT status FROM pe_campaign_entitlement WHERE entitlement_id = ?", id));
        env.m.admin.endCampaign(EDITOR, c.campaignId(), "test end");
    }

    // ------------------------------------------------------------------------------------------------ #23

    @Test void aBusyPlayerDoesNotStallOtherPlayersDeadlines() throws Exception {
        Caller a = env.caller(user()), b = env.caller(user());
        RunView qa = env.m.runs.reveal(a, env.m.runs.start(a).run().runId());
        RunView qb = env.m.runs.reveal(b, env.m.runs.start(b).run().runId());
        env.clock.set(qb.question().acceptDeadline().plusMillis(5_000));
        try (Connection held = env.ds.getConnection()) {                 // a live request holds player A
            held.setAutoCommit(false);
            try (var ps = held.prepareStatement("SELECT 1 FROM pe_player WHERE user_id = ? FOR UPDATE")) { ps.setString(1, a.userId()); ps.executeQuery(); }
            Future<Integer> sweep = Executors.newSingleThreadExecutor().submit(() -> env.m.deadlines.sweep(100));
            sweep.get(10, TimeUnit.SECONDS);                                 // before the fix this waited behind player A
            assertEquals("TIMED_OUT", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", qb.question().questionId()));
            assertEquals("OPEN", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", qa.question().questionId()), "A is left to its own request");
            held.rollback();
        }
        env.m.deadlines.sweep(100);
        assertEquals("TIMED_OUT", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", qa.question().questionId()));
    }

    // ------------------------------------------------------------------------------------------------ #24

    @Test void killSwitchScanPagesThroughEveryActiveRun() {
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) runs.add(env.m.runs.start(env.caller(user())).run().runId());
        Set<String> seen = new HashSet<>();
        String after = null;
        while (true) {
            String from = after;
            List<String> page = env.m.ctx.tx().inTransaction(() -> env.m.ctx.runs().activeRunIds(from, 2));
            if (page.isEmpty()) break;
            assertTrue(page.size() <= 2);
            seen.addAll(page);
            after = page.get(page.size() - 1);
        }
        assertTrue(seen.containsAll(runs));
        assertEquals(env.count("SELECT count(*) FROM pe_run WHERE state <> 'FINISHED'"), seen.size());
    }

    // ------------------------------------------------------------------------------------------------ #30, audit rules

    @Test void aDemotedEditorSessionLosesWriteRightsAtOnce() {
        JdbcAdminRepository repo = new JdbcAdminRepository();
        Instant now = env.clock.instant();
        env.m.ctx.tx().run(() -> {
            repo.upsertAccount(new AdminRepository.Account("demoted", "Demoted", "EDITOR", "x", true));
            repo.insertSession("token-hash-demoted", "demoted", "EDITOR", now, now.plusSeconds(3600));
            repo.upsertAccount(new AdminRepository.Account("demoted", "Demoted", "VIEWER", "x", true));
        });
        assertEquals("VIEWER", env.m.ctx.tx().inTransaction(() -> repo.session("token-hash-demoted", now)).orElseThrow().role());
    }

    @Test void consequentialActionsNeedAReason_andHistoryCanSkipReads() {
        assertEquals("VALIDATION_FAILED", assertThrows(GameException.class, () -> env.m.admin.unblock(EDITOR, "u_nobody", "")).code());
        assertEquals("VALIDATION_FAILED", assertThrows(GameException.class, () -> env.m.admin.retryOperation(EDITOR, "op", null)).code());
        assertEquals("VALIDATION_FAILED", assertThrows(GameException.class, () -> env.m.admin.endCampaign(EDITOR, "c", " ")).code());
        for (int i = 0; i < 5; i++) env.m.admin.runs(EDITOR, "u_reads", null);   // audited reads
        var changes = env.m.admin.audit(EDITOR, null, null, true, null, null, 0);
        assertTrue(changes.stream().noneMatch(ev -> ev.action().startsWith("admin.read.") || ev.action().equals("config.draft")));
        assertTrue(env.m.admin.audit(EDITOR, null, null, false, null, null, 0).stream().anyMatch(ev -> ev.action().startsWith("admin.read.")));
    }
}
