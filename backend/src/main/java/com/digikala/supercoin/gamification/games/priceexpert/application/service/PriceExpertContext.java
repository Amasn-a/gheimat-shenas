package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.ConfigValidator;

import java.time.Instant;
import java.time.InstantSource;
import java.util.random.RandomGenerator;

/** Dependencies shared by the use cases (in supercoin these are Spring beans injected by constructor). */
public record PriceExpertContext(
        TransactionRunner tx,
        RunRepository runs,
        PlayerRepository players,
        LedgerRepository ledger,
        CatalogRepository catalog,
        ConfigRepository configs,
        CampaignRepository campaigns,
        IdempotencyRepository idempotency,
        AuditRepository audit,
        CoinGateway coin,
        CampaignCodeProvider codes,
        CodeCipher cipher,
        EventSink events,
        InstantSource clock,
        RandomGenerator random,
        ConfigValidator.Bounds bounds) {

    public Instant now() { return clock.instant(); }

    /** A pinned, immutable configuration version. */
    public GameConfig config(int version) {
        return configs.version(version).orElseThrow(() -> new IllegalStateException("missing config version " + version)).config();
    }

    public ConfigRepository.Settings settings() { return configs.settings(); }

    public int activeConfigVersion() {
        Integer v = settings().activeConfigVersion();
        if (v == null) throw new GameException("GAME_UNAVAILABLE", 409, "no published configuration", null);
        return v;
    }

    public int activeCatalogVersion() {
        Integer v = settings().activeCatalogVersion();
        if (v == null) throw new GameException("GAME_UNAVAILABLE", 409, "no published catalog", null);
        return v;
    }
}
