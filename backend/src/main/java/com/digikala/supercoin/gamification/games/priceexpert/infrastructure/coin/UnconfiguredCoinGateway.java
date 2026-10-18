package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;

import java.util.OptionalLong;

/**
 * Placeholder for the real DigiCoin adapter, which is NOT integrated (R-44). It reads every balance as unknown and
 * reports every mutation as UNKNOWN, so nothing is ever assumed paid; start-up in production refuses to run with it
 * until a verified adapter replaces it.
 */
public final class UnconfiguredCoinGateway implements CoinGateway {
    @Override public OptionalLong balance(String userId) { return OptionalLong.empty(); }
    @Override public Result charge(String userId, long amount, String key) { return new Result(Outcome.UNKNOWN, null, "DigiCoin adapter not configured"); }
    @Override public Result credit(String userId, long amount, String key) { return new Result(Outcome.UNKNOWN, null, "DigiCoin adapter not configured"); }
    @Override public Result refund(String userId, long amount, String key, String chargeKey) { return new Result(Outcome.UNKNOWN, null, "DigiCoin adapter not configured"); }
    @Override public Result query(String key) { return new Result(Outcome.UNKNOWN, null, "DigiCoin adapter not configured"); }
    @Override public boolean isFake() { return false; }
}
