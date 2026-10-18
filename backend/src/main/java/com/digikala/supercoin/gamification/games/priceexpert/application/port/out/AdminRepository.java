package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Standalone admin: accounts and sessions (development sign-in), reviews, alerts and read-only search. */
public interface AdminRepository {
    record Account(String username, String displayName, String role, String passwordHash, boolean active) {}
    record Session(String username, String role, Instant expiresAt) {}

    Optional<Account> account(String username);
    void upsertAccount(Account a);
    void insertSession(String tokenHash, String username, String role, Instant now, Instant expiresAt);
    Optional<Session> session(String tokenHash, Instant now);
    void deleteSession(String tokenHash);

    void insertReview(String reviewId, String userId, String runId, String outcome, String note, String actor, Instant at);
    List<Map<String, Object>> reviews(String userId);

    List<Map<String, Object>> runs(String userId, String runId, int limit);
    List<Map<String, Object>> operations(String userId, String runId, String status, int limit);
    Map<String, Object> player(String userId);
    Map<String, Object> abuseSignals(String userId);
    List<Map<String, Object>> alerts(int limit);
    Map<String, Object> openCounts();
    List<Long> activeCatalogPrices(int catalogVersion);
}
