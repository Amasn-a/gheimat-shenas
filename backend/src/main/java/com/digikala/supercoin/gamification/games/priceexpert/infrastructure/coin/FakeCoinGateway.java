package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.TransactionRunner;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.OptionalLong;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

/**
 * IN-PROJECT FAKE DigiCoin provider (R-44: do not connect the real or test service yet). State lives in the game's
 * database (pe_fake_wallet / pe_fake_wallet_tx) so it survives restarts and recovery can be exercised. Behaviour is
 * deterministic and scriptable per user through pe_fake_scenario, consumed in order:
 * <pre>[{"op":"charge","outcome":"late_success","late_ms":15000}, {"op":"balance","outcome":"unreadable"}, ...]</pre>
 * outcomes: succeeded · failed · unknown_applied (debit done, response lost) · unknown_not_applied (nothing done,
 * response lost) · late_success (done, invisible to queries until late_ms) · unreadable (balance only); optional delay_ms.
 * Production start-up refuses this class.
 */
public final class FakeCoinGateway implements CoinGateway {
    private final TransactionRunner tx;
    private final InstantSource clock;
    private final long startingBalance;

    public FakeCoinGateway(TransactionRunner tx, InstantSource clock, long startingBalance) {
        this.tx = tx;
        this.clock = clock;
        this.startingBalance = startingBalance;
    }

    private record Step(String outcome, long delayMs, long lateMs) {}

    @Override public OptionalLong balance(String userId) {
        Optional<Step> step = nextStep(userId, "balance");
        step.ifPresent(s -> sleep(s.delayMs()));
        if (step.isPresent() && "unreadable".equals(step.get().outcome())) return OptionalLong.empty();
        return OptionalLong.of(tx.inTransaction(() -> wallet(userId)));
    }

    @Override public Result charge(String userId, long amount, String key) { return mutate(userId, "charge", "CHARGE", -amount, key); }

    @Override public Result credit(String userId, long amount, String key) { return mutate(userId, "credit", "CREDIT", amount, key); }

    @Override public Result refund(String userId, long amount, String key, String chargeKey) {
        boolean chargeApplied = tx.inTransaction(() -> one("SELECT applied FROM pe_fake_wallet_tx WHERE provider_ref = ?", rs -> rs.getBoolean(1), chargeKey).orElse(false));
        if (!chargeApplied) return new Result(Outcome.FAILED, null, "no confirmed charge " + chargeKey);
        return mutate(userId, "refund", "REFUND", amount, key);
    }

    @Override public Result query(String key) {
        return tx.inTransaction(() -> one("SELECT applied, visible_at FROM pe_fake_wallet_tx WHERE provider_ref = ?",
                rs -> {
                    if (!rs.getBoolean(1)) return new Result(Outcome.FAILED, key, "rejected");
                    Instant visible = ts(rs, "visible_at");
                    return visible.isAfter(clock.instant()) ? new Result(Outcome.PENDING, key, null) : new Result(Outcome.SUCCEEDED, key, null);
                }, key).orElse(new Result(Outcome.NOT_FOUND, null, null)));
    }

    @Override public boolean isFake() { return true; }

    private Result mutate(String userId, String op, String kind, long delta, String key) {
        Optional<Step> step = nextStep(userId, op);
        step.ifPresent(s -> sleep(s.delayMs()));
        String outcome = step.map(Step::outcome).orElse("succeeded");
        Instant now = clock.instant();
        return tx.inTransaction(() -> {
            Optional<Result> existing = one("SELECT applied, visible_at FROM pe_fake_wallet_tx WHERE provider_ref = ?", rs -> {
                if (!rs.getBoolean(1)) return new Result(Outcome.FAILED, key, "rejected");
                return ts(rs, "visible_at").isAfter(now) ? new Result(Outcome.PENDING, key, null) : new Result(Outcome.SUCCEEDED, key, null);
            }, key);
            if (existing.isPresent()) return existing.get();               // idempotent by key: duplicate calls/callbacks
            long balance = wallet(userId);
            switch (outcome) {
                case "failed" -> { record(key, userId, kind, delta, false, now); return new Result(Outcome.FAILED, key, "scripted rejection"); }
                case "unknown_not_applied" -> { return new Result(Outcome.UNKNOWN, null, "scripted timeout (not applied)"); }
                default -> { }
            }
            if (balance + delta < 0) { record(key, userId, kind, delta, false, now); return new Result(Outcome.FAILED, key, "insufficient balance"); }
            update("UPDATE pe_fake_wallet SET balance = balance + ? WHERE user_id = ?", delta, userId);
            Instant visible = "late_success".equals(outcome) ? now.plusMillis(step.get().lateMs()) : now;
            record(key, userId, kind, delta, true, visible);
            return switch (outcome) {
                case "unknown_applied", "late_success" -> new Result(Outcome.UNKNOWN, null, "scripted timeout (applied)");
                default -> new Result(Outcome.SUCCEEDED, key, null);
            };
        });
    }

    private void record(String key, String userId, String kind, long delta, boolean applied, Instant visibleAt) {
        update("INSERT INTO pe_fake_wallet_tx (provider_ref, user_id, kind, amount, applied, visible_at, created_at) VALUES (?,?,?,?,?,?,?)",
                key, userId, kind, Math.abs(delta), applied, visibleAt, clock.instant());
    }

    private long wallet(String userId) {
        update("INSERT INTO pe_fake_wallet (user_id, balance) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, startingBalance);
        return one("SELECT balance FROM pe_fake_wallet WHERE user_id = ?", rs -> rs.getLong(1), userId).orElseThrow();
    }

    /** Pops the first scripted step for this op (or "any"). */
    private Optional<Step> nextStep(String userId, String op) {
        return tx.inTransaction(() -> {
            Optional<String> script = one("SELECT script::text FROM pe_fake_scenario WHERE user_id = ? FOR UPDATE", rs -> rs.getString(1), userId);
            if (script.isEmpty()) return Optional.empty();
            ArrayNode arr = (ArrayNode) Json.read(script.get(), JsonNode.class);
            for (int i = 0; i < arr.size(); i++) {
                JsonNode s = arr.get(i);
                String sop = s.path("op").asText("any");
                if (sop.equals(op) || sop.equals("any")) {
                    arr.remove(i);
                    update("UPDATE pe_fake_scenario SET script = ?::jsonb WHERE user_id = ?", arr.toString(), userId);
                    return Optional.of(new Step(s.path("outcome").asText("succeeded"), s.path("delay_ms").asLong(0), s.path("late_ms").asLong(0)));
                }
            }
            return Optional.empty();
        });
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ------------------------------------------------------------------ development controls (never routed in production)

    public void setBalance(String userId, long balance) {
        tx.run(() -> update("INSERT INTO pe_fake_wallet (user_id, balance) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET balance = EXCLUDED.balance", userId, balance));
    }

    public void setScript(String userId, String scriptJson) {
        tx.run(() -> update("INSERT INTO pe_fake_scenario (user_id, script) VALUES (?, ?::jsonb) ON CONFLICT (user_id) DO UPDATE SET script = EXCLUDED.script", userId, scriptJson));
    }
}
