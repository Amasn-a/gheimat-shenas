package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.time.Instant;
import java.util.List;

/** Append-only admin audit (the table rejects UPDATE/DELETE). */
public interface AuditRepository {
    record AuditEvent(long id, String actor, String actorRole, String action, String target, String beforeJson,
                      String afterJson, String reason, String correlationId, Instant at) {}

    void append(String actor, String role, String action, String target, String beforeJson, String afterJson,
                String reason, String correlationId, Instant at);

    /** {@code changesOnly} leaves out read-access and draft-save events (filtered before the page is cut). */
    List<AuditEvent> list(String area, String actor, boolean changesOnly, Instant from, Instant to, int limit, long beforeId);
}
