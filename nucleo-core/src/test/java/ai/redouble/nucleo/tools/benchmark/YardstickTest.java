/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which entry of an unordered strongest grade a benchmark measures against ({@link Yardstick}),
 * and the vendor a wire id names ({@link ModelLineage#vendorOf}).
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
class YardstickTest {
    private static final List<String> PREFERRED = Benchmark.YARDSTICK_VENDORS;

    private static Yardstick.Candidate entry(String id, String identity, String vendor, Double price) {
        return new Yardstick.Candidate(id, identity, vendor, price);
    }

    @Test
    void aPreferredVendorsNewestVersionComesFirstThoughAnOlderOneAndAnotherVendorCostMore() {
        List<String> ranked = Yardstick.rank(List.of(
                entry("kimi", "kimi-k3.1", "moonshotai", 3.1),
                entry("grok", "grok-4.7", "xai", 60.0),
                entry("opus-5", "opus-5", "anthropic", 33.0),
                entry("opus-55-mantle", "opus-5.5", "anthropic", 26.4),
                entry("opus-55-bedrock", "opus-5.5", "anthropic", 26.4)), PREFERRED);
        assertEquals(List.of("opus-55-bedrock", "opus-55-mantle", "opus-5", "grok", "kimi"), ranked,
                "preferred vendor, then the newest version, then the dearer price, then the id; every entry stays in the list");
    }

    @Test
    void amongPreferredVendorsTheDearerNewestModelComesFirst() {
        List<String> ranked = Yardstick.rank(List.of(
                entry("gpt", "gpt-6.2", "openai", 20.0),
                entry("opus", "opus-5.5", "anthropic", 26.4),
                entry("gpt-old", "gpt-6.1", "openai", 40.0)), PREFERRED);
        assertEquals(List.of("opus", "gpt", "gpt-old"), ranked);
    }

    @Test
    void withNoPreferredVendorTheGradeIsRankedByVersionAndPrice() {
        List<String> ranked = Yardstick.rank(List.of(
                entry("kimi", "kimi-k3.1", "moonshotai", 3.1),
                entry("unknown", "deployment-a", null, 9.0),
                entry("grok-old", "grok-4.6", "xai", 30.0),
                entry("grok", "grok-4.7", "xai", 18.0)), PREFERRED);
        assertEquals(List.of("grok", "unknown", "kimi", "grok-old"), ranked,
                "the newest of each model by price, dearest first, then the older versions");
    }

    @Test
    void anUnpricedCatalogIsRankedByVendorAndVersionAndAnUnpricedEntryComesAfterAPricedOne() {
        assertEquals(List.of("opus-55", "opus-5", "a", "b"), Yardstick.rank(List.of(
                entry("b", "beta-1", null, null),
                entry("opus-5", "opus-5", "anthropic", null),
                entry("a", "alpha-1", null, null),
                entry("opus-55", "opus-5.5", "anthropic", null)), PREFERRED));
        assertEquals(List.of("priced", "unpriced"), Yardstick.rank(List.of(
                entry("unpriced", "alpha-1", null, null),
                entry("priced", "beta-1", null, 1.0)), PREFERRED));
    }

    @Test
    void aSingleEntryAndAnEmptyGradeRankAsTheyAre() {
        assertEquals(List.of("only"), Yardstick.rank(List.of(entry("only", "kimi-k3.1", null, null)), PREFERRED));
        assertEquals(List.of(), Yardstick.rank(List.of(), PREFERRED));
    }

    @Test
    void theVendorIsReadOffAWireIdThatNamesOne() {
        assertEquals("anthropic", ModelLineage.vendorOf("us.anthropic.claude-opus-5-5"));
        assertEquals("anthropic", ModelLineage.vendorOf("anthropic.claude-fable-5"));
        assertEquals("openai", ModelLineage.vendorOf("global.openai.gpt-6.2-sol"));
        assertEquals("moonshotai", ModelLineage.vendorOf("us.moonshotai.kimi-k3.1"));
        assertNull(ModelLineage.vendorOf("claude-opus-4-7-20260301"), "a vendor's own endpoint names none");
        assertNull(ModelLineage.vendorOf("gpt-4.1"));
        assertNull(ModelLineage.vendorOf("my-deployment"));
    }
}
