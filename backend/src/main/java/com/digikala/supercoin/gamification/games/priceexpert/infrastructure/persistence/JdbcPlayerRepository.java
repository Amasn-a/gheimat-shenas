package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.PlayerRepository;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcPlayerRepository implements PlayerRepository {

    @Override public Player lockOrCreatePlayer(String userId, Instant now) {
        update("INSERT INTO pe_player (user_id, created_at, updated_at) VALUES (?, ?, ?) ON CONFLICT (user_id) DO NOTHING", userId, now, now);
        return one("SELECT * FROM pe_player WHERE user_id = ? FOR UPDATE", JdbcPlayerRepository::player, userId).orElseThrow();
    }

    @Override public Optional<Player> tryLockPlayer(String userId) {
        return one("SELECT * FROM pe_player WHERE user_id = ? FOR UPDATE SKIP LOCKED", JdbcPlayerRepository::player, userId);
    }

    @Override public Optional<Player> findPlayer(String userId) {
        return one("SELECT * FROM pe_player WHERE user_id = ?", JdbcPlayerRepository::player, userId);
    }

    @Override public void updatePlayer(Player p, Instant now) {
        update("UPDATE pe_player SET record = ?, record_run_id = ?, streak_days = ?, streak_last_date = ?, onboarded = ?, updated_at = ? WHERE user_id = ?",
                p.record, nz(p.recordRunId), p.streakDays, nz(p.streakLastDate), p.onboarded, now, p.userId);
    }

    @Override public void setBlocked(String userId, boolean blocked, String actor, String reason, Instant now) {
        update("UPDATE pe_player SET blocked = ?, blocked_at = ?, blocked_by = ?, block_reason = ?, updated_at = ? WHERE user_id = ?",
                blocked, blocked ? now : NULL_TS, blocked ? actor : NULL_TEXT, blocked ? nz(reason) : NULL_TEXT, now, userId);
    }

    @Override public Participation lockParticipation(String userId, LocalDate date) {
        update("INSERT INTO pe_daily_participation (user_id, business_date) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, date);
        return one("SELECT * FROM pe_daily_participation WHERE user_id = ? AND business_date = ? FOR UPDATE",
                JdbcPlayerRepository::participation, userId, date).orElseThrow();
    }

    @Override public int playsUsed(String userId, LocalDate date) {
        return one("SELECT plays_used FROM pe_daily_participation WHERE user_id = ? AND business_date = ?", rs -> rs.getInt(1), userId, date).orElse(0);
    }

    @Override public void saveParticipation(Participation p) {
        update("UPDATE pe_daily_participation SET plays_used = ?, finished_runs = ?, streak_eligible = ? WHERE user_id = ? AND business_date = ?",
                p.playsUsed(), p.finishedRuns(), p.streakEligible(), p.userId(), p.date());
    }

    @Override public List<LocalDate> streakEligibleDates(String userId, LocalDate since) {
        return query("SELECT business_date FROM pe_daily_participation WHERE user_id = ? AND streak_eligible AND business_date >= ? ORDER BY business_date",
                rs -> date(rs, "business_date"), userId, since);
    }

    @Override public void markSeen(String userId, LocalDate showDate, long dkp, Instant now) {
        update("""
            INSERT INTO pe_seen_product (user_id, show_date, dkp, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id, show_date, dkp) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at""", userId, showDate, dkp, now, now);
    }

    static Player player(ResultSet rs) throws SQLException {
        Player p = new Player();
        p.userId = rs.getString("user_id");
        p.record = rs.getInt("record");
        p.recordRunId = rs.getString("record_run_id");
        p.streakDays = rs.getInt("streak_days");
        p.streakLastDate = date(rs, "streak_last_date");
        p.onboarded = rs.getBoolean("onboarded");
        p.blocked = rs.getBoolean("blocked");
        return p;
    }

    static Participation participation(ResultSet rs) throws SQLException {
        return new Participation(rs.getString("user_id"), date(rs, "business_date"), rs.getInt("plays_used"),
                rs.getInt("finished_runs"), rs.getBoolean("streak_eligible"));
    }
}
