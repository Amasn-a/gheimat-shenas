package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.util.function.Supplier;

/**
 * Runs work in one database transaction (READ COMMITTED + explicit row locks). Transient serialization or
 * deadlock failures are retried a bounded number of times, so work passed here must not call external services.
 * In supercoin this is Spring's TransactionTemplate.
 */
public interface TransactionRunner {
    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) { inTransaction(() -> { work.run(); return null; }); }

    /** Runs the callback after the current transaction commits (events, notifications); dropped on rollback. */
    void afterCommit(Runnable callback);
}
