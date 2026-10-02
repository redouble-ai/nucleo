/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link Prompt} serializes as {@code {"key", "content"}} (no lineage
 * fields) and that deserialization yields a {@link TextPrompt} whose {@link PromptContext}
 * is freshly computed with the expected contentHash; a non-object or key-less wire shape
 * is refused, and absent content restores as an explicit null node.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class PromptJacksonRoundTripTest {

    @Test
    void textPromptSerializesWithoutContext() {
        Prompt p = new TextPrompt("greeting.hello", TextNode.valueOf("hi"));
        String json = NucleoJsonSerializer.write(p);
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"content\""));
        assertTrue(json.contains("greeting.hello"));
        assertFalse(json.contains("contentHash"));
        assertFalse(json.contains("producedAt"));
    }

    @Test
    void roundTripYieldsTextPrompt() throws Exception {
        Prompt p = new TextPrompt("k", TextNode.valueOf("payload"));
        String json = NucleoJsonSerializer.write(p);
        Prompt restored = NucleoJsonSerializer.parse(json, Prompt.class);
        assertInstanceOf(TextPrompt.class, restored);
        assertEquals("k", restored.key());
        assertEquals("payload", restored.content().asText());
    }

    @Test
    void lineageIsMemoized_repeatedContextCallsNeverRehash() {
        Prompt p = new TextPrompt("k", TextNode.valueOf("payload"));
        assertSame(p.context(), p.context(),
                "the (key, content) pair maps to ONE weakly-held PromptContext instance");
    }

    @Test
    void roundTripContentHashMatches() throws Exception {
        Prompt p = new TextPrompt("k", TextNode.valueOf("payload"));
        String originalHash = p.context().getContentHash();
        String json = NucleoJsonSerializer.write(p);
        Prompt restored = NucleoJsonSerializer.parse(json, Prompt.class);
        assertEquals(originalHash, restored.context().getContentHash());
    }

    @Test
    void deserializerRefusesNonObjectsAndMissingKeys_andDefaultsAbsentContentToNull() throws Exception {
        // NucleoJsonSerializer.parse surfaces Jackson refusals as IOException with the cause's message
        java.io.IOException notAnObject = assertThrows(java.io.IOException.class,
                () -> NucleoJsonSerializer.parse("[1,2]", Prompt.class),
                "a Prompt on the wire is an object, nothing else");
        assertTrue(notAnObject.getMessage().contains("JSON object"), notAnObject.getMessage());
        java.io.IOException keyless = assertThrows(java.io.IOException.class,
                () -> NucleoJsonSerializer.parse("{\"content\":\"x\"}", Prompt.class),
                "the key is the identity and cannot be absent");
        assertTrue(keyless.getMessage().contains("key"), keyless.getMessage());
        Prompt keyOnly = NucleoJsonSerializer.parse("{\"key\":\"k\"}", Prompt.class);
        assertTrue(keyOnly.content().isNull(), "absent content restores as an explicit null node, never a Java null");
    }

    @Test
    void structuredContentRoundTrips() throws Exception {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("instruction", "do X");
        node.put("tone", "formal");
        Prompt p = new TextPrompt("skill.body", node);
        String json = NucleoJsonSerializer.write(p);
        Prompt restored = NucleoJsonSerializer.parse(json, Prompt.class);
        assertTrue(restored.content().isObject());
        assertEquals("do X", restored.content().get("instruction").asText());
        assertEquals("formal", restored.content().get("tone").asText());
    }
}
