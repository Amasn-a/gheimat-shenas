package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.LedgerRepository;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CoinOperation;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationStatus;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcLedgerRepository implements LedgerRepository {

    @Override public void insertOperation(CoinOperation o) {
        update("""
            INSERT INTO pe_coin_operation (operation_id, user_id, run_id, offer_id, type, amount, held_amount, status, cap_day,
              related_operation_id, provider_ref, attempts, next_attempt_at, give_up_at, last_error, created_at, updated_at, executed_at, confirmed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            o.operationId, o.userId, nz(o.runId), nz(o.offerId), o.type, o.amount, o.heldAmount, o.status, nz(o.capDay),
            nz(o.relatedOperationId), nz(o.providerRef), o.attempts, nz(o.nextAttemptAt), nz(o.giveUpAt), nz(o.lastError),
            o.createdAt, o.createdAt, nz(o.executedAt), nz(o.confirmedAt));
    }

    @Override public Optional<CoinOperation> findOperation(String id) {
        return one("SELECT * FROM pe_coin_operation WHERE operation_id = ?", JdbcLedgerRepository::op, id);
    }

    @Override public Optional<CoinOperation> lockOperation(String id) {
        return one("SELECT * FROM pe_coin_operation WHERE operation_id = ? FOR UPDATE", JdbcLedgerRepository::op, id);
    }

    @Override public void updateOperation(CoinOperation o) {
        update("""
            UPDATE pe_coin_operation SET status = ?, amount = ?, held_amount = ?, provider_ref = ?, attempts = ?, next_attempt_at = ?,
              give_up_at = ?, last_error = ?, updated_at = now(), executed_at = ?, confirmed_at = ? WHERE operation_id = ?""",
            o.status, o.amount, o.heldAmount, nz(o.providerRef), o.attempts, nz(o.nextAttemptAt), nz(o.giveUpAt), nz(o.lastError),
            nz(o.executedAt), nz(o.confirmedAt), o.operationId);
    }

    @Override public List<String> dueOperationIds(Instant now, int limit) {
        return query("SELECT operation_id FROM pe_coin_operation WHERE status IN ('PENDING','UNKNOWN') AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?",
                rs -> rs.getString(1), now, limit);
    }

    @Override public List<CoinOperation> operationsOfRun(String runId) {
        return query("SELECT * FROM pe_coin_operation WHERE run_id = ? ORDER BY created_at", JdbcLedgerRepository::op, runId);
    }

    @Override public Optional<CoinOperation> refundOf(String chargeOperationId) {
        return one("SELECT * FROM pe_coin_operation WHERE type = 'REFUND' AND related_operation_id = ?", JdbcLedgerRepository::op, chargeOperationId);
    }

    @Override public Optional<CoinOperation> streakRewardOf(String userId, LocalDate capDay) {
        return one("SELECT * FROM pe_coin_operation WHERE type = 'STREAK_REWARD' AND user_id = ? AND cap_day = ?", JdbcLedgerRepository::op, userId, capDay);
    }

    @Override public int capHighWater(LocalDate date, int currentCap, Instant now) {
        update("INSERT INTO pe_daily_cap (business_date, cap_high_water, updated_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", date, currentCap, now);
        return one("SELECT cap_high_water FROM pe_daily_cap WHERE business_date = ?", rs -> rs.getInt(1), date).orElseThrow();
    }

    @Override public void raiseCapHighWater(LocalDate date, int cap, Instant now) {
        update("""
            INSERT INTO pe_daily_cap (business_date, cap_high_water, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (business_date) DO UPDATE SET cap_high_water = GREATEST(pe_daily_cap.cap_high_water, EXCLUDED.cap_high_water),
              updated_at = EXCLUDED.updated_at""", date, cap, now);
    }

    @Override public DailyCredit lockDailyCredit(String userId, LocalDate capDay) {
        update("INSERT INTO pe_daily_credit (user_id, cap_day) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, capDay);
        return one("SELECT credited, held FROM pe_daily_credit WHERE user_id = ? AND cap_day = ? FOR UPDATE",
                rs -> new DailyCredit(rs.getInt(1), rs.getInt(2)), userId, capDay).orElseThrow();
    }

    @Override public void saveDailyCredit(String userId, LocalDate capDay, DailyCredit c) {
        update("UPDATE pe_daily_credit SET credited = ?, held = ? WHERE user_id = ? AND cap_day = ?", c.credited(), c.held(), userId, capDay);
    }

    @Override public long addPayout(LocalDate date, long amount) {
        return one("""
            INSERT INTO pe_daily_payout (business_date, paid) VALUES (?, ?)
            ON CONFLICT (business_date) DO UPDATE SET paid = pe_daily_payout.paid + EXCLUDED.paid RETURNING paid""",
            rs -> rs.getLong(1), date, amount).orElseThrow();
    }

    @Override public boolean claimPayoutAlert(LocalDate date) {
        return update("UPDATE pe_daily_payout SET alert_raised = TRUE WHERE business_date = ? AND NOT alert_raised", date) == 1;
    }

    @Override public void alert(String kind, String dedupeKey, String detailsJson, Instant now) {
        update("INSERT INTO pe_alert (kind, dedupe_key, details, created_at) VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (dedupe_key) DO NOTHING",
                kind, nz(dedupeKey), detailsJson, now);
    }

    static CoinOperation op(ResultSet rs) throws SQLException {
        CoinOperation o = new CoinOperation();
        o.operationId = rs.getString("operation_id");
        o.userId = rs.getString("user_id");
        o.runId = rs.getString("run_id");
        o.offerId = rs.getString("offer_id");
        o.type = OperationType.valueOf(rs.getString("type"));
        o.amount = rs.getInt("amount");
        o.heldAmount = rs.getInt("held_amount");
        o.status = OperationStatus.valueOf(rs.getString("status"));
        o.capDay = date(rs, "cap_day");
        o.relatedOperationId = rs.getString("related_operation_id");
        o.providerRef = rs.getString("provider_ref");
        o.attempts = rs.getInt("attempts");
        o.nextAttemptAt = ts(rs, "next_attempt_at");
        o.giveUpAt = ts(rs, "give_up_at");
        o.lastError = rs.getString("last_error");
        o.createdAt = ts(rs, "created_at");
        o.executedAt = ts(rs, "executed_at");
        o.confirmedAt = ts(rs, "confirmed_at");
        return o;
    }
}
