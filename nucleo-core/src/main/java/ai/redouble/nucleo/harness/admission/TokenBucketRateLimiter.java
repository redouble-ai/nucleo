/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Dual-bucket token rate limiter (requests per minute + tokens per minute) with adaptive
 * throttling, as an admission account: it never blocks, {@link ai.redouble.nucleo.harness.admission.Admission}
 * does. Any upstream slow-down signal stretches the effective refill window via
 * {@link #recordBackpressure(int)}, weighted by how hard the signal asks the fleet to yield (a
 * 429 one increment via {@link #record429()}, a 529 several - see
 * {@code UpstreamRetryException.backpressureIncrements}); sustained successes relax it back.
 * The loop deliberately seeks the boundary - occasional 429s are acceptable feedback signals
 * that we are maximizing throughput.
 *
 * <p>The bucket is clocked, not ticked: availability at any instant is the stored level plus
 * what the rate has refilled since it was last read, capped at the configured budget. There is
 * no refill thread. {@link #earliestFit} tells the admission evaluator the exact instant a
 * shortfall closes, and the evaluator parks until then.
 *
 * <p>The controller does not distinguish 429 subtypes (acceleration / capacity / per-second
 * burst / vendor-specific). Any "slow down" signal feeds the same loop, which converges on
 * whatever rate the provider is actually willing to serve without needing vendor-specific
 * header parsing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-12)
 */
public class TokenBucketRateLimiter extends AbstractRateLimiter<Integer> {
    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /** A throttle factor to one decimal, the precision the log lines read at. */
    private static String tenths(double value) {
        return String.format("%.1f", value);
    }
    private static final double THROTTLE_INCREMENT = 0.5;
    private static final double THROTTLE_DECREMENT = 0.2;
    private static final double MAX_THROTTLE = 9.0;
    private static final int SUCCESSES_PER_DECREASE = 3;
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);


    /**
     * Configuration for a rate limiter.
     */
    public static class Config {
        private final int requestsPerMinute;
        private final int tokensPerMinute;

        public Config(int requestsPerMinute, int tokensPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
            this.tokensPerMinute = tokensPerMinute;
        }

        public int getRequestsPerMinute() {return requestsPerMinute;}

        public int getTokensPerMinute() {return tokensPerMinute;}
    }


    private final String modelName;
    private final LongSupplier nanos;
    // All of the following are guarded by the instance monitor.
    private Config config;
    private double requests;
    private double tokens;
    private long lastNanos;
    private double throttleCoefficient = 0.0;
    private int successCounter = 0;
    private long lastBackpressureNanos;

    /**
     * Creates a new rate limiter with the given configuration.
     *
     * @param modelName the model identifier used as the limiter name for
     *                  observability events (dashboards, logs, traces)
     * @param config    rate-limit configuration
     */
    public TokenBucketRateLimiter(String modelName, Config config) {
        this(modelName, config, System::nanoTime);
    }

    /**
     * Test seam: the clock the bucket refills against.
     */
    TokenBucketRateLimiter(String modelName, Config config, LongSupplier nanos) {
        this.modelName = modelName;
        this.config = config;
        this.nanos = nanos;
        this.requests = config.requestsPerMinute;
        this.tokens = config.tokensPerMinute;
        this.lastNanos = nanos.getAsLong();
        this.lastBackpressureNanos = this.lastNanos;
    }

    @Override
    public boolean fits(List<Integer> mine, List<Integer> reservedAhead) {
        int needed = sum(mine);
        refuseIfImpossible(mine, needed);
        int reservedTokens = sum(reservedAhead);
        synchronized (this) {
            refresh(nanos.getAsLong());
            return tokens >= needed + reservedTokens && requests >= mine.size() + reservedAhead.size();
        }
    }

    @Override
    public boolean tryTake(List<Integer> mine, List<Integer> reservedAhead) {
        int needed = sum(mine);
        refuseIfImpossible(mine, needed);
        int reservedTokens = sum(reservedAhead);
        synchronized (this) {
            refresh(nanos.getAsLong());
            if (tokens < needed + reservedTokens || requests < mine.size() + reservedAhead.size()) {
                return false;
            }
            tokens -= needed;
            requests -= mine.size();
            return true;
        }
    }

    /**
     * Refunds a debit whose request never went upstream: rollback and grant compensation.
     * Bounded by the configured caps.
     */
    @Override
    public void give(List<Integer> amounts) {
        int returned = sum(amounts);
        synchronized (this) {
            tokens = Math.min(config.tokensPerMinute, tokens + returned);
            requests = Math.min(config.requestsPerMinute, requests + amounts.size());
            log.debug("Refunded {} tokens - requests: {}/{}, tokens: {}/{}", returned, Math.round(requests), config.requestsPerMinute, Math.round(tokens), config.tokensPerMinute);
        }
        capacityChanged();
    }

    @Override
    public Long earliestFit(List<Integer> mine, List<Integer> reservedAhead) {
        int neededTokens = sum(mine) + sum(reservedAhead);
        int neededRequests = mine.size() + reservedAhead.size();
        synchronized (this) {
            if (neededTokens > config.tokensPerMinute || neededRequests > config.requestsPerMinute) {
                return null;
            }
            long now = nanos.getAsLong();
            refresh(now);
            double tokenDeficit = neededTokens - tokens;
            double requestDeficit = neededRequests - requests;
            if (tokenDeficit <= 0 && requestDeficit <= 0) {
                return now;
            }
            double windowNanos = WINDOW_NANOS * (1.0 + throttleCoefficient);
            double tokenWait = tokenDeficit <= 0 ? 0 : tokenDeficit / config.tokensPerMinute * windowNanos;
            double requestWait = requestDeficit <= 0 ? 0 : requestDeficit / config.requestsPerMinute * windowNanos;
            return now + (long)Math.ceil(Math.max(tokenWait, requestWait));
        }
    }

    /**
     * Advances the stored level to {@code now}: the rate refills both dimensions, stretched by the
     * throttle coefficient, capped at the configured budget. Caller holds the monitor.
     */
    private void refresh(long now) {
        long elapsed = now - lastNanos;
        if (elapsed <= 0) {
            return;
        }
        double windowNanos = WINDOW_NANOS * (1.0 + throttleCoefficient);
        double fraction = elapsed / windowNanos;
        tokens = Math.min(config.tokensPerMinute, tokens + config.tokensPerMinute * fraction);
        requests = Math.min(config.requestsPerMinute, requests + config.requestsPerMinute * fraction);
        lastNanos = now;
    }

    private void refuseIfImpossible(List<Integer> mine, int needed) {
        Config current;
        synchronized (this) {
            current = config;
        }
        if (needed > current.tokensPerMinute) {
            throw new UncorrectableRuntimeLLMException(
                    "Reservation of " + needed + " tokens exceeds the " + current.tokensPerMinute + " tokens-per-minute budget of " + modelName);
        }
        if (mine.size() > current.requestsPerMinute) {
            throw new UncorrectableRuntimeLLMException(
                    "A demand of " + mine.size() + " requests exceeds the " + current.requestsPerMinute + " requests-per-minute budget of " + modelName);
        }
    }

    private static int sum(List<Integer> amounts) {
        int total = 0;
        for (Integer amount : amounts) {
            total += amount;
        }
        return total;
    }

    /**
     * Updates RPM/TPM caps in place. Preserves adaptive state (throttle coefficient, success
     * counter, last-backpressure timestamp). When caps shrink, current allowances are clamped
     * down so the invariant {@code available <= capacity} holds immediately; when caps grow,
     * the admission evaluator is woken so a parked shortfall can be re-evaluated. No-op if the
     * new config matches the current rpm/tpm pair.
     *
     * <p>Called by {@link RateLimiterFactory#updateLimits} whenever the
     * upstream provider reports its actual ceilings in response headers.
     */
    public void setConfig(Config newConfig) {
        boolean grew;
        Config current;
        synchronized (this) {
            current = config;
            if (current.requestsPerMinute == newConfig.requestsPerMinute && current.tokensPerMinute == newConfig.tokensPerMinute) {
                return;
            }
            refresh(nanos.getAsLong());
            config = newConfig;
            requests = Math.min(requests, newConfig.requestsPerMinute);
            tokens = Math.min(tokens, newConfig.tokensPerMinute);
            grew = newConfig.requestsPerMinute > current.requestsPerMinute || newConfig.tokensPerMinute > current.tokensPerMinute;
        }
        log.info("Updated rate limits for {} (RPM: {} -> {}, TPM: {} -> {})", modelName, current.requestsPerMinute, newConfig.requestsPerMinute, current.tokensPerMinute, newConfig.tokensPerMinute);
        if (grew) {
            capacityChanged();
        }
    }

    /**
     * Records a 429 response of any kind. Stretches the refill window by
     * incrementing the throttle coefficient; the bucket then refills slower
     * until successes accumulate and {@link #recordSuccess} brings the
     * coefficient back down. The caller does not distinguish 429 subtypes
     * (acceleration / capacity / per-second burst / vendor-specific): any
     * upstream "slow down" signal feeds this single loop, which converges
     * on whatever rate the provider is actually willing to serve.
     */
    public void record429() {
        recordBackpressure(1);
    }

    /**
     * Feeds an upstream slow-down signal into the adaptive throttle, weighted by how
     * hard the signal asks the fleet to yield: a 429 records one increment (this
     * caller nudged past its own budget - a small correction converges), a 529
     * records several (the provider's whole fleet is saturated and every concurrent
     * job is hearing the same thing - yield hard now, let {@link #recordSuccess}
     * walk it back). The weight comes from
     * {@link UpstreamRetryException#backpressureIncrements()}. A slower rate wakes
     * nobody: a deadline computed under the old rate merely re-evaluates early.
     */
    public void recordBackpressure(int increments) {
        double oldThrottle;
        double newThrottle;
        synchronized (this) {
            refresh(nanos.getAsLong());
            oldThrottle = throttleCoefficient;
            throttleCoefficient = Math.min(throttleCoefficient + increments * THROTTLE_INCREMENT, MAX_THROTTLE);
            newThrottle = throttleCoefficient;
            lastBackpressureNanos = nanos.getAsLong();
        }
        log.debug("backpressure x{} - throttle {} -> {} ({}x slower)", increments, tenths(oldThrottle), tenths(newThrottle), tenths(1.0 + newThrottle));
    }

    @Override
    public void onSuccess() {
        recordSuccess();
    }

    /** Logs the upstream's summary at warn, so the cause is on record, and records one increment through {@link #record429()}. */
    @Override
    public void onRateLimitError(UpstreamFailure failure) {
        log.warn("{} upstream failure: {}", modelName, failure.summary());
        record429();
    }

    @Override
    public Replenishment replenishment() {
        return Replenishment.TIME;
    }

    @Override
    public String limiterName() {
        return modelName;
    }

    @Override
    public String limiterCategory() {
        return "token_bucket";
    }

    @Override
    public long capacity() {
        synchronized (this) {
            return config.tokensPerMinute;
        }
    }

    @Override
    public long currentInUse() {
        synchronized (this) {
            refresh(nanos.getAsLong());
            return Math.max(0, (long)(config.tokensPerMinute - tokens));
        }
    }

    @Override
    public String statusIndicator() {
        double throttle = getThrottleCoefficient();
        if (throttle <= 0.0) {
            return null;
        }
        return String.format("throttle:%.1fx", 1.0 + throttle);
    }

    /**
     * Records a successful request. Every third success takes {@code 0.2} off the throttle;
     * after five minutes without a backpressure signal every success takes {@code 0.5}, so a
     * spike is recovered from quickly; a success at throttle zero does nothing. A relaxation
     * wakes the admission evaluator: a faster refill moves every parked deadline earlier.
     * Occasional 429s are acceptable feedback signals for optimal throughput.
     */
    public void recordSuccess() {
        boolean relaxed = false;
        double oldThrottle = 0;
        double newThrottle = 0;
        String recoveryType = null;
        synchronized (this) {
            if (throttleCoefficient > 0) {
                refresh(nanos.getAsLong());
                long minutesSinceLast429 = TimeUnit.NANOSECONDS.toMinutes(nanos.getAsLong() - lastBackpressureNanos);
                boolean timeBasedRecovery = minutesSinceLast429 >= 5;
                double decrementAmount = timeBasedRecovery ? THROTTLE_DECREMENT * 2.5 : THROTTLE_DECREMENT;
                int successesNeeded = timeBasedRecovery ? 1 : SUCCESSES_PER_DECREASE;
                successCounter++;
                if (successCounter >= successesNeeded) {
                    successCounter = 0;
                    oldThrottle = throttleCoefficient;
                    throttleCoefficient = Math.max(throttleCoefficient - decrementAmount, 0.0);
                    newThrottle = throttleCoefficient;
                    relaxed = oldThrottle != newThrottle;
                    recoveryType =
                            timeBasedRecovery ? String.format("time-based recovery (%d min no 429s, -%.1f per success)", minutesSinceLast429, decrementAmount) : String.format("after %d successes", SUCCESSES_PER_DECREASE);
                }
            }
        }
        if (relaxed) {
            log.debug("Throttle {} -> {} {} (seeking boundary)", tenths(oldThrottle), tenths(newThrottle), recoveryType);
            capacityChanged();
        }
    }

    /**
     * Gets current throttle coefficient.
     */
    public double getThrottleCoefficient() {
        synchronized (this) {
            return throttleCoefficient;
        }
    }

    /**
     * Gets the current available capacity, computed at this instant from the clock.
     */
    @Override
    public TokenBucketStatus getStatus() {
        synchronized (this) {
            refresh(nanos.getAsLong());
            return new TokenBucketStatus(config.requestsPerMinute, (long)requests, config.tokensPerMinute, (long)tokens, Instant.now());
        }
    }
}
