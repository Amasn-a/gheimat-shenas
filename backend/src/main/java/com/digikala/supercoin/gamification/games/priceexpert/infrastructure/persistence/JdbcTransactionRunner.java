package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.TransactionRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * READ COMMITTED transactions with explicit row locks. Joins an outer transaction when nested. Retries transient
 * serialization/deadlock failures (SQLState 40001/40P01) up to three times; the work must therefore be free of
 * external side effects, which every caller guarantees (external calls happen between transactions).
 */
public final class JdbcTransactionRunner implements TransactionRunner {
    private static final Logger LOG = Logger.getLogger(JdbcTransactionRunner.class.getName());
    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<java.util.List<Runnable>> AFTER_COMMIT = new ThreadLocal<>();
    private final DataSource dataSource;

    public JdbcTransactionRunner(DataSource dataSource) { this.dataSource = dataSource; }

    static Connection current() {
        Connection c = CURRENT.get();
        if (c == null) throw new IllegalStateException("no transaction bound to this thread");
        return c;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (CURRENT.get() != null) return work.get();
        for (int attempt = 1; ; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                CURRENT.set(c);
                AFTER_COMMIT.set(new java.util.ArrayList<>());
                try {
                    T result = work.get();
                    c.commit();
                    java.util.List<Runnable> callbacks = AFTER_COMMIT.get();
                    AFTER_COMMIT.remove();
                    CURRENT.remove();
                    for (Runnable r : callbacks) {
                        try { r.run(); } catch (RuntimeException ex) { LOG.warning("after-commit callback failed: " + ex); }
                    }
                    return result;
                } catch (RuntimeException | Error e) {
                    safeRollback(c);
                    if (attempt < 4 && isTransient(e)) {
                        LOG.fine(() -> "retrying transient transaction failure: " + e.getMessage());
                        continue;
                    }
                    throw e;
                } finally {
                    CURRENT.remove();
                    AFTER_COMMIT.remove();
                }
            } catch (SQLException e) {
                throw new Sql.DataAccessException("connection", e);
            }
        }
    }

    @Override
    public void afterCommit(Runnable callback) {
        java.util.List<Runnable> l = AFTER_COMMIT.get();
        if (l == null) callback.run(); else l.add(callback);
    }

    private static void safeRollback(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    private static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Sql.DataAccessException d && ("40001".equals(d.sqlState()) || "40P01".equals(d.sqlState()))) return true;
            if (t instanceof SQLException s && ("40001".equals(s.getSQLState()) || "40P01".equals(s.getSQLState()))) return true;
        }
        return false;
    }
}
