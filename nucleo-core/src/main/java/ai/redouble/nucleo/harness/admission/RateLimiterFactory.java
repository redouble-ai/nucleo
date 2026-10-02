/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.slf4j.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Owns every rate limiter instance in the process: one per concrete limiter class, one per
 * {@link ModelSpec} - a token bucket for an entry bounded by a quota window, a
 * {@link ModelGate} for an entry bounded by a concurrency. Limiters are accounts with no
 * threads of their own, so the factory has nothing to stop; the admission evaluator that
 * parks on their deadlines belongs to the dispatcher.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-14)
 */
public class RateLimiterFactory {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterFactory.class);
    private static final RateLimiterFactory INSTANCE = new RateLimiterFactory();
    /**
     * Rate limiters by concrete class.
     * Each concrete class has exactly one instance.
     */
    private final Map<Class<?>, RateLimiter<?>> limitersByClass = new ConcurrentHashMap<>();
    /**
     * Rate limiters per model spec, keyed by the {@link ModelSpec} (equals by id). Endpoint
     * variants that share a wire model id still get separate buckets because their ids differ.
     */
    private final Map<ModelSpec, TokenBucketRateLimiter> limitersByModel = new ConcurrentHashMap<>();
    /** Gates per model spec whose entry declares {@code max_concurrent}, keyed the same way. */
    private final Map<ModelSpec, ModelGate> gatesByModel = new ConcurrentHashMap<>();
    private RateLimiterFactory() {
    }
    /**
     * Gets the singleton factory instance.
     */
    public static RateLimiterFactory getInstance() {
        return INSTANCE;
    }
    /**
     * Gets or creates the singleton instance of a concrete rate limiter class.
     *
     * @param clazz The concrete rate limiter class; it needs a no-arg constructor, of any visibility
     * @param <T> The rate limiter type
     * @return The singleton instance of this rate limiter
     * @throws UncorrectableRuntimeLLMException if the rate limiter cannot be instantiated: a
     *         deployment named a class the runtime cannot build, which no model can correct; the
     *         message names the class and the reason, and the reflective failure is the cause
     */
    @SuppressWarnings("unchecked")
    public <T extends RateLimiter<?>> T getRateLimiter(Class<T> clazz) {
        return (T) limitersByClass.computeIfAbsent(clazz, this::createInstance);
    }
    /**
     * Creates a new instance of the given rate limiter class through its no-arg constructor,
     * whatever its visibility. A class that has none, or whose constructor fails, is a
     * deployment fault: refused with the class and the reason named and the reflective
     * failure as the cause.
     */
    private RateLimiter<?> createInstance(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            RateLimiter<?> instance = (RateLimiter<?>) constructor.newInstance();
            log.info("Created rate limiter instance: {}", clazz.getSimpleName());
            return instance;
        }
        catch (NoSuchMethodException e) {
            throw new UncorrectableRuntimeLLMException(
                "Rate limiter " + clazz.getName() + " has no no-arg constructor; the runtime cannot build it", e);
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            throw new UncorrectableRuntimeLLMException(
                "Rate limiter " + clazz.getName() + " could not be built: " + e.getMessage(), e);
        }
    }
    /**
     * Gets or creates the rate limiter for the given model spec, seeded from the spec's
     * current tpm/rpm. A spec that publishes no rpm (zero or negative) gets {@code tpm / 1000}
     * as its request budget, the shape of every provider's published pair.
     *
     * @param model The model spec to get a rate limiter for
     * @return The rate limiter for this spec
     */
    public TokenBucketRateLimiter getRateLimiter(ModelSpec model) {
        return limitersByModel.computeIfAbsent(model, key -> {
            int rpm = key.getRpm() > 0 ? key.getRpm() : key.getTpm() / 1000;
            TokenBucketRateLimiter.Config config = new TokenBucketRateLimiter.Config(rpm, key.getTpm());
            log.info("Created rate limiter for {} (RPM: {}, TPM: {})", key.getId(), rpm, key.getTpm());
            return new TokenBucketRateLimiter(key.getId(), config);
        });
    }
    /**
     * Gets or creates the gate for a model spec whose entry declares {@code max_concurrent}:
     * one permit per request in flight, held for the call. Refuses a spec that declares none,
     * since its account is the token bucket of {@link #getRateLimiter(ModelSpec)}.
     *
     * @param model the model spec to get a gate for
     * @return the gate for this spec
     */
    public ModelGate gate(ModelSpec model) {
        return gatesByModel.computeIfAbsent(model, key -> {
            ModelGate gate = new ModelGate(key);
            log.info("Created model gate for {} (max concurrent: {})", key.getId(), gate.capacity());
            return gate;
        });
    }
    /**
     * Updates rate limits for a model based on API response headers. Applies
     * the new caps in place on the existing limiter (preserving its adaptive
     * throttle state); creates a fresh limiter only if no entry exists yet. A pair in which
     * either number is not positive is ignored: the headers carried nothing usable, and the
     * limiter keeps what it has.
     *
     * @param model The model
     * @param requestsPerMinute New RPM limit
     * @param tokensPerMinute New TPM limit
     */
    public void updateLimits(ModelSpec model, int requestsPerMinute, int tokensPerMinute) {
        if (requestsPerMinute <= 0 || tokensPerMinute <= 0) {
            return;
        }
        TokenBucketRateLimiter.Config newConfig = new TokenBucketRateLimiter.Config(requestsPerMinute, tokensPerMinute);
        limitersByModel.compute(model, (key, existing) -> {
            if (existing != null) {
                existing.setConfig(newConfig);
                return existing;
            }
            log.info("Created rate limiter for {} (RPM: {}, TPM: {})", key.getId(), requestsPerMinute, tokensPerMinute);
            return new TokenBucketRateLimiter(key.getId(), newConfig);
        });
    }
    /**
     * Clears all rate limiters (useful for testing).
     */
    public void clear() {
        limitersByClass.clear();
        limitersByModel.clear();
        gatesByModel.clear();
    }
}
