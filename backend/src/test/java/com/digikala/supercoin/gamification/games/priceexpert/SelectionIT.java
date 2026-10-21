package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.RunView;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.SelectionService.Selected;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** T18 direction 50/50, T19 unseen preference and R-62 fallback, T20 look-ahead and terminal no-products. */
class SelectionIT {
    static TestEnv env;
    static int catalog;
    static GameConfig cfg;

    static ImportRow p(long dkp, long price) {
        return new ImportRow(dkp, dkp * 10, "کالای آزمایشی " + dkp, "کالای آزمایشی " + dkp, "https://img.test/" + dkp + ".jpg", price, null, "test", true, null);
    }

    @BeforeAll static void up() throws Exception {
        List<ImportRow> rows = new ArrayList<>();
        // reference 1,000,000 with plenty of band-0 candidates on both sides (d > 50%)
        rows.add(p(1, 1_000_000));
        for (int i = 0; i < 20; i++) rows.add(p(100 + i, 2_000_000 + i * 10_000));   // dearer, ratio ≥ 2
        for (int i = 0; i < 20; i++) rows.add(p(200 + i, 300_000 + i * 1_000));      // cheaper, ratio ≥ 3
        // chain for look-ahead: 5,000 → 8,000 is in band 0 (60%), and nothing in band 0 next to 8,000 except via others
        rows.add(p(300, 10_000_000_000L));                                             // band-0 candidate with no band-1 follow-up
        rows.add(p(400, 2_700_000));                                                   // band-1 follow-up for dearer refs near 2,000,000
        env = TestEnv.create(rows);
        catalog = env.m.ctx.tx().inTransaction(env.m.ctx::activeCatalogVersion);
        cfg = env.m.ctx.config(env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion));
    }
    @AfterAll static void down() { env.close(); }

    Optional<Selected> pick(long refPrice, int score, Set<Long> run, String user) {
        LocalDate day = BusinessCalendar.dateOf(env.clock.instant());
        return env.m.ctx.tx().inTransaction(() -> env.m.selection.chooseComparison(catalog, cfg, refPrice, score, run, user, day));
    }

    @Test void t18_directionIsFiftyFiftyWhenBothSidesExist() {
        int dearer = 0, n = 400;
        for (int i = 0; i < n; i++) {
            Selected s = pick(1_000_000, 0, Set.of(1L), "u_dir").orElseThrow();
            if (s.product().priceToman() > 1_000_000) dearer++;
        }
        // seeded random source; binomial(400, .5): 99.9% interval ≈ 167–233
        assertTrue(dearer > 167 && dearer < 233, "dearer " + dearer + " of " + n);
    }

    @Test void t19_unseenFirst_thenLeastRecentlySeenInTheSameBand_neverFromThisRun() {
        String user = "u_seen";
        LocalDate day = BusinessCalendar.dateOf(env.clock.instant());
        // the player saw every product today except dkp 1 (the reference) and dkp 119; dkp 100 was seen earliest
        java.time.Instant t = env.clock.instant().minusSeconds(3600);
        env.sql("INSERT INTO pe_seen_product (user_id, show_date, dkp, first_seen_at, last_seen_at) "
                + "SELECT ?, ?, dkp, ?::timestamptz + make_interval(secs => dkp), ?::timestamptz + make_interval(secs => dkp) FROM pe_catalog_product "
                + "WHERE catalog_version = ? AND dkp NOT IN (1, 119)", user, day, java.sql.Timestamp.from(t), java.sql.Timestamp.from(t), catalog);
        for (int k = 0; k < 10; k++) assertEquals(119, pick(1_000_000, 0, Set.of(1L), user).orElseThrow().product().dkp(), "the only unseen one");
        // with 119 in this run, nothing unseen remains: the earliest seen today (dkp 100) comes back, never 119
        Selected s = pick(1_000_000, 0, Set.of(1L, 119L), user).orElseThrow();
        assertEquals(100, s.product().dkp());
        // the band is never widened: score 5 (30 < d ≤ 50%) has no candidate around 1,000,000 in this catalog
        assertTrue(pick(1_000_000, 5, Set.of(1L), user).isEmpty());
    }

    @Test void t20_lookAheadRejectsDeadEnds_andRevealEndsTheRunWhenNothingIsLeft() {
        // at score 4 the next question needs band 1 (30 < d ≤ 50%): 10,000,000,000 has no such follow-up and is never chosen;
        // every chosen candidate leaves at least one valid next question
        for (int k = 0; k < 40; k++) {
            Selected s = pick(1_000_000, 4, Set.of(1L), "u_look").orElseThrow();
            assertNotEquals(300, s.product().dkp());
            long price = s.product().priceToman();
            boolean next = env.m.ctx.tx().inTransaction(() -> env.m.selection.hasNextQuestion(catalog, cfg, price, 5, Set.of(1L, s.product().dkp())));
            assertTrue(next, "candidate " + s.product().dkp() + " is a dead end");
        }
        // an actual run on this catalog eventually ends with no_products, settled normally, no fabricated miss
        Caller c = env.caller("runner");
        String run = env.m.runs.start(c).run().runId();
        RunView v = null;
        for (int i = 0; i < 40; i++) {
            v = env.m.runs.reveal(c, run);
            if (v.state().equals("FINISHED")) break;
            env.m.runs.answer(c, v.question().questionId(), Choice.valueOf(env.rightChoice(run)), v.question().t0().plusMillis(1000));
        }
        assertEquals("FINISHED", v.state());
        assertEquals("no_products", v.finishReason());
        assertNull(env.m.queries.products(c, run).lossProductId());
        // every product appeared at most once in the run
        assertEquals(0, env.count("SELECT count(*) - count(DISTINCT comparison_dkp) FROM pe_question WHERE run_id = ?", run));
    }
}
