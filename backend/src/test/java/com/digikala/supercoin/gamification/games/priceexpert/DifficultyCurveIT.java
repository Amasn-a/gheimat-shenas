package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.SelectionService.Selected;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.ProductDatabaseReader;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Difficulty curve audit (owner brief 2026-10-20 §13), end to end on the real 989-product sample. The band oracle below is
 * written from decision R-41 directly, independently of DifficultyPolicy, so a drift in the implementation, the launch
 * configuration or a published A04 version that diverges from R-41 fails here:
 * score 0–4: d > 50% (no upper limit) · 5–9: 30% < d ≤ 50% · 10–14: 15% < d ≤ 30% · 15–19: 8% < d ≤ 15% · 20+: 3% ≤ d ≤ 8%
 * (R-41 distances; band starts follow the checkpoints 5/10/15/20 since the redesign of 2026-10-21),
 * with d = 100 × (max / min − 1).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class DifficultyCurveIT {
    static TestEnv env;
    static int realCatalog;

    @BeforeAll static void up() throws Exception {
        env = TestEnv.create(null);
        realCatalog = env.m.ctx.tx().inTransaction(env.m.ctx::activeCatalogVersion);
    }
    @AfterAll static void down() { env.close(); }

    /** R-41, exact integer form: 10,000 × (hi − lo) compared with (basis points × lo). */
    static boolean r41(int score, long a, long b) {
        long hi = Math.max(a, b), lo = Math.min(a, b), lhs = 10_000L * (hi - lo);
        if (score <= 4) return lhs > 5_000L * lo;
        if (score <= 9) return lhs > 3_000L * lo && lhs <= 5_000L * lo;
        if (score <= 14) return lhs > 1_500L * lo && lhs <= 3_000L * lo;
        if (score <= 19) return lhs > 800L * lo && lhs <= 1_500L * lo;
        return lhs >= 300L * lo && lhs <= 800L * lo;
    }
    static int r41Band(int score) { return score <= 4 ? 0 : score <= 9 ? 1 : score <= 14 ? 2 : score <= 19 ? 3 : 4; }

    static ImportRow p(long dkp, long price) {
        return new ImportRow(dkp, dkp * 10, "کالای آزمایشی " + dkp, "کالای آزمایشی " + dkp, "https://img.test/" + dkp + ".jpg", price, null, "test", true, null);
    }

    /** Plays one run answering correctly until it ends or reaches {@code maxScore}; returns its question rows. */
    List<Map<String, Object>> playCorrect(Caller c, int maxScore) {
        String run = env.m.runs.start(c).run().runId();
        for (int s = 0; s < maxScore; s++) {
            RunView q = env.m.runs.reveal(c, run);
            if (!"QUESTION_OPEN".equals(q.state())) break;               // no_products: an ordinary end, checked separately
            assertEquals(s, q.score());
            env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(900));
        }
        env.m.runs.leave(c, run);
        List<Map<String, Object>> rows = new ArrayList<>();
        env.m.ctx.tx().run(() -> env.m.ctx.runs().questionsOfRun(run).forEach(q -> rows.add(Map.of(
                "score", q.scoreBefore, "band", q.bandIndex, "ref", q.referencePrice, "cmp", q.comparisonPrice, "id", q.questionId))));
        return rows;
    }

    @Test void a_everyQuestionIsInItsR41Band_beforeAtAndAfterEachCheckpoint() {
        Map<Integer, Integer> seenPerScore = new TreeMap<>();
        for (int run = 0; run < 6; run++) {
            for (Map<String, Object> q : playCorrect(env.caller("curve" + run), 22)) {
                int score = (int) q.get("score");
                long ref = (long) q.get("ref"), cmp = (long) q.get("cmp");
                assertEquals(r41Band(score), (int) q.get("band"), "band index at score " + score);
                assertTrue(r41(score, ref, cmp), "score " + score + ": " + ref + " vs " + cmp + " is outside its R-41 band");
                assertNotEquals(ref, cmp, "no equal-price pair is ever drawn (every band has a positive minimum)");
                seenPerScore.merge(score, 1, Integer::sum);
            }
        }
        // checkpoints at 5 / 10 / 15 / 20: the questions just before, at and after each threshold were all drawn
        for (int s : new int[]{4, 5, 6, 9, 10, 11, 14, 15, 16, 19, 20, 21}) assertTrue(seenPerScore.containsKey(s), "no question at score " + s + ": " + seenPerScore);
    }

    @Test void b_theComparisonIsChosenAtRevealNotBefore_andARetryKeepsIt() {
        Caller c = env.caller("reveal-point");
        String run = env.m.runs.start(c).run().runId();
        assertEquals(0, env.count("SELECT count(*) FROM pe_question WHERE run_id = ?", run), "READY: nothing is drawn before «نمایش بده»");
        assertEquals(1, env.count("SELECT count(*) FROM pe_seen_product WHERE user_id = ?", c.userId()), "only the reference was shown");
        RunView q = env.m.runs.reveal(c, run);
        RunView again = env.m.runs.reveal(c, run);                         // a retried reveal never draws a second product
        assertEquals(q.question().questionId(), again.question().questionId());
        assertEquals(1, env.count("SELECT count(*) FROM pe_question WHERE run_id = ?", run));
        assertEquals(2, env.count("SELECT count(*) FROM pe_seen_product WHERE user_id = ?", c.userId()));
        env.m.runs.leave(c, run);
    }

    @Test void c_pricesAreTheRunsPinnedCatalogSnapshot_evenAfterANewCatalogIsPublished() throws Exception {
        Caller c = env.caller("snapshot");
        StartView st = env.m.runs.start(c);
        String run = st.run().runId();
        long shownReference = st.run().reference().price();
        // a new daily catalog with every price changed is published while the run is open
        List<ImportRow> changed = new ArrayList<>();
        for (ImportRow r : ProductDatabaseReader.read(TestEnv.CATALOG, null))
            changed.add(new ImportRow(r.dkp(), r.variantId(), r.title(), r.displayTitle(), r.imageUrl(), r.priceToman() * 2, null, r.category(), r.eligible(), r.excludeReason()));
        int v2 = env.m.catalog.importAndPublish(changed, "test", "test").version();
        try {
            RunView q = env.m.runs.reveal(c, run);
            long refPrice = env.scalar("SELECT reference_price FROM pe_question WHERE question_id = ?", q.question().questionId());
            long cmpPrice = env.scalar("SELECT comparison_price FROM pe_question WHERE question_id = ?", q.question().questionId());
            long cmpDkp = env.scalar("SELECT comparison_dkp FROM pe_question WHERE question_id = ?", q.question().questionId());
            assertEquals(shownReference, refPrice, "the question compares against the reference price the player saw");
            assertEquals(cmpPrice, (long) env.scalar("SELECT price_toman FROM pe_catalog_product WHERE catalog_version = ? AND dkp = ?", realCatalog, cmpDkp),
                    "the comparison price is the pinned version's, not the newly published one");
            assertTrue(r41(0, refPrice, cmpPrice));
            AnswerView a = env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(run)), q.question().t0().plusMillis(700));
            assertEquals(cmpPrice, a.revealedPrice(), "the revealed price is the snapshot");
            env.m.runs.leave(c, run);
            assertEquals(v2, (int) env.m.ctx.tx().inTransaction(env.m.ctx::activeCatalogVersion));
        } finally {
            env.m.catalog.importAndPublish(ProductDatabaseReader.read(TestEnv.CATALOG, null), "test", "restore");
        }
    }

    @Test void d_invalidRowsAreFilteredAtImport_notAtRuntime() throws Exception {
        List<ImportRow> rows = new ArrayList<>(List.of(p(1, 1_000), p(2, 2_000), p(3, 4_000), p(4, 8_000)));
        rows.add(p(5, 0));                                                  // invalid price
        rows.add(new ImportRow(6, 60, "x", "x", "https://img.test/6.jpg", 3_000, null, "test", false, "refurbished"));   // excluded with a reason
        var report = env.m.catalog.importAndPublish(rows, "test", "invalid rows");
        try {
            assertEquals(1, report.rejectedRows(), "the zero price never reaches the catalog");
            assertEquals(4, report.eligible());
            assertEquals(0, env.count("SELECT count(*) FROM pe_catalog_product WHERE catalog_version = ? AND dkp = 5", report.version()));
            // runtime selection only ever reads eligible rows of the version: the excluded product cannot be drawn
            GameConfig cfg = env.m.ctx.config(env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion));
            LocalDate day = BusinessCalendar.dateOf(env.clock.instant());
            for (int i = 0; i < 20; i++) {
                Optional<Selected> s = env.m.ctx.tx().inTransaction(() -> env.m.selection.chooseComparison(report.version(), cfg, 1_000, 0, Set.of(1L), "u_inv", day));
                s.ifPresent(x -> assertNotEquals(6L, x.product().dkp()));
            }
        } finally {
            env.m.catalog.importAndPublish(ProductDatabaseReader.read(TestEnv.CATALOG, null), "test", "restore");
        }
    }

    @Test void e_extremeEasyPairsStayAllowed_andTheSeenTodayFallbackKeepsTheBand() throws Exception {
        // band 0 has no upper limit: a 9,900% pair is a permitted easy question (R-41), never filtered out at runtime
        var v = env.m.catalog.importAndPublish(List.of(p(1, 10_000), p(2, 1_000_000), p(3, 100_000_000), p(4, 10_000_000_000L)), "test", "extremes").version();
        try {
            GameConfig cfg = env.m.ctx.config(env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion));
            LocalDate day = BusinessCalendar.dateOf(env.clock.instant());
            Optional<Selected> s = env.m.ctx.tx().inTransaction(() -> env.m.selection.chooseComparison(v, cfg, 10_000, 0, Set.of(1L), "u_ext", day));
            assertTrue(s.isPresent());
            assertTrue(s.get().product().priceToman() >= 1_000_000, "a pair of at least 100× (d ≥ 9,900%) is drawn as an ordinary band-0 question");
            assertTrue(r41(0, 10_000, s.get().product().priceToman()));
            // every candidate seen earlier today: the fallback reuses the least recently seen one, inside the same band
            env.sql("INSERT INTO pe_seen_product (user_id, show_date, dkp, first_seen_at, last_seen_at) VALUES ('u_ext', ?, 2, now(), now()), ('u_ext', ?, 3, now(), now()), ('u_ext', ?, 4, now(), now())", day, day, day);
            Optional<Selected> f = env.m.ctx.tx().inTransaction(() -> env.m.selection.chooseComparison(v, cfg, 10_000, 0, Set.of(1L), "u_ext", day));
            assertTrue(f.isPresent(), "fallback to products seen today");
            assertTrue(r41(0, 10_000, f.get().product().priceToman()), "the fallback never widens or narrows the band");
            assertEquals(0, f.get().bandIndex());
        } finally {
            env.m.catalog.importAndPublish(ProductDatabaseReader.read(TestEnv.CATALOG, null), "test", "restore");
        }
    }

    @Test void f_bothDirectionsAreDrawn_andTheBandFollowsTheServerScoreOnly() {
        int dearer = 0, cheaper = 0;
        for (int run = 0; run < 8; run++) {
            for (Map<String, Object> q : playCorrect(env.caller("dir" + run), 4)) {
                if ((long) q.get("cmp") > (long) q.get("ref")) dearer++; else cheaper++;
            }
        }
        assertTrue(dearer > 3 && cheaper > 3, "both directions occur: dearer " + dearer + ", cheaper " + cheaper);
        // reveal takes no input: the band is derived from the stored score (the HTTP layer rejects any body field, see HttpIT)
        boolean checked = false;
        for (int i = 0; i < 6 && !checked; i++) {
            Caller c = env.caller("score-only" + i);
            String run = env.m.runs.start(c).run().runId();
            env.sql("UPDATE pe_run SET score = 20 WHERE run_id = ?", run);    // what the server holds is what decides
            RunView q = env.m.runs.reveal(c, run);
            if (!"QUESTION_OPEN".equals(q.state())) { assertEquals("no_products", q.finishReason()); continue; }
            String id = q.question().questionId();
            assertEquals(4, (int) env.count("SELECT band_index FROM pe_question WHERE question_id = ?", id));
            assertTrue(r41(20, env.scalar("SELECT reference_price FROM pe_question WHERE question_id = ?", id),
                    env.scalar("SELECT comparison_price FROM pe_question WHERE question_id = ?", id)));
            env.m.runs.leave(c, run);
            checked = true;
        }
        assertTrue(checked, "a band-4 question was drawn for a stored score of 20");
    }
}
