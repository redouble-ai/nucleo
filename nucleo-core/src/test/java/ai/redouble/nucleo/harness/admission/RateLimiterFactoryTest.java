/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RateLimiterFactory} and its per-model facade {@link RateLimiterRegistry}: one instance
 * per concrete limiter class built through a no-arg constructor, one bucket per spec keyed by the
 * spec's id and seeded from its tpm and rpm with {@code tpm / 1000} standing in for an
 * unpublished rpm, limits updated in place with the adaptive state kept, a pair that is not
 * both positive ignored, and {@code clear} emptying everything.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class RateLimiterFactoryTest {

    /** The shape a concrete limiter class takes: a package-private no-arg constructor. */
    static final class UnitGate extends CountingGate {
        UnitGate() {
            super(1);
        }

        @Override
        public String limiterName() {
            return "unit";
        }
    }

    /** The same shape with a public constructor: visibility does not matter to the factory. */
    public static final class PublicGate extends CountingGate {
        public PublicGate() {
            super(1);
        }

        @Override
        public String limiterName() {
            return "public";
        }
    }

    /** A limiter class the factory cannot build. */
    public static final class NeedsACapacity extends CountingGate {
        public NeedsACapacity(int capacity) {
            super(capacity);
        }

        @Override
        public String limiterName() {
            return "needs-a-capacity";
        }
    }

    private static StandardModelSpec spec(String id, int rpm, int tpm) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(id);
        spec.setRpm(rpm);
        spec.setTpm(tpm);
        return spec;
    }

    @AfterEach
    void clear() {
        RateLimiterFactory.getInstance().clear();
    }

    @Test
    void aConcreteClassHasOneInstanceUntilCleared_whateverItsConstructorsVisibility() {
        UnitGate first = RateLimiterFactory.getInstance().getRateLimiter(UnitGate.class);
        assertSame(first, RateLimiterFactory.getInstance().getRateLimiter(UnitGate.class), "one instance per concrete class");
        assertNotNull(RateLimiterFactory.getInstance().getRateLimiter(PublicGate.class), "a public no-arg constructor serves as well as a package-private one");
        RateLimiterFactory.getInstance().clear();
        assertNotSame(first, RateLimiterFactory.getInstance().getRateLimiter(UnitGate.class), "clear forgets it");
    }

    @Test
    void aClassWithoutANoArgConstructorIsRefusedAsADeploymentFault() {
        ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException refusal = assertThrows(ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException.class,
                () -> RateLimiterFactory.getInstance().getRateLimiter(NeedsACapacity.class));
        assertTrue(refusal.getMessage().contains(NeedsACapacity.class.getName()), "the refusal names the class: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("no no-arg constructor"), "and the reason: " + refusal.getMessage());
        assertFalse(refusal.isCorrectable(), "nothing a model can change about a deployment's limiter class");
        assertInstanceOf(NoSuchMethodException.class, refusal.getCause(), "the reflective failure is the cause");
    }

    @Test
    void aSpecIsSeededFromItsPair_andAnUnpublishedRpmIsTpmOverAThousand() {
        TokenBucketRateLimiter published = RateLimiterFactory.getInstance().getRateLimiter(spec("published", 7, 90_000));
        assertEquals(7, published.getStatus().maxRequests());
        assertEquals(90_000, published.capacity(), "capacity is the token budget");
        TokenBucketRateLimiter unpublished = RateLimiterFactory.getInstance().getRateLimiter(spec("unpublished", 0, 120_000));
        assertEquals(120, unpublished.getStatus().maxRequests(), "no rpm published: tpm / 1000");
        assertEquals("unpublished", unpublished.limiterName(), "the bucket is named after the spec");
    }

    @Test
    void specsAreKeyedByTheirId() {
        TokenBucketRateLimiter one = RateLimiterFactory.getInstance().getRateLimiter(spec("same-id", 10, 1_000));
        TokenBucketRateLimiter again = RateLimiterFactory.getInstance().getRateLimiter(spec("same-id", 99, 9_999));
        assertSame(one, again, "a second spec object with the same id is the same bucket, and its numbers are not re-read");
        assertNotSame(one, RateLimiterFactory.getInstance().getRateLimiter(spec("other-id", 10, 1_000)), "another id is another bucket");
    }

    @Test
    void updateLimitsAppliesInPlace_keepsTheThrottle_andCreatesWhenAbsent() {
        StandardModelSpec spec = spec("live", 10, 1_000);
        TokenBucketRateLimiter bucket = RateLimiterFactory.getInstance().getRateLimiter(spec);
        bucket.record429();
        double throttle = bucket.getThrottleCoefficient();
        RateLimiterFactory.getInstance().updateLimits(spec, 50, 5_000);
        assertSame(bucket, RateLimiterFactory.getInstance().getRateLimiter(spec), "the same instance carries the new caps");
        assertEquals(5_000, bucket.capacity());
        assertEquals(50, bucket.getStatus().maxRequests());
        assertEquals(throttle, bucket.getThrottleCoefficient(), 0.0001, "the adaptive state survives a header update");
        StandardModelSpec fresh = spec("fresh", 10, 1_000);
        RateLimiterFactory.getInstance().updateLimits(fresh, 20, 2_000);
        assertEquals(2_000, RateLimiterFactory.getInstance().getRateLimiter(fresh).capacity(), "no entry yet: the headers seed a new bucket");
    }

    @Test
    void updateLimitsIgnoresAPairThatIsNotBothPositive() {
        StandardModelSpec spec = spec("headers", 10, 1_000);
        RateLimiterFactory.getInstance().updateLimits(spec, 0, 9_000);
        TokenBucketRateLimiter bucket = RateLimiterFactory.getInstance().getRateLimiter(spec);
        assertEquals(1_000, bucket.capacity(), "nothing was created from a zero rpm; the spec seeded the bucket");
        RateLimiterFactory.getInstance().updateLimits(spec, 10, 0);
        RateLimiterFactory.getInstance().updateLimits(spec, -1, 9_000);
        assertEquals(1_000, bucket.capacity(), "an existing bucket keeps its caps");
        assertEquals(10, bucket.getStatus().maxRequests());
    }

    @Test
    void theRegistryIsTheFactory() {
        StandardModelSpec spec = spec("facade", 10, 1_000);
        TokenBucketRateLimiter viaRegistry = RateLimiterRegistry.getInstance().getRateLimiter(spec);
        assertSame(RateLimiterFactory.getInstance().getRateLimiter(spec), viaRegistry, "one bucket, whichever door");
        assertEquals(1_000, RateLimiterRegistry.getInstance().getStatus(spec).maxTokens());
        RateLimiterRegistry.getInstance().updateLimits(spec, 20, 2_000);
        assertEquals(2_000, viaRegistry.capacity(), "the registry's update reaches the factory's bucket");
        RateLimiterRegistry.getInstance().clear();
        assertNotSame(viaRegistry, RateLimiterRegistry.getInstance().getRateLimiter(spec), "the registry's clear is the factory's");
    }
}
