package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.EventSink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Structured log line per event. Replace with the approved telemetry stack; names marked draft in the handoff. */
public final class LogEventSink implements EventSink {
    private static final Logger LOG = Logger.getLogger("price-expert.events");

    @Override public void emit(String name, String eventId, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", name);
        m.put("event_id", eventId);
        m.putAll(fields);
        LOG.info(Json.write(m));
    }
}
