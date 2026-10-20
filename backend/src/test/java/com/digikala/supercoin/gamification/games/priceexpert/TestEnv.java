package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.ProductDatabaseReader;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.JdbcAdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Migrator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * Integration-test environment: a real PostgreSQL schema, the real module wiring, a controllable clock, a seeded random
 * source and the in-project fake coin provider. Workers are not started; tests drive sweeps and the ledger explicitly.
 */
public final class TestEnv implements AutoCloseable {
    public static final String URL = System.getProperty("pe.test.db", "jdbc:postgresql://127.0.0.1:5432/price_expert_test?user=pe&password=pe");
    public static final Path CATALOG = Path.of(System.getProperty("pe.catalog", "../data/qeymatshenas_products.json"));

    /** Manually advanced clock (InstantSource); thread-safe. */
    public static final class TestClock implements InstantSource {
        private volatile Instant now;
        public TestClock(Instant start) { now = start; }
        @Override public Instant instant() { return now; }
        public void set(Instant t) { now = t; }
        public Instant advance(long ms) { now = now.plusMillis(ms); return now; }
    }

    public final HikariDataSource ds;
    public final TestClock clock;
    public PriceExpertModule m;
    public final Map<String, String> env;

    private TestEnv(HikariDataSource ds, TestClock clock, Map<String, String> env) {
        this.ds = ds;
        this.clock = clock;
        this.env = env;
        rebuild();
    }

    /** Fresh schema; catalog = the real 989-product sample unless a custom list is given. */
    public static TestEnv create(List<ImportRow> catalog) throws Exception {
        // the schema is dropped below: only ever against a disposable database whose name says so
        String db = URL.replaceFirst("^jdbc:postgresql://[^/]+/", "").replaceFirst("[?].*$", "");
        if (!db.endsWith("_test")) throw new IllegalStateException("refusing to wipe database '" + db + "': test databases must end with _test");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(URL);
        hc.setMaximumPoolSize(24);
        HikariDataSource ds = new HikariDataSource(hc);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        Migrator.migrate(ds);
        // 12:00 Asia/Tehran on a fixed day, so midnight is far unless a test moves there
        TestClock clock = new TestClock(ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Tehran")).toInstant());
        Map<String, String> env = new HashMap<>(Map.of("PE_ENV", "test", "PE_COIN_PROVIDER", "fake", "PE_FAKE_START_BALANCE", "120"));
        TestEnv t = new TestEnv(ds, clock, env);
        t.m.admin.ensureLaunchConfig();
        t.m.catalog.importAndPublish(catalog != null ? catalog : ProductDatabaseReader.read(CATALOG, null), "test", "test");
        return t;
    }

    /** Simulates a process restart: brand-new module objects on the same database (nothing kept in memory). */
    public void rebuild() {
        m = new PriceExpertModule(env, ds, clock, new java.util.Random(42));
        m.admin.bind(new JdbcAdminRepository(), Json::write, PriceExpertServer::flatten);
    }

    public Caller caller(String user) { return new Caller("u_" + user, deviceOf(user, 0)); }
    public Caller caller(String user, int device) { return new Caller("u_" + user, deviceOf(user, device)); }
    public static String deviceOf(String user, int n) { return UUID.nameUUIDFromBytes((user + "#" + n).getBytes()).toString(); }

    public void sql(String sql, Object... args) {
        try (Connection c = ds.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.execute();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public <T> T scalar(String sql, Object... args) {
        try (Connection c = ds.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                @SuppressWarnings("unchecked") T v = (T) rs.getObject(1);
                return v;
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public long count(String sql, Object... args) { Number n = scalar(sql, args); return n == null ? 0 : n.longValue(); }

    /** The hidden comparison price of the open question (test oracle). */
    public String rightChoice(String runId) {
        long ref = scalar("SELECT reference_price FROM pe_question WHERE run_id = ? AND state = 'OPEN'", runId);
        long cmp = scalar("SELECT comparison_price FROM pe_question WHERE run_id = ? AND state = 'OPEN'", runId);
        return cmp > ref ? "higher" : "lower";
    }

    public static String flip(String c) { return c.equals("higher") ? "lower" : "higher"; }

    /** Runs the ledger until no due operation is left (bounded). */
    public void drainLedger() {
        for (int i = 0; i < 20; i++) {
            if (m.payments.processDue(100) == 0) return;
            clock.advance(25);
        }
    }

    /** Like drainLedger but lets retry backoffs elapse (moves the clock several seconds per round). */
    public void drainLedgerFully() {
        for (int i = 0; i < 40; i++) { m.payments.processDue(100); clock.advance(3_000); }
    }

    public void script(String user, String json) { m.fakeCoin.setScript("u_" + user, json); }
    public void balance(String user, long b) { m.fakeCoin.setBalance("u_" + user, b); }
    public long walletBalance(String user) { Number n = scalar("SELECT balance FROM pe_fake_wallet WHERE user_id = ?", "u_" + user); return n == null ? 120 : n.longValue(); }

    public void parallel(int n, Consumer<Integer> task) throws Exception {
        List<Thread> ts = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        var start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            int k = i;
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try { start.await(); task.accept(k); } catch (Throwable e) { errors.add(e); }
            });
            ts.add(t); t.start();
        }
        start.countDown();
        for (Thread t : ts) t.join(Duration.ofSeconds(30));
        // a worker failure or a hung worker fails the test; tasks that expect an exception must catch it themselves
        if (ts.stream().anyMatch(Thread::isAlive)) throw new AssertionError("parallel task still running after 30 s");
        if (!errors.isEmpty()) {
            AssertionError e = new AssertionError(errors.size() + " of " + n + " parallel tasks failed", errors.get(0));
            errors.stream().skip(1).forEach(e::addSuppressed);
            throw e;
        }
    }

    @Override public void close() { ds.close(); }
}
