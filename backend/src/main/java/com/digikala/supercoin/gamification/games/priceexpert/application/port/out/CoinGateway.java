package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.util.OptionalLong;

/**
 * DigiCoin port (handoff: getBalance, charge, credit, refund, getOperation; 2 s timeout per call).
 * Every mutating call carries our operation id as the provider idempotency key. A timeout is UNKNOWN, never
 * success and never failure. Only the in-project fake implements this today; the real/test DigiCoin service is
 * NOT integrated (R-44) and production start-up refuses the fake.
 */
public interface CoinGateway {

    enum Outcome { SUCCEEDED, FAILED, UNKNOWN, PENDING, NOT_FOUND }

    record Result(Outcome outcome, String providerRef, String error) {
        public static Result of(Outcome o) { return new Result(o, null, null); }
    }

    /** Spendable balance; empty when it cannot be read (counts as too low, R-63). */
    OptionalLong balance(String userId);

    Result charge(String userId, long amount, String idempotencyKey);

    Result credit(String userId, long amount, String idempotencyKey);

    /** Reverses a confirmed charge. {@code chargeKey} identifies the original debit. */
    Result refund(String userId, long amount, String idempotencyKey, String chargeKey);

    /** Status of an earlier mutating call by its idempotency key (reconciliation of UNKNOWN outcomes). */
    Result query(String idempotencyKey);

    boolean isFake();
}
