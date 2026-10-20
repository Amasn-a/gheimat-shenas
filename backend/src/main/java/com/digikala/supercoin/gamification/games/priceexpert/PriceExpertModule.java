package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.*;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.ConfigValidator;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.auth.DevPlayerAuthenticator;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.campaign.FakeCampaignCodeProvider;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.FakeCoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.TimeLimitedCoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.UnconfiguredCoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.AesCodeCipher;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.LogEventSink;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Composition root (the Spring configuration in supercoin). Production refuses every development stand-in, so a
 * production start fails until the real Super App verifier, DigiCoin adapter, code source and keys exist (R-44).
 */
public final class PriceExpertModule {

    public final Map<String, String> env;
    public final boolean production;
    public final PriceExpertContext ctx;
    public final FakeCoinGateway fakeCoin;                  // null unless the fake provider is active
    public final PlayerAuthenticator auth;
    public final SelectionService selection;
    public final SettlementService settlement;
    public final DeadlineService deadlines;
    public final PaymentService payments;
    public final ViewAssembler views;
    public final RunService runs;
    public final QueryService queries;
    public final DailyRewardService dailyRewards;
    public final CampaignService campaigns;
    public final CatalogService catalog;
    public final AdminService admin;

    public PriceExpertModule(Map<String, String> env, DataSource ds, InstantSource clock, RandomGenerator random) {
        this.env = env;
        this.production = "production".equals(env.getOrDefault("PE_ENV", "development"));
        TransactionRunner tx = new JdbcTransactionRunner(ds);

        String coinMode = env.getOrDefault("PE_COIN_PROVIDER", production ? "unconfigured" : "fake");
        CoinGateway rawCoin;
        if (coinMode.equals("fake")) {
            fakeCoin = new FakeCoinGateway(new JdbcTransactionRunner(ds), clock, Long.parseLong(env.getOrDefault("PE_FAKE_START_BALANCE", "120")));
            rawCoin = fakeCoin;
        } else {
            fakeCoin = null;
            rawCoin = new UnconfiguredCoinGateway();
        }
        CoinGateway coin = new TimeLimitedCoinGateway(rawCoin, Duration.ofMillis(Long.parseLong(env.getOrDefault("PE_COIN_TIMEOUT_MS", "2000"))));
        CampaignCodeProvider codes = new FakeCampaignCodeProvider(clock);
        this.auth = new DevPlayerAuthenticator();
        CodeCipher cipher = new AesCodeCipher(codeKey(env, production));

        if (production) {
            StringBuilder refused = new StringBuilder();
            if (coin.isFake() || rawCoin instanceof UnconfiguredCoinGateway) refused.append(" coin-provider(not integrated)");
            if (codes.isFake()) refused.append(" campaign-code-provider(fake)");
            if (auth.isDevelopmentOnly()) refused.append(" player-authenticator(dev)");
            if (refused.length() > 0)
                throw new IllegalStateException("production start refused; development stand-ins present:" + refused);
        }

        ConfigValidator.Bounds bounds = new ConfigValidator.Bounds(intOrNull(env, "PE_BOUND_MAX_PLAYS_PER_DAY"), intOrNull(env, "PE_BOUND_MAX_CHECKPOINT_REWARD"),
                intOrNull(env, "PE_BOUND_MAX_CONTINUE_COST"), intOrNull(env, "PE_BOUND_MAX_CONTINUE_CAP"), intOrNull(env, "PE_BOUND_MAX_STREAK_REWARD"));

        this.ctx = new PriceExpertContext(tx, new JdbcRunRepository(), new JdbcPlayerRepository(), new JdbcLedgerRepository(),
                new JdbcCatalogRepository(), new JdbcConfigRepository(), new JdbcCampaignRepository(), new JdbcIdempotencyRepository(),
                new JdbcAuditRepository(), coin, codes, cipher, new LogEventSink(), clock, random, bounds);
        this.selection = new SelectionService(ctx);
        this.settlement = new SettlementService(ctx);
        this.deadlines = new DeadlineService(ctx, settlement);
        this.payments = new PaymentService(ctx, settlement, selection);
        this.views = new ViewAssembler(ctx);
        this.runs = new RunService(ctx, selection, settlement, deadlines, payments, views);
        this.dailyRewards = new DailyRewardService(ctx, payments);
        this.queries = new QueryService(ctx, runs, views, dailyRewards);
        this.campaigns = new CampaignService(ctx);
        this.catalog = new CatalogService(ctx);
        this.admin = new AdminService(ctx, settlement, deadlines);
    }

    private static byte[] codeKey(Map<String, String> env, boolean production) {
        String k = env.get("PE_CODE_KEY");
        if (k != null) return Base64.getDecoder().decode(k);
        if (production) throw new IllegalStateException("PE_CODE_KEY is required in production");
        try {   // development-only key, derived from a fixed label; never used in production
            return MessageDigest.getInstance("SHA-256").digest("price-expert development code key".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Integer intOrNull(Map<String, String> env, String k) {
        String v = env.get(k);
        return v == null || v.isBlank() ? null : Integer.valueOf(v);
    }
}
