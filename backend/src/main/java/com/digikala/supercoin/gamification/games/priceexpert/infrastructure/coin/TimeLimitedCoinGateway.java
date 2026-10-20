package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Applies the handoff's 2 s per-call timeout to any coin provider. A timed-out mutation is UNKNOWN (the call may
 * still complete at the provider; reconciliation finds out). A timed-out balance read is "unreadable".
 * <p>
 * Outstanding work is bounded: at most {@code maxInFlight} provider calls run at once, counting calls that already
 * timed out for the caller but have not returned. When that many are outstanding (a hung provider), a new call is not
 * started: a mutation is answered UNKNOWN without being sent, which reconciliation handles like any other unknown, and a
 * balance read is "unreadable". A timed-out call is interrupted; its effect is still treated as unknown, never as
 * proof that nothing happened. The real adapter must also set its own connect and read deadlines (D06).
 */
public final class TimeLimitedCoinGateway implements CoinGateway, AutoCloseable {
    /** PROPOSED: concurrent provider calls per instance; tune with the provider's capacity. */
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

    private final CoinGateway delegate;
    private final Duration timeout;
    private final Semaphore inFlight;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TimeLimitedCoinGateway(CoinGateway delegate, Duration timeout) { this(delegate, timeout, DEFAULT_MAX_IN_FLIGHT); }

    public TimeLimitedCoinGateway(CoinGateway delegate, Duration timeout, int maxInFlight) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.inFlight = new Semaphore(maxInFlight);
    }

    @Override public OptionalLong balance(String userId) {
        try { return call(() -> delegate.balance(userId)); }
        catch (TimeoutException | ExecutionException | RejectedExecutionException e) { return OptionalLong.empty(); }
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
        catch (RejectedExecutionException e) { return new Result(Outcome.UNKNOWN, null, "not sent: provider calls saturated"); }
    }

    private <T> T call(Supplier<T> s) throws TimeoutException, ExecutionException {
        if (!inFlight.tryAcquire()) throw new RejectedExecutionException("saturated");
        Future<T> f;
        try {
            f = executor.submit(() -> { try { return s.get(); } finally { inFlight.release(); } });   // released when the call really ends
        } catch (RejectedExecutionException e) { inFlight.release(); throw e; }
        try { return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { f.cancel(true); throw e; }
        catch (InterruptedException e) { f.cancel(true); Thread.currentThread().interrupt(); throw new TimeoutException("interrupted"); }
    }

    /** Stops accepting calls and interrupts the outstanding ones (shutdown). */
    @Override public void close() { executor.shutdownNow(); }
}
