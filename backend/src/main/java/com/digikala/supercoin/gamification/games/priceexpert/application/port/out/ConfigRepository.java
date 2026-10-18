package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConfigRepository {
    record Settings(Integer activeConfigVersion, Integer activeCatalogVersion, boolean gameEnabled, String playerMessage,
                    int revision, String updatedBy, Instant updatedAt) {}

    record StoredConfig(int version, String status, GameConfig config, Integer basedOn, String createdBy, Instant createdAt,
                        String publishedBy, Instant publishedAt) {}

    Settings settings();
    Settings lockSettings();
    void saveSettings(Settings s);

    /** Immutable once published; implementations may cache published versions. */
    Optional<StoredConfig> version(int version);
    int insertDraft(GameConfig config, Integer basedOn, String actor, Instant now);
    void updateDraft(int version, GameConfig config);
    void markPublished(int version, String actor, Instant now);
    void retire(int version);
    List<StoredConfig> versions(int limit);
}
