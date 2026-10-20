package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.TransactionRunner;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Admin;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.AdminService.Role;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Campaign;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.GameConfig;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.*;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http.Http.route;

/**
 * Standalone admin API (A00–A07). Sign-in here is a DEVELOPMENT stand-in for organisation SSO with MFA (S-10), which is
 * not available in this environment. Sessions are opaque bearer tokens held in memory by the admin app (no cookies, so
 * there is no ambient credential for CSRF to abuse); with SSO cookies the established CSRF protection must be added.
 * Every write requires an Idempotency-Key and is authorised on the server by role.
 */
public final class AdminApi {
    public static final String PREFIX = "/admin/v1/gamification/price-expert";
    private static final Duration SESSION_TTL = Duration.ofHours(8);

    public record SignIn(String username, String password) {}
    public record PublishBody(int version, int expectedActiveVersion, String draftRevision, String reason) {}
    public record AvailabilityBody(boolean enabled, String playerMessage, String reason) {}
    public record ReviewBody(String userId, String runId, String outcome, String note) {}
    public record BlockBody(String reason, String reviewReference) {}
    public record ReasonBody(String reason) {}
    public record BandsBody(List<GameConfig.DifficultyBand> bands) {}
    public record CampaignBody(String name, Instant startsAt, Instant endsAt, List<String> codeCheckpointIds, long codeAmountToman,
                               int validityDays, String terms, int codeCap, long budgetToman) {
        Campaign toCampaign() { return new Campaign(null, name, startsAt, endsAt, codeCheckpointIds, codeAmountToman, validityDays, terms, codeCap, budgetToman, 0, 0, "SCHEDULED"); }
    }

    private final AdminService admin;
    private final AdminRepository repo;
    private final IdempotencyRepository idem;
    private final TransactionRunner tx;
    private final InstantSource clock;
    private final SecureRandom rng = new SecureRandom();

    public AdminApi(AdminService admin, AdminRepository repo, IdempotencyRepository idem, TransactionRunner tx, InstantSource clock) {
        this.admin = admin;
        this.repo = repo;
        this.idem = idem;
        this.tx = tx;
        this.clock = clock;
    }

