package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.FakeCoinGateway;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http.Http.route;

/**
 * DEVELOPMENT-ONLY reviewer controls for the fake coin provider. Registered only when PE_ENV is not production and the
 * fake adapter is active; never part of the player API or the production build.
 */
public final class DevApi {
    public static final String PREFIX = "/dev/v1/price-expert";
    private static final Pattern USER = Pattern.compile("^u_[A-Za-z0-9_-]{1,64}$");
    private final FakeCoinGateway fake;
    private final CoinGateway coin;

    public DevApi(FakeCoinGateway fake, CoinGateway coin) {
        this.fake = fake;
        this.coin = coin;
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
                }));
    }

    private static String user(Http.Request r) {
        String u = r.param("user");
        if (u == null || !USER.matcher(u).matches()) throw com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException.notFound();
        return u;
    }
}
