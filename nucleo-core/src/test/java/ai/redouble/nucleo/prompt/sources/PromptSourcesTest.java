/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.sources;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the shipped generic sources' contracts: {@link StaticTextSource} serves its
 * construction-time text whatever the key; {@link AbTestSource} validates its probability
 * and forwards the key to the chosen branch; the key-threading sources
 * ({@link DbTextSource}, {@link RemoteTextSource}) hand the key to their caller-provided
 * function.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class PromptSourcesTest {

    @Test
    void staticTextSourceServesItsTextWhateverTheKey() {
        StaticTextSource source = new StaticTextSource("fixed");
        assertEquals("fixed", source.produce("any.key").asText());
        assertEquals("fixed", source.produce("other.key").asText());
    }

    @Test
    void abTestSourceValidatesItsProbability() {
        StaticTextSource a = new StaticTextSource("a");
        StaticTextSource b = new StaticTextSource("b");
        assertThrows(IllegalArgumentException.class, () -> new AbTestSource(a, b, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new AbTestSource(a, b, 1.1));
    }

    @Test
    void abTestSourceDispatchesByProbability_forwardingTheKey() {
        PromptSource echoA = key -> TextNode.valueOf("A:" + key);
        PromptSource echoB = key -> TextNode.valueOf("B:" + key);
        assertEquals("B:my.key", new AbTestSource(echoA, echoB, 0.0).produce("my.key").asText(),
                "probability 0 always takes branch B, and the branch sees the caller's key");
        assertEquals("A:my.key", new AbTestSource(echoA, echoB, 1.0).produce("my.key").asText(),
                "probability 1 always takes branch A");
    }

    @Test
    void keyThreadingSourcesHandTheKeyToTheCallerProvidedFunction() {
        DbTextSource db = new DbTextSource(key -> TextNode.valueOf("row:" + key));
        assertEquals("row:pharma.k", db.produce("pharma.k").asText());
        RemoteTextSource remote = new RemoteTextSource(key -> TextNode.valueOf("fetched:" + key));
        assertEquals("fetched:pharma.k", remote.produce("pharma.k").asText());
    }

    @Test
    void onlyStaticTextSourceCarriesTheStaticMarker() {
        assertInstanceOf(StaticPromptSource.class, new StaticTextSource("t"),
                "the facade caches exactly what carries the marker");
        assertFalse(new DbTextSource(key -> TextNode.valueOf("x")) instanceof StaticPromptSource,
                "a backing row can change between calls");
        assertFalse(new RemoteTextSource(key -> TextNode.valueOf("x")) instanceof StaticPromptSource,
                "a remote endpoint can change between calls");
        assertFalse(new AbTestSource(new StaticTextSource("a"), new StaticTextSource("b"), 0.5) instanceof StaticPromptSource,
                "dispatch varies call-to-call even over static branches");
    }
}
