package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.ProductDatabaseReader;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http.*;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.JdbcAdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.JdbcIdempotencyRepository;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Migrator;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.worker.Workers;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.*;
import java.util.logging.Logger;

/**
 * Standalone entry point for development and tests (in supercoin the Spring Boot application hosts the module).
 * Environment variables are documented in docs/SETUP.md and .env.example.
 */
public final class PriceExpertServer {
    private static final Logger LOG = Logger.getLogger(PriceExpertServer.class.getName());

    public final PriceExpertModule module;
    public final HttpServerAdapter http;
    public final Workers workers;
    public final HikariDataSource dataSource;

    public PriceExpertServer(Map<String, String> env, InstantSource clock, boolean startWorkers) throws Exception {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(env.getOrDefault("PE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/price_expert"));
        hc.setUsername(env.getOrDefault("PE_DB_USER", "pe"));
        hc.setPassword(env.getOrDefault("PE_DB_PASSWORD", "pe"));
        hc.setMaximumPoolSize(Integer.parseInt(env.getOrDefault("PE_DB_POOL", "20")));
        hc.setAutoCommit(true);
        dataSource = new HikariDataSource(hc);
        Migrator.migrate(dataSource);

        module = new PriceExpertModule(env, dataSource, clock, new SecureRandom());
        JdbcAdminRepository adminRepo = new JdbcAdminRepository();
        module.admin.bind(adminRepo, Json::write, PriceExpertServer::flatten);
        module.admin.ensureLaunchConfig();
        seedCatalog(env);
        if (!module.production) seedDevAdmins(env, adminRepo);

        String csp = env.getOrDefault("PE_CSP", "default-src 'self'; img-src 'self' https://dkstatics-public.digikala.com data: blob:; "
                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; script-src 'self'; "
                + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        http = new HttpServerAdapter(clock, csp);
        RateLimiter limiter = new RateLimiter(clock, !"off".equals(env.get("PE_RATE_LIMIT")));
        JdbcIdempotencyRepository idem = new JdbcIdempotencyRepository();
        http.add(new PlayerApi(module.runs, module.queries, module.dailyRewards, module.auth, idem, module.ctx.tx(), clock, limiter).routes());
        http.add(new AdminApi(module.admin, adminRepo, idem, module.ctx.tx(), clock).routes());
        if (!module.production && module.fakeCoin != null) http.add(new DevApi(module.fakeCoin, module.ctx.coin(), module.ctx).routes());
        mount(env, "PE_PLAYER_DIR", "/play/", true);
        mount(env, "PE_ADMIN_DIR", "/admin/", true);
        mount(env, "PE_MEDIA_DIR", "/media/", false);
        workers = new Workers(module.deadlines, module.payments, module.campaigns, idem, module.ctx.tx(), clock);
        if (startWorkers) workers.start();
    }

    private void mount(Map<String, String> env, String key, String prefix, boolean html) {
        String dir = env.get(key);
        if (dir != null && Files.isDirectory(Path.of(dir))) http.mount(new HttpServerAdapter.StaticMount(prefix, Path.of(dir).toAbsolutePath().normalize(), html));
    }

    private void seedCatalog(Map<String, String> env) throws Exception {
        if (module.ctx.tx().inTransaction(() -> module.ctx.settings().activeCatalogVersion()) != null) return;
        String file = env.get("PE_CATALOG_JSON");
        if (file == null) { LOG.warning("no catalog published and PE_CATALOG_JSON not set: the game is unavailable until a catalog is imported"); return; }
        String media = env.get("PE_MEDIA_PREFIX");                        // e.g. /media/ in development, null = CDN URLs
        var report = module.catalog.importIfNoneActive(ProductDatabaseReader.read(Path.of(file), media), "product-database:" + Path.of(file).getFileName(), "system:seed");
        if (report == null) { LOG.info("catalog already published by another instance"); return; }
        LOG.info("catalog v" + report.version() + " published: " + report.products() + " products, " + report.eligible() + " eligible, "
                + report.rejectedRows() + " rejected");
    }

    /** DEVELOPMENT accounts for the admin sign-in stand-in: PE_DEV_ADMINS="user:EDITOR:password,viewer:VIEWER:password". */
    private void seedDevAdmins(Map<String, String> env, AdminRepository repo) {
        String spec = env.getOrDefault("PE_DEV_ADMINS", "editor:EDITOR:editor-dev-password,viewer:VIEWER:viewer-dev-password");
        for (String entry : spec.split(",")) {
            String[] p = entry.trim().split(":", 3);
            if (p.length != 3) continue;
            module.ctx.tx().run(() -> repo.upsertAccount(new AdminRepository.Account(p[0], p[0] + " (dev)", p[1], AdminApi.hashPassword(p[2]), true)));
        }
    }

    public void start() throws Exception {
        http.start(module.env.getOrDefault("PE_HTTP_HOST", "0.0.0.0"), Integer.parseInt(module.env.getOrDefault("PE_HTTP_PORT", "8080")));
    }

    public void stop() {
        workers.stop();
        http.stop();
        if (module.ctx.coin() instanceof com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.TimeLimitedCoinGateway t) t.close();
        dataSource.close();
    }

    /** "checkpoints[0].reward" style keys for the admin draft bar and publish dialog. */
    static Map<String, Object> flatten(Object config) {
        JsonNode node = Json.MAPPER.valueToTree(config);
        Map<String, Object> out = new TreeMap<>();
        walk("", node, out);
        return out;
    }

    private static void walk(String prefix, JsonNode n, Map<String, Object> out) {
        if (n.isObject()) n.fields().forEachRemaining(e -> walk(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
        else if (n.isArray()) for (int i = 0; i < n.size(); i++) walk(prefix + "[" + i + "]", n.get(i), out);
        else out.put(prefix, n.isNull() ? null : n.isNumber() ? n.numberValue() : n.isBoolean() ? n.booleanValue() : n.asText());
    }

    public static void main(String[] args) throws Exception {
        PriceExpertServer s = new PriceExpertServer(System.getenv(), InstantSource.system(), true);
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        s.start();
    }
}
