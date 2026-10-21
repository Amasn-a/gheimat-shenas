package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.SelectionService.Selected;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of b023340, findings 7, 8 and 19: what admission, offers and paid continues promise is exactly what reveal
 * finds; a sample of dead ends is not reported as exhaustion; an empty price range is an ordinary "no candidate".
 * Each test publishes its own small catalog version.
 */
class SelectionConsistencyIT {
    static TestEnv env;
    static GameConfig cfg;

    static ImportRow p(long dkp, long price) {
        return new ImportRow(dkp, dkp * 10, "کالای آزمایشی " + dkp, "کالای آزمایشی " + dkp, "https://img.test/" + dkp + ".jpg", price, null, "test", true, null);
    }

    @BeforeAll static void up() throws Exception {
        env = TestEnv.create(List.of(p(1, 100), p(2, 200), p(3, 1_000)));
        cfg = env.m.ctx.config(env.m.ctx.tx().inTransaction(env.m.ctx::activeConfigVersion));
    }
    @AfterAll static void down() { env.close(); }

    static int publish(List<ImportRow> rows) { return env.m.catalog.importAndPublish(rows, "test", "test").version(); }
    static LocalDate today() { return BusinessCalendar.dateOf(env.clock.instant()); }

    @Test void twoProductCatalogIsNotAdmittedWhenRevealWouldFindNothing() {
        int v = publish(List.of(p(1, 100), p(2, 200)));
        // a direct pair exists (100 → 200 is in band 0), but after a correct answer nothing is left: reveal's look-ahead fails
        assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.selection.hasNextQuestion(v, cfg, 100, 0, Set.of(1L))));
        assertFalse(env.m.ctx.tx().inTransaction(() -> env.m.selection.canReveal(v, cfg, 100, 0, Set.of(1L))));
        GameException e = assertThrows(GameException.class, () -> env.m.runs.start(env.caller("two")));
        assertEquals("GAME_UNAVAILABLE", e.code(), "no play is consumed for a run that would end at once");
        assertEquals(0, env.count("SELECT count(*) FROM pe_run WHERE user_id = 'u_two'"));
    }

    @Test void deadEndSampleDoesNotHideAValidSeenCandidate() {
        // reference 100 at score 4 (band 0, > 50 %); next question at score 5 needs band 1 (30–50 %)
        List<ImportRow> rows = new ArrayList<>(List.of(p(1, 100), p(2, 250), p(3, 350)));
        for (int i = 0; i < 60; i++) rows.add(p(1_000 + i, 200));          // unseen, but each one is a dead end at score 5
        int v = publish(rows);
        String user = "u_sample";
        env.sql("INSERT INTO pe_seen_product (user_id, show_date, dkp, first_seen_at, last_seen_at) VALUES (?, ?, 2, now(), now()), (?, ?, 3, now(), now())",
                user, today(), user, today());
        Optional<Selected> s = env.m.ctx.tx().inTransaction(() -> env.m.selection.chooseComparison(v, cfg, 100, 4, Set.of(1L), user, today()));
        assertTrue(s.isPresent(), "250 → 350 is a valid path; the 48-candidate sample must not be read as exhaustion");
        assertTrue(Set.of(2L, 3L).contains(s.get().product().dkp()));
        assertTrue(env.m.ctx.tx().inTransaction(() -> env.m.selection.canReveal(v, cfg, 100, 4, Set.of(1L))));
    }

    @Test void emptyPriceRangeIsNoCandidateNotAnError() {
        int v = publish(List.of(p(1, 1), p(2, 3), p(3, 7), p(4, 20)));
        // reference price 1: nothing can be cheaper, so the CHEAPER range is empty
        Optional<Selected> s = assertDoesNotThrow(() -> env.m.ctx.tx().inTransaction(() ->
                env.m.selection.chooseComparison(v, cfg, 1, 0, Set.of(1L), "u_one", today())));
        assertTrue(s.isPresent());
        assertTrue(s.get().product().priceToman() > 1);
    }

    @Test void concurrentImportsGetDistinctVersions() throws Exception {
        // review of b023340, #25: the version number was allocated before the lock, so two imports could pick the same one
        List<Integer> versions = Collections.synchronizedList(new ArrayList<>());
        env.parallel(3, i -> versions.add(publish(List.of(p(1, 100), p(2, 1_000), p(3, 10_000)))));
        assertEquals(3, new HashSet<>(versions).size(), "versions " + versions);
        assertEquals(Collections.max(versions), env.m.ctx.tx().inTransaction(env.m.ctx::activeCatalogVersion));
    }
}
