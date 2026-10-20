package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.PlayerAuthenticator;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.TransactionRunner;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.QueryService;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.RunService.Caller;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http.Http.route;

/**
 * Player routes (proposed, following the gamification route convention /v1/gamification/&lt;game&gt;/…; the handoff's draft
 * paths map one to one). Every route authenticates the Super App token; every POST requires an Idempotency-Key.
 */
public final class PlayerApi {
    public static final String PREFIX = "/v1/gamification/price-expert";
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public record AnswerBody(String choice) {}
    public record EmptyBody() {}

    private final RunService runs;
    private final QueryService queries;
    private final PlayerAuthenticator auth;
    private final IdempotencyRepository idem;
    private final TransactionRunner tx;
    private final InstantSource clock;
    private final RateLimiter limiter;

    public PlayerApi(RunService runs, QueryService queries, PlayerAuthenticator auth, IdempotencyRepository idem, TransactionRunner tx,
                     InstantSource clock, RateLimiter limiter) {
        this.runs = runs;
        this.queries = queries;
        this.auth = auth;
        this.idem = idem;
        this.tx = tx;
        this.clock = clock;
        this.limiter = limiter;
    }

    public List<Http.Route> routes() {
        return List.of(
                route("GET", PREFIX + "/bootstrap", r -> Http.Response.ok(queries.bootstrap(caller(r)))),
                route("POST", PREFIX + "/runs", r -> idempotent(r, c -> {
                    limiter.check("start:" + c.userId(), RateLimiter.START_PER_MINUTE);
                    r.json(EmptyBody.class);
                    return Http.Response.status(201, runs.start(c));
                })),
                route("GET", PREFIX + "/runs/{id}", r -> {
                    Caller c = caller(r);
                    limiter.check("poll:" + id(r, "id"), RateLimiter.POLL_PER_SECOND);
                    return Http.Response.ok(queries.run(c, id(r, "id")));
                }),
                route("POST", PREFIX + "/runs/{id}/reveal", r -> idempotent(r, c -> { r.json(EmptyBody.class); return Http.Response.ok(runs.reveal(c, id(r, "id"))); })),
                route("POST", PREFIX + "/questions/{id}/answer", r -> idempotent(r, c -> {
                    AnswerBody b = r.json(AnswerBody.class);
                    Choice choice;
                    try { choice = b.choice() == null ? null : Choice.valueOf(b.choice()); }
                    catch (IllegalArgumentException e) { throw GameException.validation("invalid choice", Map.of("choice", "higher or lower")); }
                    return Http.Response.ok(runs.answer(c, id(r, "id"), choice, r.receivedAt()));
                })),
                route("POST", PREFIX + "/runs/{id}/offer", r -> idempotent(r, c -> { r.json(EmptyBody.class); return Http.Response.ok(runs.activateOffer(c, id(r, "id"))); })),
                route("POST", PREFIX + "/offers/{id}/continue", r -> idempotent(r, c -> {
                    r.json(EmptyBody.class);
                    return Http.Response.status(202, runs.continueOffer(c, id(r, "id"), r.receivedAt()));
                })),
                route("POST", PREFIX + "/offers/{id}/skip", r -> idempotent(r, c -> { r.json(EmptyBody.class); return Http.Response.ok(runs.skip(c, id(r, "id"))); })),
                route("POST", PREFIX + "/runs/{id}/leave", r -> idempotent(r, c -> { r.json(EmptyBody.class); return Http.Response.ok(runs.leave(c, id(r, "id"))); })),
                route("GET", PREFIX + "/operations/{id}", r -> {
                    Caller c = caller(r);
                    limiter.check("poll:" + id(r, "id"), RateLimiter.POLL_PER_SECOND);
                    return Http.Response.ok(runs.operation(c, id(r, "id")));
                }),
                route("GET", PREFIX + "/runs/{id}/result", r -> Http.Response.ok(queries.result(caller(r), id(r, "id")))),
                route("GET", PREFIX + "/runs/{id}/products", r -> Http.Response.ok(queries.products(caller(r), id(r, "id")))),
                route("GET", PREFIX + "/runs/{id}/share", r -> Http.Response.ok(queries.share(caller(r), id(r, "id")))),
                route("POST", PREFIX + "/player/onboarding", r -> idempotent(r, c -> { r.json(EmptyBody.class); runs.onboarding(c); return Http.Response.status(200, Map.of("ok", true)); }))
        );
    }

    Caller caller(Http.Request r) {
        String h = r.header("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw GameException.unauthorized();
        String user = auth.authenticate(h.substring(7).trim()).orElseThrow(GameException::unauthorized);
        String device = r.header("X-Device-Id");
        if (device == null || !UUID.matcher(device).matches())                // a hint only, never authentication
            throw GameException.validation("X-Device-Id must be a UUID", Map.of("x-device-id", "uuid"));
        return new Caller(user, device.toLowerCase());
    }

    static String id(Http.Request r, String name) {
        String v = r.param(name);
        if (!Ids.looksValid(v)) throw GameException.notFound();
        return v;
    }

    @FunctionalInterface interface Body { Http.Response apply(Caller c) throws Exception; }

    /**
     * Handoff idempotency contract: scope (user, method, path, key); same key + same body → stored response; same key +
     * different body → 422 IDEMPOTENCY_MISMATCH; a key from another user is simply a different scope. Only successful
     * responses are stored; errors are re-evaluated on retry (the domain's own uniqueness rules still apply). A retry while
     * the first attempt runs gets REQUEST_IN_PROGRESS; once that attempt's 30 s lease has expired (its process died) the retry
     * takes the key over and executes, which the domain rules make safe (one answer, one operation per offer, …).
     */
    Http.Response idempotent(Http.Request r, Body body) throws Exception {
        Caller c = caller(r);
        String key = r.header("Idempotency-Key");
        if (key == null || !UUID.matcher(key).matches()) throw GameException.validation("Idempotency-Key header (UUID) is required", Map.of());
        String hash = sha256(r.body());
        String owner = Ids.newId();                                         // this attempt; a takeover after a crash gets its own
        Optional<IdempotencyRepository.Stored> existing = tx.inTransaction(() -> idem.begin(c.userId(), r.method(), r.path(), key, hash, owner, IdempotencyRepository.PLAYER_LEASE, clock.instant()));
        if (existing.isPresent()) {
            IdempotencyRepository.Stored s = existing.get();
            if (!s.bodyHash().equals(hash)) throw new GameException("IDEMPOTENCY_MISMATCH", 422, "the key was used with a different body", null);
            if ("IN_PROGRESS".equals(s.status())) throw GameException.conflict("REQUEST_IN_PROGRESS", "the same request is still being processed");
            return new Http.Response(s.responseStatus(), s.responseBody(), Map.of("Idempotent-Replay", "true"));
        }
        try {
            Http.Response resp = body.apply(c);
            String json = Json.write(resp.body());
            tx.run(() -> idem.complete(c.userId(), r.method(), r.path(), key, owner, resp.status(), json));
            return new Http.Response(resp.status(), json, resp.headers());
        } catch (Exception | Error e) {
            tx.run(() -> idem.forget(c.userId(), r.method(), r.path(), key, owner));
            throw e;
        }
    }

    private static String sha256(byte[] body) {
        try {
            byte[] canonical = body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
