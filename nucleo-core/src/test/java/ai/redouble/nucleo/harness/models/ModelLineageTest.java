/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The reading of identities as family and version, and of wire ids as identities, on the
 * spellings the catalog and the vendors actually use.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
class ModelLineageTest {

    @Test
    void identitiesSplitIntoFamilyAndVersion() {
        assertEquals(new ModelLineage("opus", "4.8"), ModelLineage.of("opus-4.8"));
        assertEquals(new ModelLineage("opus", "5"), ModelLineage.of("opus-5"));
        assertEquals(new ModelLineage("fable", "5.1"), ModelLineage.of("fable-5.1"));
        assertEquals(new ModelLineage("gpt", "5.6"), ModelLineage.of("gpt-5.6"));
        assertEquals(new ModelLineage("gpt-mini", "5"), ModelLineage.of("gpt-5-mini"));
        assertEquals(new ModelLineage("gpt-oss-120b", ""), ModelLineage.of("gpt-oss-120b"), "a size is family, not version");
        assertEquals(new ModelLineage("nova-lite", ""), ModelLineage.of("nova-lite"));
        assertEquals(new ModelLineage("nova-lite", "2"), ModelLineage.of("nova-2-lite"));
        assertEquals(new ModelLineage("ministral-14b", "3"), ModelLineage.of("ministral-3-14b"));
        assertEquals(new ModelLineage("o3", ""), ModelLineage.of("o3"));
    }

    @Test
    void versionsOrderNumericallyAndAMissingOneComesFirst() {
        assertTrue(ModelLineage.of("opus-5").newerThan(ModelLineage.of("opus-4.8")));
        assertTrue(ModelLineage.of("opus-4.10").newerThan(ModelLineage.of("opus-4.9")), "numeric, not lexical");
        assertTrue(ModelLineage.of("nova-2-lite").newerThan(ModelLineage.of("nova-lite")));
        assertTrue(ModelLineage.of("fable-5.1").newerThan(ModelLineage.of("fable-5")));
        assertFalse(ModelLineage.of("sonnet-5").newerThan(ModelLineage.of("opus-4.8")), "another family is never newer");
        assertEquals(0, ModelLineage.of("opus-5").compareVersion(ModelLineage.of("opus-5")));
    }

    @Test
    void wireIdsShedTheirEndpointDecorations() {
        assertEquals("nova-2-lite", ModelLineage.identityOf("eu.amazon.nova-2-lite-v1:0"));
        assertEquals("gpt-oss-120b", ModelLineage.identityOf("openai.gpt-oss-120b-1:0"));
        assertEquals("ministral-3-14b", ModelLineage.identityOf("mistral.ministral-3-14b-instruct"));
        assertEquals("gpt-5.6", ModelLineage.identityOf("gpt-5.6"), "a dotted version is not a vendor prefix");
        assertEquals("claude-opus-4-5", ModelLineage.identityOf("global.anthropic.claude-opus-4-5-20251101-v1:0"));
        assertEquals("grok-4.6", ModelLineage.identityOf("xai.grok-4.6"));
        assertEquals("nova-lite", ModelLineage.identityOf("amazon.nova-lite-v1:0:24k"), "a provisioned-context variant is the same model");
        assertEquals("nova-pro", ModelLineage.identityOf("amazon.nova-pro-v1:0:300k"));
        assertEquals("gpt-oss-120b", ModelLineage.identityOf("openai.gpt-oss-120b"), "Mantle's spelling of the runtime's openai.gpt-oss-120b-1:0");
    }

    @Test
    void geographyPrefixesAreExplicitAndVendorsAreNot() {
        assertEquals("us", ModelLineage.prefix("us.anthropic.claude-opus-5"));
        assertEquals("global", ModelLineage.prefix("global.anthropic.claude-opus-5"));
        assertNull(ModelLineage.prefix("anthropic.claude-opus-5"));
        assertNull(ModelLineage.prefix("qwen.qwen3-32b"));
        assertEquals("anthropic.claude-opus-5", ModelLineage.bare("eu.anthropic.claude-opus-5"));
        assertEquals("v.m", ModelLineage.bare("us-gov.v.m"));
    }
}
