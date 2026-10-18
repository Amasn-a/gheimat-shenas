package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** Handoff idempotency contract: scope (user, method, path, key), body hash, stored response, 24 h retention. */
public interface IdempotencyRepository {
    record Stored(String bodyHash, String status, Integer responseStatus, String responseBody) {}

    /** Inserts an IN_PROGRESS row; returns the existing row when the key is already known. */
    Optional<Stored> begin(String scope, String method, String path, String key, String bodyHash, Instant now);
    void complete(String scope, String method, String path, String key, int status, String body);
    void forget(String scope, String method, String path, String key);
    int purgeOlderThan(Instant cutoff);
}
