package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Handoff idempotency contract: scope (user, method, path, key), body hash, stored response, 24 h retention. */
public interface IdempotencyRepository {
    /**
     * PROPOSED: how long an IN_PROGRESS row protects a player request before a retry may take it over. Longer than any
     * player request takes (the slowest, continue, makes two 2 s provider calls), so a live request is not taken over.
     */
    Duration PLAYER_LEASE = Duration.ofSeconds(30);
    /** PROPOSED: admin writes can run long (game-off finishes every active run), so their lease is much longer. */
    Duration ADMIN_LEASE = Duration.ofMinutes(15);

    record Stored(String bodyHash, String status, Integer responseStatus, String responseBody) {}

    /**
     * Inserts an IN_PROGRESS row leased to {@code owner} until now + {@code lease}, or takes over an IN_PROGRESS row with
     * the same body whose lease has expired (its process died). A row without a lease (written before leases existed)
     * counts as leased until created_at + {@code lease}. Returns empty when {@code owner} may execute; otherwise the row.
     */
    Optional<Stored> begin(String scope, String method, String path, String key, String bodyHash, String owner, Duration lease, Instant now);
    /** Stores the response; only the attempt that holds the row ({@code owner}) can complete it. */
    void complete(String scope, String method, String path, String key, String owner, int status, String body);
    /** Releases an IN_PROGRESS row after a failure; only its owner can release it. */
    void forget(String scope, String method, String path, String key, String owner);
    int purgeOlderThan(Instant cutoff);
}
