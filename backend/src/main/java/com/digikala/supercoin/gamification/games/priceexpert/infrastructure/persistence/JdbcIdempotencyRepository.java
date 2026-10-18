package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository;

import java.time.Instant;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcIdempotencyRepository implements IdempotencyRepository {

    @Override public Optional<Stored> begin(String scope, String method, String path, String key, String bodyHash, Instant now) {
        int inserted = update("""
            INSERT INTO pe_idempotency (scope, method, path, idem_key, body_hash, status, created_at) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
            ON CONFLICT DO NOTHING""", scope, method, path, key, bodyHash, now);
        if (inserted == 1) return Optional.empty();
        return one("SELECT body_hash, status, response_status, response_body FROM pe_idempotency WHERE scope = ? AND method = ? AND path = ? AND idem_key = ?",
                rs -> new Stored(rs.getString(1), rs.getString(2), intOrNull(rs, "response_status"), rs.getString(4)), scope, method, path, key);
    }

    @Override public void complete(String scope, String method, String path, String key, int status, String body) {
        update("UPDATE pe_idempotency SET status = 'DONE', response_status = ?, response_body = ? WHERE scope = ? AND method = ? AND path = ? AND idem_key = ?",
                status, body, scope, method, path, key);
    }

    @Override public void forget(String scope, String method, String path, String key) {
        update("DELETE FROM pe_idempotency WHERE scope = ? AND method = ? AND path = ? AND idem_key = ? AND status = 'IN_PROGRESS'", scope, method, path, key);
    }

    @Override public int purgeOlderThan(Instant cutoff) {
        return update("DELETE FROM pe_idempotency WHERE created_at < ?", cutoff);
    }
}
