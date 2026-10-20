package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.PlayerRepository.Participation;
import com.digikala.supercoin.gamification.games.priceexpert.application.service.PriceExpertContext;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CoinOperation;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationStatus;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OperationType;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Player;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.FakeCoinGateway;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http.Http.route;

/**
 * DEVELOPMENT-ONLY reviewer controls: the fake coin provider, and player fixtures for reviewing the onboarding and the
 * daily reward modal (reset the onboarding, seed attended days with an unclaimed reward). Registered only when PE_ENV is
 * not production and the fake adapter is active; never part of the player API or the production build.
 */
public final class DevApi {
    public static final String PREFIX = "/dev/v1/price-expert";
    private static final Pattern USER = Pattern.compile("^u_[A-Za-z0-9_-]{1,64}$");
    private final FakeCoinGateway fake;
    private final CoinGateway coin;
    private final PriceExpertContext ctx;

    public DevApi(FakeCoinGateway fake, CoinGateway coin, PriceExpertContext ctx) {
        this.fake = fake;
        this.coin = coin;
        this.ctx = ctx;
    }

    public List<Http.Route> routes() {
        return List.of(
                route("GET", PREFIX + "/fake-coin/{user}", r -> {
                    String u = user(r);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("user_id", u);
                    var b = coin.balance(u);
                    m.put("balance", b.isPresent() ? b.getAsLong() : null);
                    m.put("fake", true);
                    return Http.Response.ok(m);
                }),
                route("POST", PREFIX + "/fake-coin/{user}", r -> {
                    String u = user(r);
                    JsonNode body = com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json.MAPPER.readTree(r.body().length == 0 ? "{}".getBytes() : r.body());
                    if (body.has("balance")) fake.setBalance(u, body.get("balance").asLong());
                    if (body.has("script")) fake.setScript(u, body.get("script").toString());
                    return Http.Response.ok(Map.of("ok", true, "fake", true));
                }),
                route("POST", PREFIX + "/player/{user}", r -> {
                    String u = user(r);
                    JsonNode body = com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json.MAPPER.readTree(r.body().length == 0 ? "{}".getBytes() : r.body());
                    ctx.tx().run(() -> {
                        java.time.Instant now = ctx.now();
                        Player p = ctx.players().lockOrCreatePlayer(u, now);
                        if (body.has("onboarded")) { p.onboarded = body.get("onboarded").asBoolean(); ctx.players().updatePlayer(p, now); }
                        if (body.has("attended_days")) {               // the n days before today were attended; yesterday's reward is unclaimed
                            java.time.LocalDate today = BusinessCalendar.dateOf(now);
                            int n = Math.max(0, Math.min(30, body.get("attended_days").asInt()));
                            for (int i = 1; i <= n; i++) {
                                java.time.LocalDate d = today.minusDays(i);
                                Participation cur = ctx.players().lockParticipation(u, d);
                                ctx.players().saveParticipation(new Participation(u, d, Math.max(1, cur.playsUsed()), Math.max(1, cur.finishedRuns()), true));
                            }
                            if (n > 0 && ctx.ledger().streakRewardOf(u, today.minusDays(1)).isEmpty()) {
                                CoinOperation op = new CoinOperation();
                                op.operationId = Ids.newId(); op.userId = u; op.type = OperationType.STREAK_REWARD; op.amount = 1;
                                op.status = OperationStatus.CLAIMABLE; op.capDay = today.minusDays(1); op.createdAt = now; op.giveUpAt = now.plusSeconds(72 * 3600);
                                ctx.ledger().insertOperation(op);
                            }
                        }
                    });
                    return Http.Response.ok(Map.of("ok", true, "fixture", true));
                }));
    }

    private static String user(Http.Request r) {
        String u = r.param("user");
        if (u == null || !USER.matcher(u).matches()) throw com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException.notFound();
        return u;
    }
}
