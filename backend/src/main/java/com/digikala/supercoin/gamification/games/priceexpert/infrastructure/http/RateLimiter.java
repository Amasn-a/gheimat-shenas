package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;

import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fixed-window limiter. The limits are the handoff's PLACEHOLDERS ([10] starts a minute per user, [4] polls a
 * second per run) until load tests set real values (D10). A multi-node deployment needs a shared limiter (gateway or Redis).
 */
public final class RateLimiter {
    public record Limit(int max, long windowMs) {}

    public static final Limit START_PER_MINUTE = new Limit(10, 60_000);
    public static final Limit POLL_PER_SECOND = new Limit(4, 1_000);

    private final InstantSource clock;
    private final boolean enabled;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public RateLimiter(InstantSource clock, boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    public void check(String key, Limit limit) {
        if (!enabled) return;
        long now = clock.millis();
        long[] w = windows.compute(key, (k, v) -> {
            if (v == null || now - v[0] >= limit.windowMs()) return new long[]{now, 1};
            v[1]++;
            return v;
        });
        if (w[1] > limit.max()) throw new GameException("RATE_LIMITED", 429, "too many requests", Map.of("retry_after_ms", limit.windowMs() - (now - w[0])));
        if (windows.size() > 100_000) windows.clear();                         // bound memory
    }
}
