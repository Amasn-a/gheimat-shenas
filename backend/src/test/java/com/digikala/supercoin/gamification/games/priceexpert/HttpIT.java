package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http.*;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.JdbcAdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.JdbcIdempotencyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP-level checks through the real routing, authentication, idempotency and JSON edge:
 * T01 over HTTP, T39 (Viewer cannot mutate, Editor changes audited), T41 (object authorization, replay, tampered payloads,
 * stale device/version, payload-key conflicts, secret redaction) and T42 (integration status is truthful).
 */
class HttpIT {
    static TestEnv env;
    static HttpServerAdapter http;
    static String base;
    static final HttpClient client = HttpClient.newHttpClient();
    static final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    static final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) {
            logs.add(r.getMessage() + (r.getThrown() == null ? "" : " " + r.getThrown()));
        }
        @Override public void flush() {}
        @Override public void close() {}
    };
    static final String P = PlayerApi.PREFIX, A = AdminApi.PREFIX;

    @BeforeAll static void up() throws Exception {
        env = TestEnv.create(null);
        JdbcAdminRepository adminRepo = new JdbcAdminRepository();
        env.m.ctx.tx().run(() -> {
            adminRepo.upsertAccount(new AdminRepository.Account("ed", "Ed (dev)", "EDITOR", AdminApi.hashPassword("ed-pass-123"), true));
            adminRepo.upsertAccount(new AdminRepository.Account("vi", "Vi (dev)", "VIEWER", AdminApi.hashPassword("vi-pass-123"), true));
        });
        JdbcIdempotencyRepository idem = new JdbcIdempotencyRepository();
        http = new HttpServerAdapter(env.clock, "default-src 'self'");
        http.add(new PlayerApi(env.m.runs, env.m.queries, env.m.dailyRewards, env.m.auth, idem, env.m.ctx.tx(), env.clock, new RateLimiter(env.clock, false)).routes());
        http.add(new AdminApi(env.m.admin, adminRepo, idem, env.m.ctx.tx(), env.clock).routes());
        http.add(new DevApi(env.m.fakeCoin, env.m.ctx.coin(), env.m.ctx).routes());
        http.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + http.port();
        Logger.getLogger("").addHandler(capture);
    }

    @AfterAll static void down() {
        Logger.getLogger("").removeHandler(capture);
        http.stop();
        env.close();
    }

    // ------------------------------------------------------------------------------------------------ client helpers

    record Res(int status, JsonNode json, String raw, HttpHeaders h) {
        String code() { return json.path("code").asText(null); }
    }
    record HttpHeaders(java.net.http.HttpHeaders h) { String get(String n) { return h.firstValue(n).orElse(null); } }

    static Res call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = r.body().isEmpty() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(r.body());
        return new Res(r.statusCode(), j, r.body(), new HttpHeaders(r.headers()));
    }

    /** A player: dev bearer token + device id (both development stand-ins). */
    record Player(String name, String device) {
        String token() { return "dev." + name; }
        String userId() { return "u_" + name; }
        Res get(String path) throws Exception { return call("GET", P + path, null, "Authorization", "Bearer " + token(), "X-Device-Id", device); }
        Res post(String path, String body, String key) throws Exception {
            return call("POST", P + path, body, "Authorization", "Bearer " + token(), "X-Device-Id", device, "Idempotency-Key", key);
        }
        Res post(String path, String body) throws Exception { return post(path, body, UUID.randomUUID().toString()); }
        Player onDevice(int n) { return new Player(name, TestEnv.deviceOf(name, n)); }
    }

    static Player player() {
        String n = "h" + UUID.randomUUID().toString().substring(0, 8);
        return new Player(n, TestEnv.deviceOf(n, 0));
    }

    static String admin(String user, String pass) throws Exception {
        Res r = call("POST", A + "/session", "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
        assertEquals(200, r.status(), r.raw());
        return r.json().get("token").asText();
    }

    static Res adminCall(String token, String method, String path, String body, String... extra) throws Exception {
        List<String> h = new ArrayList<>(List.of("Authorization", "Bearer " + token));
        if (!method.equals("GET")) h.addAll(List.of("Idempotency-Key", UUID.randomUUID().toString()));
        h.addAll(List.of(extra));
        return call(method, A + path, method.equals("GET") ? null : body, h.toArray(String[]::new));
    }

    /** Starts a run and opens its first question; returns [runId, questionId]. */
    static String[] openQuestion(Player p) throws Exception {
        Res s = p.post("/runs", "{}");
        assertEquals(201, s.status(), s.raw());
        String run = s.json().at("/run/run_id").asText();
        Res q = p.post("/runs/" + run + "/reveal", "{}");
        assertEquals(200, q.status(), q.raw());
        return new String[]{run, q.json().at("/question/question_id").asText()};
    }

    // ------------------------------------------------------------------------------------------------ T01 over HTTP

    @Test void t01_startOverHttp_replayReturnsTheSameAdmission_mismatchIsRejected() throws Exception {
        Player p = player();
        String key = UUID.randomUUID().toString();
        Res first = p.post("/runs", "{}", key);
        assertEquals(201, first.status(), first.raw());
        Res replay = p.post("/runs", "{}", key);
        assertEquals(201, replay.status());
        assertEquals("true", replay.h().get("Idempotent-Replay"));
        assertEquals(first.json(), replay.json(), "same key, same body → the stored admission");
        Res mismatch = p.post("/runs", "{\"x\":1}", key);
        assertEquals(422, mismatch.status());
        assertEquals("IDEMPOTENCY_MISMATCH", mismatch.code());
        assertEquals(1, env.count("SELECT count(*) FROM pe_run WHERE user_id = ?", p.userId()));
        assertEquals(2, p.get("/bootstrap").json().at("/quota/plays_left").asInt(), "one allowance consumed, not three");
        // the key is required and must be a UUID
        assertEquals(422, call("POST", P + "/runs", "{}", "Authorization", "Bearer " + p.token(), "X-Device-Id", p.device()).status());
        assertEquals(422, p.post("/runs", "{}", "not-a-uuid").status());
        // a key from another user is a different scope, not a replay of this user's admission
        Player q = player();
        Res other = q.post("/runs", "{}", key);
        assertEquals(201, other.status());
        assertNull(other.h().get("Idempotent-Replay"));
        assertNotEquals(first.json().at("/run/run_id"), other.json().at("/run/run_id"));
    }

    // ------------------------------------------------------------------------------------------------ T41

    @Test void t41_foreignAndUnknownIdsAreNotFound() throws Exception {
        Player alice = player(), bob = player();
        String[] ids = openQuestion(alice);
        String run = ids[0], question = ids[1];
        for (String path : List.of("/runs/" + run, "/runs/" + run + "/result", "/runs/" + run + "/products", "/runs/" + run + "/share"))
            assertEquals(404, bob.get(path).status(), path);
        assertEquals(404, bob.post("/runs/" + run + "/reveal", "{}").status());
        assertEquals(404, bob.post("/runs/" + run + "/leave", "{}").status());
        assertEquals(404, bob.post("/runs/" + run + "/offer", "{}").status());
        Res steal = bob.post("/questions/" + question + "/answer", "{\"choice\":\"higher\"}");
        assertEquals(404, steal.status(), "an opaque id is not access control");
        assertEquals("OPEN", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", question), "bob's attempt changed nothing");
        // well-formed but unknown, and malformed ids
        assertEquals(404, alice.get("/runs/" + "0".repeat(26)).status());
        assertEquals(404, alice.get("/runs/..%2F..%2Fetc").status());
        assertEquals(404, alice.post("/offers/" + "A".repeat(26) + "/continue", "{}").status());
        assertEquals(404, alice.get("/operations/" + "B".repeat(26)).status());
    }

    @Test void t41_untrustedOrConflictingPayloadsAreRejected_noClientTimePriceOrCost() throws Exception {
        Player p = player();
        String[] ids = openQuestion(p);
        String q = ids[1];
        for (String body : List.of(
                "{\"choice\":\"higher\",\"received_at\":\"2020-01-01T00:00:00Z\"}",    // tampered time
                "{\"choice\":\"higher\",\"price\":1}",                                  // tampered price
                "{\"choice\":\"higher\",\"score\":99}",
                "{\"choice\":\"maybe\"}",
                "{\"choice\":\"higher\",\"choice\":\"lower\"}",                         // conflicting duplicate key
                "[1,2]",
                "not json")) {
            Res r = p.post("/questions/" + q + "/answer", body);
            assertEquals(422, r.status(), body + " → " + r.raw());
            assertEquals("VALIDATION_FAILED", r.code(), body);
            assertFalse(r.raw().contains("Exception") || r.raw().contains("at com."), "no internals in errors: " + r.raw());
        }
        assertEquals("OPEN", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", q), "rejected bodies changed nothing");
        assertEquals(422, p.post("/runs/" + ids[0] + "/offer", "{\"cost\":0}").status(), "the client cannot name a price for continue");
        assertEquals(422, p.post("/runs", "{\"plays_left\":3}").status());
        // oversized body
        assertEquals(422, p.post("/questions/" + q + "/answer", "{\"choice\":\"higher\",\"pad\":\"" + "x".repeat(Http.MAX_BODY) + "\"}").status());
        // authentication and device hint
        assertEquals(401, call("GET", P + "/bootstrap", null, "X-Device-Id", p.device()).status());
        assertEquals(401, call("GET", P + "/bootstrap", null, "Authorization", "Bearer forged", "X-Device-Id", p.device()).status());
        assertEquals(422, call("GET", P + "/bootstrap", null, "Authorization", "Bearer " + p.token()).status());
        assertEquals(422, call("GET", P + "/bootstrap", null, "Authorization", "Bearer " + p.token(), "X-Device-Id", "laptop").status());
        // the server's own receive time decides: a valid answer arriving after t0 + 12,500 ms is refused, whatever the client says
        env.clock.advance(12_501);
        Res late = p.post("/questions/" + q + "/answer", "{\"choice\":\"higher\"}");
        assertEquals(409, late.status(), late.raw());
        assertEquals("QUESTION_EXPIRED", late.code());
        assertEquals("OPEN", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", q), "no answer accepted after the cutoff");
        env.clock.advance(1_000);                                                 // grace elapses; the sweeper settles a timeout
        env.m.deadlines.sweep(100);
        assertEquals("TIMED_OUT", env.scalar("SELECT state FROM pe_question WHERE question_id = ?", q));
    }

    @Test void t41_staleDeviceAndStaleConfigVersionAreRefused() throws Exception {
        Player p = player();
        String run = openQuestion(p)[0];
        Player other = p.onDevice(1);
        Res view = other.get("/runs/" + run);
        assertEquals(200, view.status());
        assertFalse(view.json().get("on_this_device").asBoolean(), "the other device sees the run as elsewhere");
        assertTrue(view.json().get("question").isNull(), "and not the open product (response minimisation)");
        Res stale = other.post("/runs/" + run + "/reveal", "{}");
        assertEquals(409, stale.status());
        assertEquals("ACTIVE_RUN_OTHER_DEVICE", stale.code());
        // stale admin config version
        String ed = admin("ed", "ed-pass-123");
        JsonNode cfg = adminCall(ed, "GET", "/config", null).json();
        int active = cfg.at("/active/version").asInt();
        JsonNode draft = adminCall(ed, "POST", "/config/versions", Json.write(Json.MAPPER.treeToValue(cfg.at("/active/config"), Object.class))).json();
        int v = draft.get("version").asInt();
        String rev = draft.get("revision").asText();
        Res conflict = adminCall(ed, "POST", "/config/versions/" + v + "/publish",
                "{\"version\":" + v + ",\"expected_active_version\":" + (active - 1) + ",\"draft_revision\":\"" + rev + "\",\"reason\":\"stale\"}");
        assertEquals(409, conflict.status(), conflict.raw());
        assertEquals("VERSION_CONFLICT", conflict.code());
        assertEquals(200, adminCall(ed, "POST", "/config/versions/" + v + "/discard", "{}", "If-Match", rev).status());
    }

    @Test void t41_hiddenComparisonDataNeverLeavesTheServerDuringAQuestion() throws Exception {
        Player p = player();
        String[] ids = openQuestion(p);
        long price = env.scalar("SELECT comparison_price FROM pe_question WHERE question_id = ?", ids[1]);
        for (Res r : List.of(p.get("/runs/" + ids[0]), p.post("/runs/" + ids[0] + "/reveal", "{}"), p.get("/bootstrap"))) {
            assertEquals(200, r.status());
            assertFalse(containsValue(r.json(), price), "comparison price exposed: " + r.raw());
            assertFalse(r.raw().matches("(?s).*\"(comparison_price|expected|correct_choice|direction)\".*"), r.raw());
        }
        Res products = p.get("/runs/" + ids[0] + "/products");
        if (products.status() == 200) assertFalse(containsValue(products.json(), price), "unanswered price stays hidden in the product list");
    }

    static boolean containsValue(JsonNode n, long v) {
        if (n.isNumber()) return n.asLong() == v;
        if (n.isTextual()) return n.asText().equals(Long.toString(v));
        for (JsonNode c : n) if (containsValue(c, v)) return true;
        return false;
    }

    @Test void t41_secretsAreNotLogged_orEchoed() throws Exception {
        logs.clear();
        Player p = player();
        String[] ids = openQuestion(p);
        long price = env.scalar("SELECT comparison_price FROM pe_question WHERE question_id = ?", ids[1]);
        String ed = admin("ed", "ed-pass-123");
        assertEquals(401, call("POST", A + "/session", "{\"username\":\"ed\",\"password\":\"wrong-secret-value\"}").status());
        p.post("/questions/" + ids[1] + "/answer", "{\"choice\":\"" + env.rightChoice(ids[0]) + "\"}");
        call("POST", P + "/runs", "{}", "Authorization", "Bearer dev.bad token!", "X-Device-Id", p.device());   // rejected token
        String all = String.join("\n", logs);
        assertFalse(logs.isEmpty(), "events were emitted");
        for (String secret : List.of(p.token(), "dev.bad token!", ed, "ed-pass-123", "wrong-secret-value"))
            assertFalse(all.contains(secret), "secret in logs: " + secret);
        // the question_released event carries no product or price; the price appears only after the answer is settled
        String released = logs.stream().filter(l -> l.contains("\"question_released\"") && l.contains(ids[1])).findFirst().orElseThrow();
        assertFalse(released.contains(Long.toString(price)), released);
        assertFalse(released.contains("dkp"), released);
        // admin passwords are stored hashed; session tokens are stored hashed
        assertEquals(0, env.count("SELECT count(*) FROM pe_admin_user WHERE password_hash LIKE '%ed-pass-123%'"));
        assertEquals(0, env.count("SELECT count(*) FROM pe_admin_session WHERE token_hash = ?", ed));
    }

    // ------------------------------------------------------------------------------------------------ T39

    @Test void t39_viewerCannotMutate_everyEditorChangeIsAudited() throws Exception {
        String vi = admin("vi", "vi-pass-123"), ed = admin("ed", "ed-pass-123");
        assertEquals("VIEWER", adminCall(vi, "GET", "/me", null).json().get("role").asText());
        JsonNode cfg = adminCall(vi, "GET", "/config", null).json();
        String cfgJson = Json.write(Json.MAPPER.treeToValue(cfg.at("/active/config"), Object.class));
        Player target = player();
        openQuestion(target);
        String campaign = "{\"name\":\"کمپین\",\"starts_at\":\"" + env.clock.instant().plusSeconds(3600) + "\",\"ends_at\":\""
                + env.clock.instant().plusSeconds(90_000) + "\",\"code_checkpoint_ids\":[\"cp_5\"],\"code_amount_toman\":50000,"
                + "\"validity_days\":10,\"terms\":\"شرایط آزمایشی\",\"code_cap\":5,\"budget_toman\":250000}";
        Map<String, String[]> writes = new LinkedHashMap<>();
        writes.put("save draft", new String[]{"POST", "/config/versions", cfgJson});
        writes.put("availability", new String[]{"POST", "/game/availability", "{\"enabled\":false,\"player_message\":null,\"reason\":\"x\"}"});
        writes.put("block", new String[]{"POST", "/players/" + target.userId() + "/block", "{\"reason\":\"x\",\"review_reference\":\"R-1\"}"});
        writes.put("unblock", new String[]{"POST", "/players/" + target.userId() + "/unblock", "{\"reason\":\"x\"}"});
        writes.put("review", new String[]{"POST", "/reviews", "{\"user_id\":\"" + target.userId() + "\",\"run_id\":null,\"outcome\":\"no_issue\",\"note\":\"x\"}"});
        writes.put("campaign", new String[]{"POST", "/campaigns", campaign});
        for (var w : writes.entrySet()) {
            Res r = adminCall(vi, w.getValue()[0], w.getValue()[1], w.getValue()[2]);
            assertEquals(403, r.status(), w.getKey() + " → " + r.raw());
        }
        assertEquals(0, env.count("SELECT count(*) FROM pe_audit_event WHERE actor = 'vi' AND action NOT LIKE 'admin.read.%'"),
                "refused viewer writes change nothing and record no change");
        assertEquals(0, env.count("SELECT count(*) FROM pe_player WHERE user_id = ? AND blocked", target.userId()), "viewer could not block");
        assertTrue((boolean) env.scalar("SELECT game_enabled FROM pe_game_settings"), "viewer could not switch the game off");

        // the same writes as Editor succeed and each leaves an audit event naming the actor
        for (var w : writes.entrySet()) {
            long before = env.count("SELECT count(*) FROM pe_audit_event WHERE actor = 'ed'");
            Res r = adminCall(ed, w.getValue()[0], w.getValue()[1], w.getValue()[2]);
            assertTrue(r.status() == 200 || r.status() == 201, w.getKey() + " → " + r.raw());
            assertTrue(env.count("SELECT count(*) FROM pe_audit_event WHERE actor = 'ed'") > before, w.getKey() + " was not audited");
        }
        adminCall(ed, "POST", "/game/availability", "{\"enabled\":true,\"player_message\":null,\"reason\":\"restore\"}");
        JsonNode ov = adminCall(ed, "GET", "/config", null).json();
        int draft = ov.at("/draft/version").asInt();
        assertEquals("DRAFT_CHANGED", adminCall(ed, "POST", "/config/versions/" + draft + "/discard", "{}").code(), "discard needs the reviewed revision");
        assertEquals(200, adminCall(ed, "POST", "/config/versions/" + draft + "/discard", "{}", "If-Match", ov.get("draft_revision").asText()).status());
        assertTrue(adminCall(ed, "GET", "/config", null).json().get("draft").isNull(), "a discarded draft is gone");
        // admin replay and mismatch
        String key = UUID.randomUUID().toString();
        String body = "{\"enabled\":true,\"player_message\":null,\"reason\":\"same\"}";
        Res a1 = call("POST", A + "/game/availability", body, "Authorization", "Bearer " + ed, "Idempotency-Key", key);
        Res a2 = call("POST", A + "/game/availability", body, "Authorization", "Bearer " + ed, "Idempotency-Key", key);
        assertEquals("true", a2.h().get("Idempotent-Replay"));
        assertEquals(a1.json(), a2.json());
        assertEquals(422, call("POST", A + "/game/availability", body.replace("same", "other"), "Authorization", "Bearer " + ed, "Idempotency-Key", key).status());
        // the audit trail cannot be rewritten
        assertThrows(RuntimeException.class, () -> env.sql("UPDATE pe_audit_event SET actor = 'nobody' WHERE actor = 'ed'"));
        assertThrows(RuntimeException.class, () -> env.sql("DELETE FROM pe_audit_event WHERE actor = 'ed'"));
        // sessions: wrong password, signed out, no token
        assertEquals(401, call("POST", A + "/session", "{\"username\":\"vi\",\"password\":\"nope\"}").status());
        assertEquals(200, call("DELETE", A + "/session", null, "Authorization", "Bearer " + vi).status());
        assertEquals(401, adminCall(vi, "GET", "/me", null).status());
        assertEquals(401, call("GET", A + "/me", null).status());
    }

    // ------------------------------------------------------------------------------------------------ T42

    @Test void t42_integrationStatusIsTruthful() throws Exception {
        String vi = admin("vi", "vi-pass-123");
        JsonNode mon = adminCall(vi, "GET", "/monitoring", null).json();
        assertTrue(mon.get("coin_provider").asText().startsWith("fake"), "fake coin is reported as fake: " + mon);
        assertTrue(mon.get("campaign_code_provider").asText().startsWith("fake"), mon.toString());
        Res dev = call("GET", "/dev/v1/price-expert/fake-coin/u_someone", null);
        assertEquals(200, dev.status());
        assertTrue(dev.json().get("fake").asBoolean(), "development endpoints label themselves fake");
        assertTrue(env.m.ctx.coin().isFake());

        // production refuses every development stand-in, including "not integrated" coin; nothing silently falls back
        Map<String, String> prod = new HashMap<>(Map.of("PE_ENV", "production", "PE_CODE_KEY", Base64.getEncoder().encodeToString(new byte[32])));
        IllegalStateException unconfigured = assertThrows(IllegalStateException.class,
                () -> new PriceExpertModule(prod, env.ds, env.clock, new Random(1)));
        assertTrue(unconfigured.getMessage().contains("coin-provider(not integrated)"), unconfigured.getMessage());
        prod.put("PE_COIN_PROVIDER", "fake");
        IllegalStateException fake = assertThrows(IllegalStateException.class, () -> new PriceExpertModule(prod, env.ds, env.clock, new Random(1)));
        assertTrue(fake.getMessage().contains("coin-provider(not integrated)") && fake.getMessage().contains("player-authenticator(dev)")
                && fake.getMessage().contains("campaign-code-provider(fake)"), fake.getMessage());

        // with the unconfigured (real-adapter placeholder) provider nothing is ever assumed paid or affordable
        Map<String, String> unc = new HashMap<>(env.env);
        unc.put("PE_COIN_PROVIDER", "unconfigured");
        PriceExpertModule m = new PriceExpertModule(unc, env.ds, env.clock, new Random(7));
        assertFalse(m.ctx.coin().isFake());
        assertTrue(m.ctx.coin().balance("u_any").isEmpty(), "balance is unknown, not zero and not fake");
        assertEquals("UNKNOWN", m.ctx.coin().credit("u_any", 5, "k-" + UUID.randomUUID()).outcome().name());
    }

    // ------------------------------------------------------------------------------------------------ idempotency lease

    @Test void abandonedRequestKeyIsTakenOverAfterItsLease() throws Exception {
        // review of 716d889: a process that died after recording IN_PROGRESS stranded the key until the 24 h purge
        Player p = player();
        String key = UUID.randomUUID().toString(), path = P + "/player/onboarding";
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var idem = env.m.ctx.idempotency();
        assertTrue(env.m.ctx.tx().inTransaction(() -> idem.begin(p.userId(), "POST", path, key, hash, "dead-attempt", com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository.PLAYER_LEASE, env.clock.instant())).isEmpty());
        assertEquals("REQUEST_IN_PROGRESS", p.post("/player/onboarding", "{}", key).code(), "protected while the lease runs");
        env.clock.advance(com.digikala.supercoin.gamification.games.priceexpert.application.port.out.IdempotencyRepository.PLAYER_LEASE.toMillis() + 1);
        Res r = p.post("/player/onboarding", "{}", key);
        assertEquals(200, r.status(), r.raw());
        assertNull(r.h().get("Idempotent-Replay"), "the retry executed");
        // the dead attempt can neither overwrite nor release the key any more
        env.m.ctx.tx().run(() -> idem.complete(p.userId(), "POST", path, key, "dead-attempt", 500, "{}"));
        env.m.ctx.tx().run(() -> idem.forget(p.userId(), "POST", path, key, "dead-attempt"));
        Res replay = p.post("/player/onboarding", "{}", key);
        assertEquals(200, replay.status(), replay.raw());
        assertEquals("true", replay.h().get("Idempotent-Replay"));
        assertEquals(422, p.post("/player/onboarding", "{\"x\":1}", key).status(), "a different body is still a mismatch");
    }

    // ------------------------------------------------------------------------------------------------ review of b023340, #20 and #21

    @Test void strictJsonRejectsCoercionAliasesAndNulls() throws Exception {
        Player p = player();
        String[] rq = openQuestion(p);
        String path = "/questions/" + rq[1] + "/answer";
        assertEquals(422, p.post(path, "{\"choice\":\"higher\",\"choice_\":\"lower\"}").status(), "two spellings of one field");
        assertEquals(422, p.post(path, "{\"Choice\":\"higher\"}").status());
        assertEquals(422, p.post(path, "null").status(), "a null body");
        assertEquals(422, p.post(path, "[]").status(), "not an object");
        assertEquals(0, env.count("SELECT count(*) FROM pe_question WHERE question_id = ? AND received_at IS NOT NULL", rq[1]), "nothing was recorded");
        String ed = admin("ed", "ed-pass-123");
        JsonNode cfg = adminCall(ed, "GET", "/config", null).json().at("/active/config");
        String json = Json.write(Json.MAPPER.treeToValue(cfg, Object.class));
        assertEquals(422, adminCall(ed, "POST", "/config/versions", json.replaceFirst("\"min_bp\":(\\d+)", "\"min_bp\":$1.9")).status(), "300.9 is not an integer");
        assertEquals(422, adminCall(ed, "POST", "/config/versions", json.replaceFirst("\"plays_per_day\":(\\d+)", "\"plays_per_day\":\"$1\"")).status(), "\"3\" is not a number");
        assertEquals(422, adminCall(ed, "POST", "/config/versions", json.replaceFirst("\"checkpoints\":\\[", "\"checkpoints\":[null,")).status(), "null array element");
        assertTrue(adminCall(ed, "GET", "/config", null).json().get("draft").isNull(), "no draft was saved");
    }

    @Test void answerTimeIsStampedWhenTheWholeBodyHasArrived() throws Exception {
        Player p = player();
        String[] rq = openQuestion(p);
        java.time.Instant accept = java.time.Instant.parse(p.get("/runs/" + rq[0]).json().at("/question/accept_deadline").asText());
        env.clock.set(accept.minusMillis(500));                         // headers arrive before the cutoff
        byte[] body = "{\"choice\":\"higher\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        URI u = URI.create(base);
        try (java.net.Socket s = new java.net.Socket(u.getHost(), u.getPort())) {
            var out = s.getOutputStream();
            out.write(("POST " + P + "/questions/" + rq[1] + "/answer HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + p.token() + "\r\nX-Device-Id: " + p.device()
                    + "\r\nIdempotency-Key: " + UUID.randomUUID() + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(body, 0, 5);
            out.flush();
            Thread.sleep(300);
            env.clock.set(accept.plusMillis(700));                      // the rest of the choice arrives after the cutoff
            out.write(body, 5, body.length - 5);
            out.flush();
            String resp = new String(s.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(resp.startsWith("HTTP/1.1 409"), resp);
            assertTrue(resp.contains("QUESTION_EXPIRED"), "a choice completed after the cutoff is late: " + resp);
        }
    }

    // ------------------------------------------------------------------------------------------------ brief 2026-10-20

    @Test void oneKeyNamesOneOperation_reuseOnAnotherPathIsRefusedAndCreatesNothing() throws Exception {
        Player p = player();
        String key = UUID.randomUUID().toString();
        Res start = p.post("/runs", "{}", key);
        assertEquals(201, start.status(), start.raw());
        String run = start.json().at("/run/run_id").asText();
        Res reused = p.post("/runs/" + run + "/reveal", "{}", key);      // the start key reused for another operation
        assertEquals(422, reused.status(), reused.raw());
        assertEquals("IDEMPOTENCY_MISMATCH", reused.code());
        assertEquals(0, env.count("SELECT count(*) FROM pe_question WHERE run_id = ?", run), "no question was opened");
        assertEquals(422, p.post("/runs/" + run + "/reveal", "{\"score\":10}").status(), "reveal takes no client input (band, score, price)");
    }

    @Test void answerAdmittedBeforeTheDeadline_replayAndRetryAfterItReturnTheSameResult() throws Exception {
        Player p = player();
        String[] ids = openQuestion(p);
        String run = ids[0], q = ids[1];
        String right = env.rightChoice(run), key = UUID.randomUUID().toString();
        env.clock.advance(12_000);                                                // arrives at t0 + 12,000: after the visible 10 s
        Res first = p.post("/questions/" + q + "/answer", "{\"choice\":\"" + right + "\"}", key);
        assertEquals(200, first.status(), first.raw());
        assertEquals("correct", first.json().get("result").asText());
        env.clock.advance(1_000);                                                 // t0 + 13,000: past the arrival deadline
        Res replay = p.post("/questions/" + q + "/answer", "{\"choice\":\"" + right + "\"}", key);
        assertEquals(200, replay.status());
        assertEquals("true", replay.h().get("Idempotent-Replay"));
        Res other = p.post("/questions/" + q + "/answer", "{\"choice\":\"" + TestEnv.flip(right) + "\"}");   // a new key, the other choice
        assertEquals(200, other.status(), other.raw());
        assertEquals("correct", other.json().get("result").asText(), "the admitted answer stands; no second answer");
        assertEquals(1, p.get("/runs/" + run).json().get("score").asInt());
    }

    @Test void dailyRewardOverHttp_statusDoesNotClaim_claimsFromTwoDevicesCreditOnce() throws Exception {
        Player p = player();
        Res start = p.post("/runs", "{}");
        p.post("/runs/" + start.json().at("/run/run_id").asText() + "/leave", "{}");
        Res boot = p.get("/bootstrap");
        assertEquals("claimable", boot.json().at("/daily_reward/state").asText(), boot.raw());
        assertEquals(1, boot.json().at("/daily_reward/amount").asInt());
        assertEquals(7, boot.json().at("/daily_reward/days").size());
        assertEquals("claimable", p.get("/player/daily-reward").json().get("state").asText());
        Res c1 = p.post("/player/daily-reward/claim", "{}");
        Res c2 = p.onDevice(1).post("/player/daily-reward/claim", "{}");
        assertEquals(200, c1.status(), c1.raw());
        assertEquals(200, c2.status(), c2.raw());
        env.drainLedger();
        assertEquals("claimed", p.get("/player/daily-reward").json().get("state").asText());
        assertEquals(1, env.count("SELECT coalesce(sum(amount), 0) FROM pe_fake_wallet_tx WHERE user_id = ? AND kind = 'CREDIT' AND applied", p.userId()));
        assertEquals(422, p.post("/player/daily-reward/claim", "{\"amount\":5}").status(), "the client cannot name an amount");
    }
}
