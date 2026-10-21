package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Applies versioned SQL migrations from {@code db/migration/V<n>__*.sql} in order, recording them in
 * {@code pe_schema_history}. Flyway is the intended tool in supercoin (production DDL is applied out-of-band);
 * this runner exists because the offline build here has no Flyway artefact. The file names are Flyway-compatible.
 */
public final class Migrator {
    private static final Logger LOG = Logger.getLogger(Migrator.class.getName());
    public static final List<String> MIGRATIONS = List.of("V1__price_expert_schema.sql", "V2__idempotency_lease.sql", "V3__active_run_scan.sql",
            "V4__timing_contract_daily_claim.sql", "V5__redesign_rewards.sql");

    private Migrator() {}

    public static void migrate(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS pe_schema_history (version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            st.execute("SELECT pg_advisory_lock(918273645)");
            try {
                for (String m : MIGRATIONS) {
                    String version = m.substring(0, m.indexOf("__"));
                    try (var rs = st.executeQuery("SELECT 1 FROM pe_schema_history WHERE version = '" + version + "'")) {
                        if (rs.next()) continue;
                    }
                    String sql = read("/db/migration/" + m);
                    c.setAutoCommit(false);
                    try (Statement s2 = c.createStatement()) {
                        s2.execute(sql);
                        s2.execute("INSERT INTO pe_schema_history(version) VALUES ('" + version + "')");
                        c.commit();
                        LOG.info("applied migration " + m);
                    } catch (SQLException e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(true);
                    }
                }
            } finally {
                st.execute("SELECT pg_advisory_unlock(918273645)");
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("migration failed: " + e.getMessage(), e);
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = Migrator.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
