package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.ConfigRepository;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcConfigRepository implements ConfigRepository {
    /** Published versions are immutable, so they are cached forever; drafts are always read from the database. */
    private final Map<Integer, StoredConfig> published = new ConcurrentHashMap<>();

    @Override public Settings settings() {
        return one("SELECT * FROM pe_game_settings WHERE id = 1", JdbcConfigRepository::settings).orElseThrow();
    }

    @Override public Settings lockSettings() {
        return one("SELECT * FROM pe_game_settings WHERE id = 1 FOR UPDATE", JdbcConfigRepository::settings).orElseThrow();
    }

    @Override public void saveSettings(Settings s) {
        update("""
            UPDATE pe_game_settings SET active_config_version = ?, active_catalog_version = ?, game_enabled = ?, player_message = ?,
              revision = ?, updated_by = ?, updated_at = ? WHERE id = 1""",
            nz(s.activeConfigVersion()), nz(s.activeCatalogVersion()), s.gameEnabled(), nz(s.playerMessage()), s.revision(),
            nz(s.updatedBy()), nz(s.updatedAt()));
    }

    @Override public Optional<StoredConfig> version(int version) {
        StoredConfig cached = published.get(version);
        if (cached != null) return Optional.of(cached);
        Optional<StoredConfig> c = one("SELECT * FROM pe_config_version WHERE version = ?", JdbcConfigRepository::stored, version);
        c.filter(s -> !"DRAFT".equals(s.status())).ifPresent(s -> published.put(version, s));
        return c;
    }

    @Override public int insertDraft(GameConfig config, Integer basedOn, String actor, Instant now) {
        int v = one("SELECT COALESCE(MAX(version), 0) + 1 FROM pe_config_version", rs -> rs.getInt(1)).orElse(1);
        update("INSERT INTO pe_config_version (version, status, payload, based_on, created_by, created_at) VALUES (?, 'DRAFT', ?::jsonb, ?, ?, ?)",
                v, Json.write(config), nz(basedOn), actor, now);
        return v;
    }

    @Override public void updateDraft(int version, GameConfig config) {
        int n = update("UPDATE pe_config_version SET payload = ?::jsonb WHERE version = ? AND status = 'DRAFT'", Json.write(config), version);
        if (n != 1) throw new IllegalStateException("config version " + version + " is not a draft");
    }

    @Override public void markPublished(int version, String actor, Instant now) {
        int n = update("UPDATE pe_config_version SET status = 'PUBLISHED', published_by = ?, published_at = ? WHERE version = ? AND status = 'DRAFT'",
                actor, now, version);
        if (n != 1) throw new IllegalStateException("config version " + version + " is not a draft");
    }

    @Override public void retire(int version) {
        // a superseded published version, or a discarded draft
        update("UPDATE pe_config_version SET status = 'RETIRED' WHERE version = ? AND status IN ('PUBLISHED', 'DRAFT')", version);
        published.remove(version);
    }

    @Override public List<StoredConfig> versions(int limit) {
        return query("SELECT * FROM pe_config_version ORDER BY version DESC LIMIT ?", JdbcConfigRepository::stored, limit);
    }

    static Settings settings(ResultSet rs) throws SQLException {
        return new Settings(intOrNull(rs, "active_config_version"), intOrNull(rs, "active_catalog_version"), rs.getBoolean("game_enabled"),
                rs.getString("player_message"), rs.getInt("revision"), rs.getString("updated_by"), ts(rs, "updated_at"));
    }

    static StoredConfig stored(ResultSet rs) throws SQLException {
        return new StoredConfig(rs.getInt("version"), rs.getString("status"), Json.read(rs.getString("payload"), GameConfig.class),
                intOrNull(rs, "based_on"), rs.getString("created_by"), ts(rs, "created_at"), rs.getString("published_by"), ts(rs, "published_at"));
    }
}
