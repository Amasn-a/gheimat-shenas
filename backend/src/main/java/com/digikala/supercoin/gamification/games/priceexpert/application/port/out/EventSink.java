package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.util.Map;

/**
 * Server-side product events (spec «رویدادها و سنجش محصول»). Names not in the spec are drafts for the analytics
 * team. Events are emitted after commit, carry an eventId for de-duplication and never include hidden prices,
 * codes or tokens. The approved telemetry stack is not known here; the default sink writes structured log lines.
 */
public interface EventSink {
    void emit(String name, String eventId, Map<String, Object> fields);
}
