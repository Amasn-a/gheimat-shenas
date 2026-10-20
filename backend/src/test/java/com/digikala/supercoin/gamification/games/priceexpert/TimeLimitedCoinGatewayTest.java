package com.digikala.supercoin.gamification.games.priceexpert;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CoinGateway;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.coin.TimeLimitedCoinGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Review of b023340, #22: a hung provider cannot accumulate unbounded outstanding calls. */
class TimeLimitedCoinGatewayTest {

    @Test void hungProviderCallsAreBoundedAndStillUnknown() {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger(), interrupted = new AtomicInteger();
        CoinGateway hung = new CoinGateway() {
            Result block() {
                started.incrementAndGet();
                try { never.await(); } catch (InterruptedException e) { interrupted.incrementAndGet(); Thread.currentThread().interrupt(); }
                return Result.of(Outcome.SUCCEEDED);
            }
            @Override public OptionalLong balance(String u) { block(); return OptionalLong.of(1); }
            @Override public Result charge(String u, long a, String k) { return block(); }
            @Override public Result credit(String u, long a, String k) { return block(); }
            @Override public Result refund(String u, long a, String k, String c) { return block(); }
            @Override public Result query(String k) { return block(); }
            @Override public boolean isFake() { return true; }
        };
        try (TimeLimitedCoinGateway g = new TimeLimitedCoinGateway(hung, Duration.ofMillis(50), 2)) {
            for (int i = 0; i < 10; i++) {
                CoinGateway.Result r = g.charge("u", 10, "k" + i);
                assertEquals(CoinGateway.Outcome.UNKNOWN, r.outcome(), "a timed-out or unsent debit is unknown, never failed");
            }
            assertTrue(g.balance("u").isEmpty());
            assertTrue(started.get() <= 11, "calls started: " + started.get());   // 10 charges + 1 balance read
            // timed-out calls are interrupted, so a cooperative provider frees its slot and new calls can start again
            assertTrue(interrupted.get() >= 1);
        }
    }

    @Test void saturationAnswersUnknownWithoutStartingWork() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        CoinGateway stuck = new CoinGateway() {   // ignores interrupts: the worst case for outstanding work
            Result block() { started.incrementAndGet(); while (true) { try { never.await(); } catch (InterruptedException ignored) { } } }
            @Override public OptionalLong balance(String u) { block(); return OptionalLong.empty(); }
            @Override public Result charge(String u, long a, String k) { return block(); }
            @Override public Result credit(String u, long a, String k) { return block(); }
            @Override public Result refund(String u, long a, String k, String c) { return block(); }
            @Override public Result query(String k) { return block(); }
            @Override public boolean isFake() { return true; }
        };
        TimeLimitedCoinGateway g = new TimeLimitedCoinGateway(stuck, Duration.ofMillis(30), 3);
        for (int i = 0; i < 20; i++) assertEquals(CoinGateway.Outcome.UNKNOWN, g.charge("u", 10, "k" + i).outcome());
        Thread.sleep(50);
        assertEquals(3, started.get(), "at most 3 provider calls are ever outstanding");
        assertTrue(g.charge("u", 10, "x").error().contains("saturated"));
    }
}
