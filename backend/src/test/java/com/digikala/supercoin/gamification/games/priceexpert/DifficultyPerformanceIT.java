package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.dto.Views.RunView;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Selection cost at a realistic catalog size (owner brief 2026-10-20 §13): 20,000 eligible products with log-uniform
 * prices from 10,000 to 500,000,000 toman (the real sample spans 9,800–469,000,000). Each reveal draws a comparison in
 * the band of the current score with look-ahead. The bound is a regression guard for this single-node test database,
 * not a production SLO (capacity targets are still open in the spec).
 */
class DifficultyPerformanceIT {
    static TestEnv env;

    @BeforeAll static void up() throws Exception {
        Random r = new Random(7);
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 1; i <= 20_000; i++) {
            long price = Math.round(Math.exp(Math.log(10_000) + r.nextDouble() * (Math.log(500_000_000) - Math.log(10_000))));
            rows.add(new ImportRow(i, i * 10L, "کالای حجمی " + i, "کالای حجمی " + i, "https://img.test/" + i + ".jpg", price, null, "load", true, null));
        }
        env = TestEnv.create(rows);
    }
    @AfterAll static void down() { env.close(); }

    @Test void revealStaysFastWithTwentyThousandProducts() {
        List<Long> ms = new ArrayList<>();
        int questions = 0;
        for (int run = 0; run < 40; run++) {
            Caller c = env.caller("load" + run);
            String id = env.m.runs.start(c).run().runId();
            for (int s = 0; s < 12; s++) {
                long t = System.nanoTime();
                RunView q = env.m.runs.reveal(c, id);
                ms.add((System.nanoTime() - t) / 1_000_000);
                if (!"QUESTION_OPEN".equals(q.state())) break;
                questions++;
                env.m.runs.answer(c, q.question().questionId(), Choice.valueOf(env.rightChoice(id)), q.question().t0().plusMillis(800));
            }
            env.m.runs.leave(c, id);
        }
        Collections.sort(ms);
        long p50 = ms.get(ms.size() / 2), p95 = ms.get((int) (ms.size() * 0.95)), max = ms.get(ms.size() - 1);
        System.out.println("[perf] reveals=" + ms.size() + " questions=" + questions + " p50=" + p50 + "ms p95=" + p95 + "ms max=" + max + "ms");
        assertTrue(questions >= 400, "runs reach the late bands with a large catalog: " + questions);
        assertTrue(p95 < 250, "p95 reveal " + p95 + " ms");
    }
}
