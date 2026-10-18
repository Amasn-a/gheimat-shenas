package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AdminRepository;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

/** Admin reads never select hidden data of an OPEN question: comparison prices are shown only for settled questions. */
public final class JdbcAdminRepository implements AdminRepository {

    @Override public Optional<Account> account(String username) {
        return one("SELECT * FROM pe_admin_user WHERE username = ?", rs -> new Account(rs.getString("username"), rs.getString("display_name"),
                rs.getString("role"), rs.getString("password_hash"), rs.getBoolean("active")), username);
    }

    @Override public void upsertAccount(Account a) {
        update("""
            INSERT INTO pe_admin_user (username, display_name, role, password_hash, active) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (username) DO UPDATE SET display_name = EXCLUDED.display_name, role = EXCLUDED.role,
              password_hash = EXCLUDED.password_hash, active = EXCLUDED.active""",
            a.username(), a.displayName(), a.role(), a.passwordHash(), a.active());
    }

    @Override public void insertSession(String tokenHash, String username, String role, Instant now, Instant expiresAt) {
        update("INSERT INTO pe_admin_session (token_hash, username, role, created_at, expires_at) VALUES (?, ?, ?, ?, ?)", tokenHash, username, role, now, expiresAt);
    }

    @Override public Optional<Session> session(String tokenHash, Instant now) {
        return one("""
            SELECT s.username, s.role, s.expires_at FROM pe_admin_session s JOIN pe_admin_user u ON u.username = s.username
            WHERE s.token_hash = ? AND s.expires_at > ? AND u.active""",
            rs -> new Session(rs.getString(1), rs.getString(2), ts(rs, "expires_at")), tokenHash, now);
    }

    @Override public void deleteSession(String tokenHash) { update("DELETE FROM pe_admin_session WHERE token_hash = ?", tokenHash); }

