package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcIdempotencyRepository implements IdempotencyRepository {

    @Override public Optional<Stored> begin(String scope, String method, String path, String key, String bodyHash, String owner, Duration lease, Instant now) {
        Instant leaseUntil = now.plus(lease);
        int inserted = update("""
            INSERT INTO pe_idempotency (scope, method, path, idem_key, body_hash, status, created_at, lease_until, owner)
            VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
            ON CONFLICT DO NOTHING""", scope, method, path, key, bodyHash, now, leaseUntil, owner);
        if (inserted == 1) return Optional.empty();
        // Abandoned attempt (its lease expired): this retry takes it over. The body must
        // match; a different body keeps the row and is answered IDEMPOTENCY_MISMATCH by the caller.
        int taken = update("""
            UPDATE pe_idempotency SET lease_until = ?, owner = ?
            WHERE scope = ? AND method = ? AND path = ? AND idem_key = ? AND status = 'IN_PROGRESS' AND body_hash = ?
              AND (lease_until <= ? OR (lease_until IS NULL AND created_at <= ?))""", leaseUntil, owner, scope, method, path, key, bodyHash, now, now.minus(lease));
        if (taken == 1) return Optional.empty();
        return one("SELECT body_hash, status, response_status, response_body FROM pe_idempotency WHERE scope = ? AND method = ? AND path = ? AND idem_key = ?",
                rs -> new Stored(rs.getString(1), rs.getString(2), intOrNull(rs, "response_status"), rs.getString(4)), scope, method, path, key);
    }

    @Override public void complete(String scope, String method, String path, String key, String owner, int status, String body) {
        update("""
            UPDATE pe_idempotency SET status = 'DONE', response_status = ?, response_body = ?, lease_until = NULL
            WHERE scope = ? AND method = ? AND path = ? AND idem_key = ? AND status = 'IN_PROGRESS' AND owner = ?""",
                status, body, scope, method, path, key, owner);
    }

    @Override public void forget(String scope, String method, String path, String key, String owner) {
        update("DELETE FROM pe_idempotency WHERE scope = ? AND method = ? AND path = ? AND idem_key = ? AND status = 'IN_PROGRESS' AND owner = ?",
                scope, method, path, key, owner);
    }

    @Override public int purgeOlderThan(Instant cutoff) {
        return update("DELETE FROM pe_idempotency WHERE created_at < ?", cutoff);
    }
}
