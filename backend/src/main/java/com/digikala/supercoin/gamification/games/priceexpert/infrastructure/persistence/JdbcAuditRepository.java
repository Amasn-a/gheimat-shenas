package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AuditRepository;

import java.time.Instant;
import java.util.List;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcAuditRepository implements AuditRepository {

    @Override public void append(String actor, String role, String action, String target, String beforeJson, String afterJson,
                                 String reason, String correlationId, Instant at) {
        update("""
            INSERT INTO pe_audit_event (actor, actor_role, action, target, before_value, after_value, reason, correlation_id, at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""",
            actor, role, action, nz(target), nz(beforeJson), nz(afterJson), nz(reason), nz(correlationId), at);
    }

    @Override public List<AuditEvent> list(String area, String actor, boolean changesOnly, Instant from, Instant to, int limit, long beforeId) {
        return query("""
            SELECT * FROM pe_audit_event
            WHERE (?::text IS NULL OR action LIKE ? || '%') AND (?::text IS NULL OR actor = ?)
              AND (?::timestamptz IS NULL OR at >= ?) AND (?::timestamptz IS NULL OR at < ?) AND id < ?
              AND (NOT ? OR (action NOT LIKE 'admin.read.%' AND action <> 'config.draft'))
            ORDER BY id DESC LIMIT ?""",
            rs -> new AuditEvent(rs.getLong("id"), rs.getString("actor"), rs.getString("actor_role"), rs.getString("action"),
                    rs.getString("target"), rs.getString("before_value"), rs.getString("after_value"), rs.getString("reason"),
                    rs.getString("correlation_id"), ts(rs, "at")),
            nz(area), nz(area), nz(actor), nz(actor), nz(from), nz(from), nz(to), nz(to), beforeId <= 0 ? Long.MAX_VALUE : beforeId, changesOnly, limit);
    }
}