    public List<Http.Route> routes() {
        return List.of(
                route("POST", PREFIX + "/session", this::signIn),
                route("DELETE", PREFIX + "/session", r -> { String t = token(r); tx.run(() -> repo.deleteSession(hash(t))); return Http.Response.ok(Map.of("ok", true)); }),
                route("GET", PREFIX + "/me", r -> { Admin a = admin(r); return Http.Response.ok(Map.of("username", a.username(), "role", a.role().name())); }),
                route("GET", PREFIX + "/config", r -> Http.Response.ok(admin.config(admin(r)))),
                // If-Match carries the revision of the draft the editor loaded (absent when there was none): see AdminService.saveDraft
                route("POST", PREFIX + "/config/versions", r -> write(r, a -> Http.Response.ok(admin.saveDraft(a, r.json(GameConfig.class), r.header("If-Match"))))),
                route("POST", PREFIX + "/config/versions/{v}/discard", r -> write(r, a -> { admin.discardDraft(a, version(r), r.header("If-Match")); return Http.Response.ok(Map.of("ok", true)); })),
                route("POST", PREFIX + "/config/versions/{v}/publish", r -> write(r, a -> {
                    PublishBody b = r.json(PublishBody.class);
                    if (b.version() != version(r)) throw GameException.validation("version mismatch", Map.of());
                    return Http.Response.ok(admin.publish(a, b.version(), b.expectedActiveVersion(), b.draftRevision(), b.reason()));
                })),
                route("POST", PREFIX + "/difficulty/pair-counts", r -> Http.Response.ok(admin.bandPairCounts(admin(r), r.json(BandsBody.class).bands()))),
                route("POST", PREFIX + "/game/availability", r -> write(r, a -> {
                    AvailabilityBody b = r.json(AvailabilityBody.class);
                    return Http.Response.ok(admin.setAvailability(a, b.enabled(), b.playerMessage(), b.reason()));
                })),
                route("GET", PREFIX + "/runs", r -> Http.Response.ok(admin.runs(admin(r), r.queryParam("user_id"), r.queryParam("run_id")))),
                route("GET", PREFIX + "/operations", r -> Http.Response.ok(admin.operations(admin(r), r.queryParam("user_id"), r.queryParam("run_id"), r.queryParam("status")))),
                route("POST", PREFIX + "/operations/{id}/retry", r -> write(r, a -> Http.Response.ok(admin.retryOperation(a, r.param("id"), r.json(ReasonBody.class).reason())))),
                route("GET", PREFIX + "/entitlements/review", r -> Http.Response.ok(admin.entitlementsNeedingReview(admin(r)))),
                route("POST", PREFIX + "/entitlements/{id}/retry", r -> write(r, a -> Http.Response.ok(admin.retryEntitlement(a, r.param("id"), r.json(ReasonBody.class).reason())))),
                route("GET", PREFIX + "/players/{user}", r -> Http.Response.ok(admin.player(admin(r), r.param("user")))),
                route("POST", PREFIX + "/players/{user}/block", r -> write(r, a -> {
                    BlockBody b = r.json(BlockBody.class);
                    return Http.Response.ok(admin.block(a, r.param("user"), b.reason(), b.reviewReference()));
                })),
                route("POST", PREFIX + "/players/{user}/unblock", r -> write(r, a -> Http.Response.ok(admin.unblock(a, r.param("user"), r.json(ReasonBody.class).reason())))),
                route("POST", PREFIX + "/reviews", r -> write(r, a -> {
                    ReviewBody b = r.json(ReviewBody.class);
                    return Http.Response.ok(admin.review(a, b.userId(), b.runId(), b.outcome(), b.note()));
                })),
                route("GET", PREFIX + "/alerts", r -> Http.Response.ok(admin.alerts(admin(r)))),
                route("GET", PREFIX + "/monitoring", r -> Http.Response.ok(admin.monitoring(admin(r)))),
                route("GET", PREFIX + "/audit", r -> {
                    Admin a = admin(r);
                    String from = r.queryParam("from"), to = r.queryParam("to"), before = r.queryParam("before_id");
                    return Http.Response.ok(admin.audit(a, blank(r.queryParam("area")), blank(r.queryParam("actor")), "1".equals(r.queryParam("changes_only")),
                            from == null || from.isBlank() ? null : Instant.parse(from), to == null || to.isBlank() ? null : Instant.parse(to),
                            before == null || before.isBlank() ? 0 : Long.parseLong(before)));
                }),
                route("GET", PREFIX + "/campaigns", r -> Http.Response.ok(admin.campaigns(admin(r)))),
                route("POST", PREFIX + "/campaigns", r -> write(r, a -> Http.Response.status(201, admin.createCampaign(a, r.json(CampaignBody.class).toCampaign())))),
                route("POST", PREFIX + "/campaigns/{id}", r -> write(r, a -> Http.Response.ok(admin.updateCampaign(a, r.param("id"), r.json(CampaignBody.class).toCampaign())))),
                route("POST", PREFIX + "/campaigns/{id}/end", r -> write(r, a -> Http.Response.ok(admin.endCampaign(a, r.param("id"), r.json(ReasonBody.class).reason()))))
        );
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    private static int version(Http.Request r) {
        try { return Integer.parseInt(r.param("v")); } catch (NumberFormatException e) { throw GameException.notFound(); }
    }

    private Http.Response signIn(Http.Request r) {
        SignIn b = r.json(SignIn.class);
        Optional<AdminRepository.Account> acc = tx.inTransaction(() -> repo.account(b.username() == null ? "" : b.username().trim()));
        if (acc.isEmpty() || !acc.get().active() || !verifyPassword(b.password() == null ? "" : b.password(), acc.get().passwordHash()))
            throw new GameException("INVALID_CREDENTIALS", 401, "invalid credentials", null);
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        tx.run(() -> repo.insertSession(hash(token), acc.get().username(), acc.get().role(), now, now.plus(SESSION_TTL)));
        return Http.Response.ok(Map.of("token", token, "username", acc.get().username(), "display_name", acc.get().displayName(),
                "role", acc.get().role(), "expires_at", now.plus(SESSION_TTL).toString()));
    }

    Admin admin(Http.Request r) {
        String t = token(r);
        AdminRepository.Session s = tx.inTransaction(() -> repo.session(hash(t), clock.instant())).orElseThrow(GameException::unauthorized);
        return new Admin(s.username(), Role.valueOf(s.role()));
    }

    private static String token(Http.Request r) {
        String h = r.header("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw GameException.unauthorized();
        return h.substring(7).trim();
    }

    @FunctionalInterface interface Body { Http.Response apply(Admin a) throws Exception; }

    private Http.Response write(Http.Request r, Body body) throws Exception {
        Admin a = admin(r);
        String key = r.header("Idempotency-Key");
        if (key == null || key.length() < 8 || key.length() > 64) throw GameException.validation("Idempotency-Key header is required", Map.of());
        String bodyHash = hash(r.bodyText());
        String scope = "admin:" + a.username();
        String owner = Ids.newId();                                         // this attempt; a takeover after a crash gets its own
        Optional<IdempotencyRepository.Stored> existing = tx.inTransaction(() -> idem.begin(scope, r.method(), r.path(), key, bodyHash, owner, IdempotencyRepository.ADMIN_LEASE, clock.instant()));
        if (existing.isPresent()) {
            if (!existing.get().bodyHash().equals(bodyHash)) throw new GameException("IDEMPOTENCY_MISMATCH", 422, "the key was used with a different body", null);
            if ("IN_PROGRESS".equals(existing.get().status())) throw GameException.conflict("REQUEST_IN_PROGRESS", "still processing");
            return new Http.Response(existing.get().responseStatus(), existing.get().responseBody(), Map.of("Idempotent-Replay", "true"));
        }
        try {
            Http.Response resp = body.apply(a);
            String json = Json.write(resp.body());
            tx.run(() -> idem.complete(scope, r.method(), r.path(), key, owner, resp.status(), json));
            return new Http.Response(resp.status(), json, resp.headers());
        } catch (Exception | Error e) {
            tx.run(() -> idem.forget(scope, r.method(), r.path(), key, owner));
            throw e;
        }
    }

    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ development accounts (PBKDF2-SHA256)

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return "pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(pbkdf2(password, salt, 120000));
    }

    static boolean verifyPassword(String password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        byte[] expected = Base64.getDecoder().decode(p[3]);
        return MessageDigest.isEqual(expected, pbkdf2(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
