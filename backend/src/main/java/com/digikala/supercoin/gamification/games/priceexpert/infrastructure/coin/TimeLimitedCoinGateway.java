package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Applies the handoff's 2 s per-call timeout to any coin provider. A timed-out mutation is UNKNOWN (the call may
 * still complete at the provider; reconciliation finds out). A timed-out balance read is "unreadable".
 */
public final class TimeLimitedCoinGateway implements CoinGateway {
    private final CoinGateway delegate;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TimeLimitedCoinGateway(CoinGateway delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override public OptionalLong balance(String userId) {
        try { return call(() -> delegate.balance(userId)); }
        catch (TimeoutException | ExecutionException e) { return OptionalLong.empty(); }
    }

    @Override public Result charge(String userId, long amount, String key) { return mutation(() -> delegate.charge(userId, amount, key)); }
    @Override public Result credit(String userId, long amount, String key) { return mutation(() -> delegate.credit(userId, amount, key)); }
    @Override public Result refund(String userId, long amount, String key, String chargeKey) { return mutation(() -> delegate.refund(userId, amount, key, chargeKey)); }
    @Override public Result query(String key) { return mutation(() -> delegate.query(key)); }
    @Override public boolean isFake() { return delegate.isFake(); }

    private Result mutation(Supplier<Result> s) {
        try { return call(s); }
        catch (TimeoutException e) { return new Result(Outcome.UNKNOWN, null, "timeout after " + timeout.toMillis() + " ms"); }
        catch (ExecutionException e) { return new Result(Outcome.UNKNOWN, null, "provider error: " + e.getCause().getClass().getSimpleName()); }
    }

    private <T> T call(Supplier<T> s) throws TimeoutException, ExecutionException {
        Future<T> f = executor.submit(s::get);
        try { return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TimeoutException("interrupted"); }
    }
}
