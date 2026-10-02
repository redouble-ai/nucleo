/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A classifier's proposal for a decision model passes the deterministic gate only in the
 * decision kind's shape: no grade, and exactly one bound, a quota window or a concurrency.
 * The kind follows the provider key, as it does for the loader.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class ModelClassifierDecisionTest {
    private static final Map<String, ClientProvider<?>> PROVIDERS = Map.of("fake-decision", new FakeDecisionProvider());
    private static final Map<String, List<DiscoveredModel>> UNKNOWN = Map.of("fake-decision", List.of(DiscoveredModel.of("decide-x")));

    private static ObjectNode proposal() {
        ObjectNode proposal = NucleoJsonSerializer.createObjectNode();
        proposal.put("id", "decide-x");
        proposal.put("identity", "decide-x");
        proposal.put("provider_key", "fake-decision");
        proposal.put("wire_model_id", "decide-x");
        proposal.put("max_context_tokens", 8192);
        proposal.put("max_output_tokens", 0);
        proposal.put("input_price_per_million", 0.0);
        proposal.put("currency", "USD");
        return proposal;
    }

    private static String rejection(ObjectNode proposal) {
        return ModelClassifier.rejection(proposal, UNKNOWN, Map.of(), PROVIDERS);
    }

    @Test
    void aDecisionProposalPassesInItsKindsShapeAndInNoOther() {
        ObjectNode gated = proposal();
        gated.put("max_concurrent", 1);
        assertNull(rejection(gated), "a local decision model: one concurrency, no grade");
        ObjectNode windowed = proposal();
        windowed.put("tpm", 15000000);
        windowed.put("rpm", 1200);
        assertNull(rejection(windowed), "a hosted decision model: a quota window, no grade");
        ObjectNode graded = proposal();
        graded.put("max_concurrent", 1);
        graded.put("grade", "SMALL");
        assertEquals("a decision model carries no grade", rejection(graded));
        ObjectNode both = proposal();
        both.put("max_concurrent", 1);
        both.put("tpm", 15000000);
        assertEquals("a decision model states exactly one bound, tpm or max_concurrent", rejection(both));
        assertEquals("a decision model states exactly one bound, tpm or max_concurrent", rejection(proposal()), "neither bound is refused too");
    }
}
