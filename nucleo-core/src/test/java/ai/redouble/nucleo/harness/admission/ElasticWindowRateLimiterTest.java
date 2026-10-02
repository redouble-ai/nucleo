/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.admission.ElasticWindowRateLimiter.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent unit tests for {@link ElasticWindowRateLimiter} as an admission account.
 *
 * <p>These tests exercise the circuit breaker state machine under single-threaded
 * and concurrent conditions: basic throttle dynamics, circuit open/close, probe
 * claiming races, probe expiry, stuck-state self-healing, the k-th-oldest deadline and
 * the newest-first refund.
 *
 * <p>The tests use a {@code ConfigurableTestLimiter} subclass with tight defaults
 * (sub-second window, small max requests, short cooldowns) so the state machine
 * exercises in milliseconds rather than minutes.
 *
 * <p><b>Constructor gotcha:</b> the parent constructor calls {@code getBaseWindowMs()}
 * and {@code getMaxRequests()} before subclass instance fields are initialized.
 * We work around this by stashing config in thread-locals before constructing,
 * so the overridden methods read from the thread-locals rather than instance fields.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public class ElasticWindowRateLimiterTest {

    /** A representative upstream rejection; these tests care that a failure happened, not which. */
    private static final UpstreamFailure FAILURE =
            new UpstreamFailure(429, "test-service", "HTTP 429 at /search: slow down", 0);

    private static final List<Void> NONE = Collections.emptyList();

    private static List<Void> units(int count) {
        return Collections.nCopies(count, null);
    }

    private static final ThreadLocal<Long> configBaseWindowMs = new ThreadLocal<>();
    private static final ThreadLocal<Integer> configMaxRequests = new ThreadLocal<>();
    private static final ThreadLocal<Long> configInitialCooldownMs = new ThreadLocal<>();
    private static final ThreadLocal<Long> configMaxCooldownMs = new ThreadLocal<>();
    private static final ThreadLocal<Integer> configFailuresAtMaxToBlock = new ThreadLocal<>();
    private static final ThreadLocal<Long> configProbeTimeoutMs = new ThreadLocal<>();

    private static class ConfigurableTestLimiter extends ElasticWindowRateLimiter {
        // Store the config captured from thread-locals at construction time.
        // These are read in overrides so even if thread-locals are cleared later,
        // the limiter keeps its configured values.
        private final long baseWindowMs;
        private final int maxRequests;
        private final long initialCooldownMs;
        private final long maxCooldownMs;
        private final int failuresAtMaxToBlock;
        private final long probeTimeoutMs;
        ConfigurableTestLimiter() {
            // Parent constructor has already run the template getters, which read directly
            // from thread-locals. Now we snapshot them into instance fields for the rest of
            // the lifecycle.
            this.baseWindowMs = configBaseWindowMs.get();
            this.maxRequests = configMaxRequests.get();
            this.initialCooldownMs = configInitialCooldownMs.get();
            this.maxCooldownMs = configMaxCooldownMs.get();
            this.failuresAtMaxToBlock = configFailuresAtMaxToBlock.get();
            this.probeTimeoutMs = configProbeTimeoutMs.get();
        }
        @Override protected long getBaseWindowMs() {
            Long tl = configBaseWindowMs.get();
            return tl != null ? tl : baseWindowMs;
        }
        @Override protected int getMaxRequests() {
            Integer tl = configMaxRequests.get();
            return tl != null ? tl : maxRequests;
        }
        @Override protected long getInitialCooldownMs() {
            Long tl = configInitialCooldownMs.get();
            return tl != null ? tl : initialCooldownMs;
        }
        @Override protected long getMaxCooldownMs() {
            Long tl = configMaxCooldownMs.get();
            return tl != null ? tl : maxCooldownMs;
        }
        @Override protected int getFailuresAtMaxToBlock() {
            Integer tl = configFailuresAtMaxToBlock.get();
            return tl != null ? tl : failuresAtMaxToBlock;
        }
        @Override protected long getProbeTimeoutMs() {
            Long tl = configProbeTimeoutMs.get();
            return tl != null ? tl : probeTimeoutMs;
        }
    }

    private static ConfigurableTestLimiter limiter(long baseWindowMs, int maxRequests) {
        return limiter(baseWindowMs, maxRequests, 100, 1000, 3);
    }
    private static ConfigurableTestLimiter limiter(long baseWindowMs, int maxRequests, long initialCooldownMs, long maxCooldownMs, int failuresAtMaxToBlock) {
        return limiter(baseWindowMs, maxRequests, initialCooldownMs, maxCooldownMs, failuresAtMaxToBlock, maxCooldownMs);
    }
    private static ConfigurableTestLimiter limiter(long baseWindowMs, int maxRequests, long initialCooldownMs, long maxCooldownMs, int failuresAtMaxToBlock, long probeTimeoutMs) {
        configBaseWindowMs.set(baseWindowMs);
        configMaxRequests.set(maxRequests);
        configInitialCooldownMs.set(initialCooldownMs);
        configMaxCooldownMs.set(maxCooldownMs);
        configFailuresAtMaxToBlock.set(failuresAtMaxToBlock);
        configProbeTimeoutMs.set(probeTimeoutMs);
        try {
            return new ConfigurableTestLimiter();
        }
        finally {
            clearConfig();
        }
    }
    private static void clearConfig() {
        configBaseWindowMs.remove();
        configMaxRequests.remove();
        configInitialCooldownMs.remove();
        configMaxCooldownMs.remove();
        configFailuresAtMaxToBlock.remove();
        configProbeTimeoutMs.remove();
    }
    private static void forceBlocked(ElasticWindowRateLimiter l) {
        // 9 failures to reach max throttle, then 3 more to open the circuit.
        for (int i = 0; i < 12; i++) {
            l.onRateLimitError(FAILURE);
        }
    }
    private static boolean take(ElasticWindowRateLimiter l) {
        return l.tryTake(units(1), NONE);
    }

    // ======================== Basic state machine tests ========================

    @Test
    public void testInitialState() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        assertEquals(CircuitState.HEALTHY, l.getCircuitState());
        assertEquals(0.0, l.getThrottleCoefficient());
    }

    @Test
    public void testBasicTakeInHealthy() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        assertTrue(l.fits(units(1), NONE));
        assertTrue(take(l));
        assertEquals(CircuitState.HEALTHY, l.getCircuitState());
        assertEquals(1, l.currentInUse());
    }

    @Test
    public void testOnRateLimitErrorBumpsThrottle() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        l.onRateLimitError(FAILURE);
        assertEquals(1.0, l.getThrottleCoefficient(), 0.001);
        l.onRateLimitError(FAILURE);
        assertEquals(2.0, l.getThrottleCoefficient(), 0.001);
    }

    @Test
    public void testOnRateLimitErrorCapsAtMaxThrottle() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        for (int i = 0; i < 20; i++) {
            l.onRateLimitError(FAILURE);
        }
        assertEquals(9.0, l.getThrottleCoefficient(), 0.001, "throttle should cap at maxThrottle (default 9.0)");
    }

    @Test
    public void testOnSuccessDoesNotDecrementBelowZero() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        for (int i = 0; i < 100; i++) {
            l.onSuccess();
        }
        assertEquals(0.0, l.getThrottleCoefficient(), 0.001);
    }

    @Test
    public void testOnSuccessDecrementsThrottleAfterEnoughSuccesses() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        l.onRateLimitError(FAILURE); // throttle = 1.0
        l.onRateLimitError(FAILURE); // throttle = 2.0
        // 3 successes should trigger one decrement by 0.2
        l.onSuccess();
        l.onSuccess();
        l.onSuccess();
        assertEquals(1.8, l.getThrottleCoefficient(), 0.01, "3 successes should decrement 0.2 from 2.0");
    }

    @Test
    public void testThrottleRelaxationWakesTheMonitor() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        AtomicInteger wakes = new AtomicInteger();
        l.onCapacityChange(wakes::incrementAndGet);
        l.onRateLimitError(FAILURE);
        assertEquals(0, wakes.get(), "a slower window wakes nobody");
        l.onSuccess();
        l.onSuccess();
        l.onSuccess();
        assertEquals(1, wakes.get(), "a faster window wakes the evaluator once");
    }

    @Test
    public void testEffectiveWindowStretchesWithThrottle() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        assertEquals(1000, l.getEffectiveWindowMs(), "at throttle 0, effective window = base window");
        l.onRateLimitError(FAILURE); // throttle = 1.0
        assertEquals(2000, l.getEffectiveWindowMs(), "at throttle 1, effective window = 2 * base");
        l.onRateLimitError(FAILURE); // throttle = 2.0
        assertEquals(3000, l.getEffectiveWindowMs(), "at throttle 2, effective window = 3 * base");
    }

    @Test
    public void testAdvisoryPressureBumpsThrottleWithoutCircuitChange() {
        ElasticWindowRateLimiter l = limiter(1000, 10);
        // Reach max throttle via advisory signals only. Circuit should NOT open.
        for (int i = 0; i < 20; i++) {
            l.onAdvisoryPressure();
        }
        assertEquals(9.0, l.getThrottleCoefficient(), 0.001);
        assertEquals(CircuitState.HEALTHY, l.getCircuitState(), "advisory alone must not open the circuit");
    }

    // ======================== Window arithmetic ========================

    @Test
    public void testWindowFullDoesNotFit_andEarliestFitIsTheKthOldestStampPlusTheWindow() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 3);
        assertTrue(take(l));
        Thread.sleep(20);
        assertTrue(take(l));
        Thread.sleep(20);
        assertTrue(take(l));
        assertFalse(l.fits(units(1), NONE), "window of 3 is full");

        long now = System.nanoTime();
        long forOne = l.earliestFit(units(1), NONE);
        long forTwo = l.earliestFit(units(2), NONE);
        long forOneBehindOne = l.earliestFit(units(1), units(1));

        assertTrue(forOne > now && forOne <= now + TimeUnit.MILLISECONDS.toNanos(1000), "one slot frees when the oldest stamp leaves the window");
        assertTrue(forTwo > forOne, "two slots need the second-oldest stamp to leave, later than the oldest");
        assertEquals(forTwo, forOneBehindOne, "a slot reserved ahead counts exactly like a second amount");
        assertNull(l.earliestFit(units(4), NONE), "more than the window can ever hold has no clock deadline");
        assertThrows(UncorrectableRuntimeLLMException.class, () -> l.fits(units(4), NONE), "and is refused outright");
    }

    @Test
    public void testGiveDropsTheNewestStamps() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 2);
        assertTrue(take(l));
        long afterFirst = l.earliestFit(units(2), NONE);
        Thread.sleep(50);
        assertTrue(take(l));
        long withBoth = l.earliestFit(units(1), NONE);
        assertEquals(afterFirst, withBoth, "the deadline for one slot is the oldest stamp plus the window");

        l.give(units(1));

        assertTrue(l.fits(units(1), NONE), "the refund freed a slot");
        assertEquals(afterFirst, l.earliestFit(units(2), NONE), "the oldest stamp is untouched: the newest one was dropped");
    }

    // ======================== Circuit breaker tests ========================

    @Test
    public void testCircuitOpensAfterFailuresAtMaxThrottle() {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        // Climb to max throttle (9 failures with increment 1.0)
        for (int i = 0; i < 9; i++) {
            l.onRateLimitError(FAILURE);
        }
        assertEquals(9.0, l.getThrottleCoefficient(), 0.001);
        assertEquals(CircuitState.HEALTHY, l.getCircuitState(), "should still be HEALTHY at max throttle");
        // Three more failures should open the circuit
        l.onRateLimitError(FAILURE);
        l.onRateLimitError(FAILURE);
        l.onRateLimitError(FAILURE);
        assertEquals(CircuitState.BLOCKED, l.getCircuitState(), "circuit should open after 3 failures at max");
    }

    @Test
    public void testCircuitRefusesInBlockedState() {
        ElasticWindowRateLimiter l = limiter(1000, 10, 10_000, 60_000, 3);
        forceBlocked(l);
        assertEquals(CircuitState.BLOCKED, l.getCircuitState());
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class, () -> l.fits(units(1), NONE));
        assertTrue(refusal.getMessage().contains("Cooldown is active"), refusal.getMessage());
        assertThrows(UncorrectableRuntimeLLMException.class, () -> take(l));
    }

    @Test
    public void testCircuitBlockedToProbingAfterCooldown() throws InterruptedException {
        // Tight cooldown for fast test
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        forceBlocked(l);
        assertEquals(CircuitState.BLOCKED, l.getCircuitState());
        Thread.sleep(150); // wait past cooldown
        assertTrue(l.fits(units(1), NONE), "after cooldown the head may claim the probe");
        assertTrue(take(l), "the take claims the probe");
        assertEquals(CircuitState.PROBING, l.getCircuitState(), "the claimant stays in PROBING until signalled");
        assertEquals("probing", l.statusIndicator(), "the indicator names the probe in flight");
    }

    @Test
    public void theReplenishmentOverrideIsFinal_soNoServiceWindowCanDrift() throws NoSuchMethodException {
        java.lang.reflect.Method override = ElasticWindowRateLimiter.class.getDeclaredMethod("replenishment");
        assertTrue(java.lang.reflect.Modifier.isFinal(override.getModifiers()), "a subclass cannot answer RELEASE for a window");
        assertEquals(RateLimiter.Replenishment.TIME, limiter(1000, 10).replenishment());
    }

    @Test
    public void testProbeBelongsToTheHead() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        forceBlocked(l);
        Thread.sleep(150);
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> l.fits(units(1), units(1)), "a follower with a slot reserved ahead of it is refused as if the probe were in flight");
        assertTrue(refusal.getMessage().contains("single probe"), refusal.getMessage());
        assertEquals(CircuitState.BLOCKED, l.getCircuitState(), "the follower took nothing");
        assertTrue(take(l), "the head claims it");
        assertEquals(CircuitState.PROBING, l.getCircuitState());
    }

    @Test
    public void testProbeSuccessReturnsToHealthy() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        forceBlocked(l);
        Thread.sleep(150);
        take(l); // becomes probe
        assertEquals(CircuitState.PROBING, l.getCircuitState());
        l.onSuccess();
        assertEquals(CircuitState.HEALTHY, l.getCircuitState(), "successful probe should close circuit");
    }

    @Test
    public void testProbeFailureDoublesCooldown() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 10_000, 3);
        forceBlocked(l);
        Thread.sleep(150);
        take(l); // probe
        // Probe failure
        l.onRateLimitError(FAILURE);
        assertEquals(CircuitState.BLOCKED, l.getCircuitState(), "failed probe returns to BLOCKED");
        long remaining = l.getCooldownRemainingMs();
        assertTrue(remaining > 150 && remaining <= 200, "the cooldown doubled from 100ms to 200ms, " + remaining + "ms left");
        assertThrows(UncorrectableRuntimeLLMException.class, () -> l.fits(units(1), NONE), "cooldown still active");
        Thread.sleep(250);
        assertTrue(take(l), "probe should be allowed after doubled cooldown");
    }

    @Test
    public void testExponentialCooldownCapsAtMax() throws InterruptedException {
        // Initial 100ms, max 400ms. After a few failures: 100 -> 200 -> 400 (cap).
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 400, 3);
        forceBlocked(l);
        // Run a few probe-and-fail cycles to drive cooldown toward max
        for (int cycle = 0; cycle < 4; cycle++) {
            Thread.sleep(500); // well past max cooldown
            take(l);     // probe
            l.onRateLimitError(FAILURE); // probe fails
            assertEquals(CircuitState.BLOCKED, l.getCircuitState());
            assertTrue(l.getCooldownRemainingMs() <= 400, "cooldown never exceeds the cap");
        }
        // Cooldown should be capped at 400ms. Wait exactly past the cap and verify.
        Thread.sleep(500);
        assertTrue(take(l), "probe should be allowed after max-capped cooldown");
    }

    // ======================== Probe self-healing tests ========================

    @Test
    public void testGiveRevertsPendingProbe() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        forceBlocked(l);
        Thread.sleep(150);
        take(l); // probe
        assertEquals(CircuitState.PROBING, l.getCircuitState());
        // Rollback or compensation without any signal
        l.give(units(1));
        assertEquals(CircuitState.BLOCKED, l.getCircuitState(), "a refund with no prior signal self-heals to BLOCKED");
    }

    @Test
    public void testGiveAfterSuccessIsNoop() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        forceBlocked(l);
        Thread.sleep(150);
        take(l); // probe
        l.onSuccess();   // transitions to HEALTHY
        assertEquals(CircuitState.HEALTHY, l.getCircuitState());
        l.give(units(1)); // should NOT revert
        assertEquals(CircuitState.HEALTHY, l.getCircuitState(), "a refund after onSuccess must not disturb HEALTHY");
    }

    @Test
    public void testGiveAfterFailureIsNoop() throws InterruptedException {
        ElasticWindowRateLimiter l = limiter(1000, 10, 100, 1000, 3);
        forceBlocked(l);
        Thread.sleep(150);
        take(l); // probe
        l.onRateLimitError(FAILURE); // signals probe failure -> BLOCKED with doubled cooldown
        assertEquals(CircuitState.BLOCKED, l.getCircuitState());
        l.give(units(1)); // should NOT disturb BLOCKED
        assertEquals(CircuitState.BLOCKED, l.getCircuitState(), "a refund after onRateLimitError must not disturb BLOCKED");
    }

    @Test
    public void testAProbeWithoutAVerdictExpires() throws InterruptedException {
        // A probe job that dies without signalling must not leave the circuit PROBING forever.
        ElasticWindowRateLimiter l = limiter(1000, 10, 50, 1000, 3, 100);
        forceBlocked(l);
        Thread.sleep(80);
        assertTrue(take(l), "the head claims the probe");
        assertEquals(CircuitState.PROBING, l.getCircuitState());
        assertThrows(UncorrectableRuntimeLLMException.class, () -> l.fits(units(1), NONE), "while the probe is in flight everyone is refused");

        Thread.sleep(150); // past the probe timeout

        assertTrue(l.fits(units(1), NONE), "the expired probe fell back to BLOCKED with its cooldown already over, so the head may probe again");
        assertEquals(CircuitState.BLOCKED, l.getCircuitState());
    }

    // ======================== Concurrent tests ========================

    @Test
    public void testConcurrentProbeClaim() throws Exception {
        // N threads race to claim the probe after cooldown expires.
        // Exactly one should become the probe, others should be refused.
        final int N = 50;
        ElasticWindowRateLimiter l = limiter(1000, N * 2, 50, 10_000, 3);
        forceBlocked(l);
        Thread.sleep(100); // past cooldown

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(N);
        AtomicInteger probesWon = new AtomicInteger(0);
        AtomicInteger refused = new AtomicInteger(0);
        ExecutorService exec = Executors.newFixedThreadPool(N);
        try {
            for (int i = 0; i < N; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();
                        if (take(l)) {
                            probesWon.incrementAndGet();
                        }
                    }
                    catch (UncorrectableRuntimeLLMException expected) {
                        refused.incrementAndGet();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS), "all threads should finish within 5s");
        }
        finally {
            exec.shutdownNow();
        }
        assertEquals(1, probesWon.get(), "exactly one thread should become the probe");
        assertEquals(N - 1, refused.get(), "all other threads should be refused");
        assertEquals(CircuitState.PROBING, l.getCircuitState());
    }

    @Test
    public void testConcurrentFailuresDontCorruptCircuit() throws Exception {
        // Many concurrent failures, circuit should open exactly once and stay BLOCKED.
        ElasticWindowRateLimiter l = limiter(1000, 1000, 10_000, 60_000, 3);
        final int N = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(N);
        ExecutorService exec = Executors.newFixedThreadPool(20);
        try {
            for (int i = 0; i < N; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();
                        l.onRateLimitError(FAILURE);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS));
        }
        finally {
            exec.shutdownNow();
        }
        assertEquals(CircuitState.BLOCKED, l.getCircuitState(), "circuit should be BLOCKED after many failures");
        assertEquals(9.0, l.getThrottleCoefficient(), 0.001, "throttle at max");
    }

    @Test
    public void testConcurrentStatusReadsNeverReleaseLiveStamps() throws Exception {
        // Under concurrent cleanup pressure, verify no non-expired timestamps are removed.
        final int CAPACITY = 20;
        ElasticWindowRateLimiter l = limiter(500, CAPACITY);

        for (int i = 0; i < CAPACITY; i++) {
            take(l);
        }
        assertEquals(0, l.getStatus().availablePermits());

        // Hammer cleanup via many concurrent getStatus calls BEFORE the window expires.
        final int N = 200;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(N);
        ExecutorService exec = Executors.newFixedThreadPool(20);
        try {
            for (int i = 0; i < N; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();
                        l.getStatus();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(5, TimeUnit.SECONDS));
        }
        finally {
            exec.shutdownNow();
        }
        assertEquals(0, l.getStatus().availablePermits(), "no stamp is released by concurrent reads before the window expires");

        Thread.sleep(600);
        assertEquals(CAPACITY, l.getStatus().availablePermits(), "all permits should be released after window expires");
    }

    @Test
    public void testValidationRejectsBadConfig() {
        // maxRequests <= 0
        configBaseWindowMs.set(1000L);
        configMaxRequests.set(0);
        configInitialCooldownMs.set(100L);
        configMaxCooldownMs.set(1000L);
        configFailuresAtMaxToBlock.set(3);
        configProbeTimeoutMs.set(1000L);
        try {
            assertThrows(IllegalArgumentException.class, ConfigurableTestLimiter::new);
        }
        finally {
            clearConfig();
        }

        // baseWindowMs <= 0
        configBaseWindowMs.set(0L);
        configMaxRequests.set(10);
        configInitialCooldownMs.set(100L);
        configMaxCooldownMs.set(1000L);
        configFailuresAtMaxToBlock.set(3);
        configProbeTimeoutMs.set(1000L);
        try {
            assertThrows(IllegalArgumentException.class, ConfigurableTestLimiter::new);
        }
        finally {
            clearConfig();
        }

        // maxCooldown < initialCooldown
        configBaseWindowMs.set(1000L);
        configMaxRequests.set(10);
        configInitialCooldownMs.set(1000L);
        configMaxCooldownMs.set(500L);
        configFailuresAtMaxToBlock.set(3);
        configProbeTimeoutMs.set(1000L);
        try {
            assertThrows(IllegalArgumentException.class, ConfigurableTestLimiter::new);
        }
        finally {
            clearConfig();
        }

        // probeTimeout <= 0
        configBaseWindowMs.set(1000L);
        configMaxRequests.set(10);
        configInitialCooldownMs.set(100L);
        configMaxCooldownMs.set(1000L);
        configFailuresAtMaxToBlock.set(3);
        configProbeTimeoutMs.set(0L);
        try {
            assertThrows(IllegalArgumentException.class, ConfigurableTestLimiter::new);
        }
        finally {
            clearConfig();
        }
    }

    // ======================== Identity, defaults and the clock ========================

    /** A window whose simple name carries the suffix the default name drops. */
    private static class SuffixedRateLimiter extends ConfigurableTestLimiter {
    }

    /** A window on a clock the test drives, for the recovery and cooldown intervals. */
    private static class ClockedLimiter extends ConfigurableTestLimiter {
        private final AtomicLong clock = new AtomicLong(1_000_000_000L);

        @Override
        long nanoTime() {
            return clock.get();
        }
    }

    private static class NegativeMaxThrottle extends ConfigurableTestLimiter {
        @Override protected double getMaxThrottle() { return -1; }
    }

    private static class NoInitialCooldown extends ConfigurableTestLimiter {
        @Override protected long getInitialCooldownMs() { return 0; }
    }

    private static class NoFailuresToBlock extends ConfigurableTestLimiter {
        @Override protected int getFailuresAtMaxToBlock() { return 0; }
    }

    private static class NoSuccessesPerDecrease extends ConfigurableTestLimiter {
        @Override protected int getSuccessesPerDecrease() { return 0; }
    }

    private static <L extends ConfigurableTestLimiter> L configured(java.util.function.Supplier<L> constructor) {
        configBaseWindowMs.set(1000L);
        configMaxRequests.set(10);
        configInitialCooldownMs.set(100L);
        configMaxCooldownMs.set(1000L);
        configFailuresAtMaxToBlock.set(3);
        configProbeTimeoutMs.set(1000L);
        try {
            return constructor.get();
        }
        finally {
            clearConfig();
        }
    }

    @Test
    public void aWindowNeedsTheHttpGate() {
        assertTrue(limiter(1000, 10).requiresHttpConnection(), "every elastic window is an HTTP account");
    }

    @Test
    public void theDefaultNameDropsTheRateLimiterSuffix() {
        assertEquals("Suffixed", configured(SuffixedRateLimiter::new).limiterName());
        assertEquals("ConfigurableTestLimiter", limiter(1000, 10).limiterName(), "a name without the suffix is used as it is");
    }

    @Test
    public void cooldownRemainingIsZeroUnlessBlocked() {
        ElasticWindowRateLimiter l = limiter(1000, 10, 10_000, 60_000, 3);
        assertEquals(0, l.getCooldownRemainingMs(), "healthy: nothing to wait out");
        forceBlocked(l);
        assertTrue(l.getCooldownRemainingMs() > 9_000, "blocked: the cooldown left");
    }

    @Test
    public void theRemainingConstructorBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> configured(NegativeMaxThrottle::new), "maxThrottle must be non-negative");
        assertThrows(IllegalArgumentException.class, () -> configured(NoInitialCooldown::new), "initialCooldownMs must be positive");
        assertThrows(IllegalArgumentException.class, () -> configured(NoFailuresToBlock::new), "failuresAtMaxToBlock must be positive");
        assertThrows(IllegalArgumentException.class, () -> configured(NoSuccessesPerDecrease::new), "successesPerDecrease must be positive");
    }

    @Test
    public void fiveMinutesWithoutPressure_recoverFasterPerSuccess() {
        ClockedLimiter l = configured(ClockedLimiter::new);
        l.onRateLimitError(FAILURE);
        assertEquals(1.0, l.getThrottleCoefficient(), 0.001);
        l.onSuccess();
        assertEquals(1.0, l.getThrottleCoefficient(), 0.001, "inside five minutes one success is not yet a decrement");
        l.clock.addAndGet(TimeUnit.MINUTES.toNanos(5));
        l.onSuccess();
        assertEquals(0.5, l.getThrottleCoefficient(), 0.001, "after five minutes without pressure one success relaxes by two and a half decrements");
    }

    @Test
    public void theCooldownIsReadOffTheSameClockAsTheWindow() {
        ClockedLimiter l = configured(ClockedLimiter::new);
        forceBlocked(l);
        assertEquals(100, l.getCooldownRemainingMs(), "the initial cooldown on a still clock");
        l.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(99));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> l.fits(units(1), NONE), "one millisecond short");
        l.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertTrue(take(l), "the cooldown elapsed on the clock and the head claims the probe");
        assertEquals(CircuitState.PROBING, l.getCircuitState());
    }

    // ======================== Soak test ========================

    @Test
    public void testSoakMixedOperations() throws Exception {
        ElasticWindowRateLimiter l = limiter(500, 50, 100, 1000, 3);
        final int N_THREADS = 20;
        final int OPS_PER_THREAD = 500;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(N_THREADS);
        List<Throwable> failures = new ArrayList<>();
        ExecutorService exec = Executors.newFixedThreadPool(N_THREADS);
        try {
            for (int t = 0; t < N_THREADS; t++) {
                final int tid = t;
                exec.submit(() -> {
                    try {
                        startGate.await();
                        for (int op = 0; op < OPS_PER_THREAD; op++) {
                            int r = (tid * 31 + op * 17) % 10;
                            switch (r) {
                                case 0, 1, 2, 3 -> { // 40% take + give
                                    try {
                                        if (take(l)) {
                                            l.give(units(1));
                                        }
                                    }
                                    catch (UncorrectableRuntimeLLMException ignored) {
                                        // Circuit BLOCKED/PROBING - expected during soak
                                    }
                                }
                                case 4, 5 -> l.onRateLimitError(FAILURE); // 20% failure
                                case 6, 7, 8 -> l.onSuccess();      // 30% success
                                case 9 -> l.onAdvisoryPressure();   // 10% advisory
                            }
                        }
                    }
                    catch (Throwable th) {
                        synchronized (failures) { failures.add(th); }
                    }
                    finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(30, TimeUnit.SECONDS), "soak test should finish within 30s");
        }
        finally {
            exec.shutdownNow();
        }
        assertTrue(failures.isEmpty(), "no uncaught exceptions from soak test: " + failures);
        // After soak, state should still be a valid CircuitState (not null/corrupt)
        CircuitState finalState = l.getCircuitState();
        assertNotNull(finalState);
        // Throttle should be within [0, maxThrottle]
        double throttle = l.getThrottleCoefficient();
        assertTrue(throttle >= 0.0 && throttle <= 9.0, "throttle within bounds after soak: " + throttle);
        // Available permits should be in [0, maxRequests]
        long permits = l.getStatus().availablePermits();
        assertTrue(permits >= 0 && permits <= 50, "permits within bounds after soak: " + permits);
    }
}