    @Override public void insertReview(String id, String userId, String runId, String outcome, String note, String actor, Instant at) {
        update("INSERT INTO pe_review (review_id, user_id, run_id, outcome, note, actor, at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, userId, nz(runId), outcome, nz(note), actor, at);
    }

    @Override public List<Map<String, Object>> reviews(String userId) {
        return query("SELECT review_id, run_id, outcome, note, actor, at FROM pe_review WHERE user_id = ? ORDER BY at DESC LIMIT 50", JdbcAdminRepository::row, userId);
    }

    @Override public List<Map<String, Object>> runs(String userId, String runId, int limit) {
        return query("""
            SELECT run_id, user_id, device_id, state, finish_reason, no_offer_reason, score, continues_used, highest_checkpoint_id,
                   config_version, catalog_version, start_business_date, started_at, finished_at, campaign_id, campaign_outcome
            FROM pe_run WHERE (?::text IS NULL OR user_id = ?) AND (?::text IS NULL OR run_id = ?)
            ORDER BY started_at DESC LIMIT ?""", JdbcAdminRepository::row, nz(userId), nz(userId), nz(runId), nz(runId), limit);
    }

    @Override public List<Map<String, Object>> operations(String userId, String runId, String status, int limit) {
        return query("""
            SELECT operation_id, user_id, run_id, offer_id, type, amount, held_amount, status, cap_day, related_operation_id,
                   provider_ref, attempts, last_error, created_at, executed_at, confirmed_at
            FROM pe_coin_operation WHERE (?::text IS NULL OR user_id = ?) AND (?::text IS NULL OR run_id = ?) AND (?::text IS NULL OR status = ?)
            ORDER BY created_at DESC LIMIT ?""", JdbcAdminRepository::row, nz(userId), nz(userId), nz(runId), nz(runId), nz(status), nz(status), limit);
    }

    @Override public Map<String, Object> player(String userId) {
        return one("SELECT user_id, record, streak_days, streak_last_date, blocked, blocked_at, blocked_by, block_reason, created_at FROM pe_player WHERE user_id = ?",
                JdbcAdminRepository::row, userId).orElse(Map.of());
    }

    /** A05 signals (S-1/S-3). Information only: flags never hold rewards (R-60). */
    @Override public Map<String, Object> abuseSignals(String userId) {
        Map<String, Object> m = new LinkedHashMap<>(one("""
            SELECT count(*) AS answers,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (q.received_at - q.released_at)) * 1000) AS median_ms_from_t0,
                   avg(CASE WHEN q.received_at - q.released_at < interval '600 milliseconds' THEN 1 ELSE 0 END) AS share_under_600ms,
                   avg(CASE WHEN q.received_at - q.released_at >= interval '10500 milliseconds' THEN 1 ELSE 0 END) AS share_10_5_to_11s
            FROM pe_question q JOIN pe_run r ON r.run_id = q.run_id
            WHERE r.user_id = ? AND q.received_at IS NOT NULL AND q.released_at > now() - interval '30 days'""", JdbcAdminRepository::row, userId).orElse(Map.of()));
        m.put("accuracy_by_band", query("""
            SELECT q.band_index, count(*) AS answered, avg(CASE WHEN q.state = 'CORRECT' THEN 1 ELSE 0 END) AS accuracy
            FROM pe_question q JOIN pe_run r ON r.run_id = q.run_id
            WHERE r.user_id = ? AND q.state IN ('CORRECT','WRONG','TIMED_OUT') GROUP BY q.band_index ORDER BY q.band_index""", JdbcAdminRepository::row, userId));
        m.put("devices", one("SELECT count(DISTINCT device_id) AS devices FROM pe_run WHERE user_id = ?", rs -> rs.getLong(1), userId).orElse(0L));
        m.put("accounts_on_same_devices", one("""
            SELECT count(DISTINCT r2.user_id) - 1 FROM pe_run r1 JOIN pe_run r2 ON r2.device_id = r1.device_id WHERE r1.user_id = ?""",
            rs -> rs.getLong(1), userId).orElse(0L));
        m.put("runs_ending_right_after_cp10", one("""
            SELECT count(*) FROM pe_run WHERE user_id = ? AND state = 'FINISHED' AND finish_reason IN ('wrong','timeout','skipped','expired')
            AND highest_checkpoint_id = 'cp_10' AND score = 10""", rs -> rs.getLong(1), userId).orElse(0L));
        Object median = m.get("median_ms_from_t0");
        Object fast = m.get("share_under_600ms");
        StringBuilder reasons = new StringBuilder();
        if (fast instanceof Number n && n.doubleValue() > 0.3) reasons.append("many answers under 600 ms; ");
        if (median instanceof Number n && n.doubleValue() < 900) reasons.append("median answer time under 0.9 s; ");
        if (m.get("accounts_on_same_devices") instanceof Number n && n.longValue() > 2) reasons.append("device shared by several accounts; ");
        m.put("risk_flag", reasons.length() > 0);
        m.put("risk_reasons", reasons.toString().trim());
        m.put("flag_policy", "information only; a flagged account is paid normally until an Editor blocks it (R-60). Thresholds are proposals.");
        return m;
    }

    @Override public List<Map<String, Object>> alerts(int limit) {
        return query("SELECT id, kind, details::text AS details, created_at FROM pe_alert ORDER BY id DESC LIMIT ?", JdbcAdminRepository::row, limit);
    }

    @Override public Map<String, Object> openCounts() {
        return one("""
            SELECT (SELECT count(*) FROM pe_run WHERE state <> 'FINISHED') AS open_runs,
                   (SELECT count(*) FROM pe_run WHERE state = 'PAYMENT_PENDING') AS payments_pending,
                   (SELECT count(*) FROM pe_coin_operation WHERE status IN ('PENDING','UNKNOWN')) AS operations_in_flight,
                   (SELECT count(*) FROM pe_coin_operation WHERE status = 'UNKNOWN') AS operations_unknown,
                   (SELECT count(*) FROM pe_coin_operation WHERE status = 'NEEDS_REVIEW') AS operations_needing_review,
                   (SELECT count(*) FROM pe_coin_operation WHERE status = 'HELD' OR held_amount > 0) AS credits_held,
                   (SELECT count(*) FROM pe_coin_operation WHERE type = 'REFUND') AS refunds,
                   (SELECT coalesce(min(created_at), now()) FROM pe_coin_operation WHERE status IN ('PENDING','UNKNOWN')) AS oldest_in_flight,
                   (SELECT count(*) FROM pe_campaign_entitlement WHERE status = 'PENDING') AS codes_pending""", JdbcAdminRepository::row).orElse(Map.of());
    }

    @Override public List<Long> activeCatalogPrices(int catalogVersion) {
        return query("SELECT price_toman FROM pe_catalog_product WHERE catalog_version = ? AND eligible ORDER BY price_toman", rs -> rs.getLong(1), catalogVersion);
    }

    static Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp t) v = t.toInstant();
            else if (v instanceof OffsetDateTime o) v = o.toInstant();
            else if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            else if (v instanceof java.math.BigDecimal b) v = b.doubleValue();
            m.put(md.getColumnLabel(i), v);
        }
        return m;
    }
}
