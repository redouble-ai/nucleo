/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.http.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which account a model binding demands follows its entry: an entry bounded by a concurrency
 * ({@code max_concurrent}) demands one permit on its gate, named after the entry and held for
 * the call; an entry bounded by a quota window demands its priced token reservation on the
 * entry's bucket. A decision job is a resourceful job like any other, so it demands the
 * HTTP gate beside its model account.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class DecisionDemandTest {

    private static StandardModelSpec decision(String id, Integer maxConcurrent, int tpm) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(id);
        spec.setIdentity(id);
        spec.setProviderKey("systemone-decision");
        spec.setWireModelId("kev-latest");
        spec.setMaxContextTokens(4096);
        spec.setMaxConcurrent(maxConcurrent);
        spec.setTpm(tpm);
        return spec;
    }

    @Test
    void aConcurrencyBoundedEntryDemandsOnePermitOnItsGate() {
        StandardModelSpec local = decision("kev-gated", 1, 0);
        JobRequirements requirements = new JobRequirements();
        ModelBinding binding = requirements.requireDecision(local, 40);
        binding.resolve(local);
        binding.price();
        Demand demand = JobResources.demandOf(requirements);
        ModelGate gate = RateLimiterRegistry.getInstance().gate(local);
        Demand.Entry<?> entry = demand.getEntries().stream().filter(e -> e.getLimiter() == gate).findFirst().orElseThrow();
        assertEquals(1, entry.getAmounts().size(), "one permit, the request in flight");
        assertEquals("kev-gated", gate.limiterName(), "named after the entry, as the bucket would be");
        assertEquals(1, gate.capacity());
        assertEquals(RateLimiter.Replenishment.RELEASE, gate.replenishment(), "returned when the call ends, never with the clock");
        assertTrue(demand.getEntries().stream().anyMatch(e -> e.getLimiter() == HttpConnectionPools.getInstance().gate()), "and the HTTP gate beside it");
        assertTrue(requirements.requiresDecision());
        assertFalse(requirements.requiresLlm());
        assertTrue(requirements.requiresResources());
        assertSame(gate, RateLimiterRegistry.getInstance().gate(local), "one gate per entry");
    }

    @Test
    void aWindowBoundedEntryDemandsItsTokenReservationOnTheBucket() {
        StandardModelSpec hosted = decision("jev-windowed", null, 15000000);
        hosted.setRpm(1200);
        JobRequirements requirements = new JobRequirements();
        ModelBinding binding = requirements.requireDecision(hosted, 40);
        binding.resolve(hosted);
        binding.price();
        Demand demand = JobResources.demandOf(requirements);
        TokenBucketRateLimiter bucket = RateLimiterRegistry.getInstance().getRateLimiter(hosted);
        Demand.Entry<?> entry = demand.getEntries().stream().filter(e -> e.getLimiter() == bucket).findFirst().orElseThrow();
        assertEquals(40, entry.getAmounts().get(0), "the priced input reservation");
        assertThrows(IllegalArgumentException.class, () -> new ModelGate(hosted), "a windowed entry has no gate");
    }
}
