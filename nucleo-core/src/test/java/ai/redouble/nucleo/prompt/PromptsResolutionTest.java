/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.prompt.sources.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the source resolution in {@link Prompts#produce}:
 * OVERRIDES &gt; GLOBAL_BACKEND &gt; DEFAULTS, with a miss in every tier throwing
 * {@link PromptNotFoundException} naming the key. Also pins the registration refusals
 * (a duplicate scanner-level default) and the first-produce auto-scan honoring
 * {@link Prompts#setDefaultScanPackage}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class PromptsResolutionTest {
    @BeforeEach
    void reset() {
        Prompts.resetAll();
    }

    @Test
    void defaultLayerResolves() throws Exception {
        Prompts.of("k1", "default-text");
        assertEquals("default-text", Prompts.produce("k1").content().asText());
    }

    @Test
    void overrideBeatsDefault() throws Exception {
        Prompts.of("k1", "default-text");
        Prompts.replace("k1", new StaticTextSource("override-text"));
        assertEquals("override-text", Prompts.produce("k1").content().asText());
    }

    @Test
    void resetOverrideClearsOverride() throws Exception {
        Prompts.replace("k1", new StaticTextSource("override-text"));
        assertEquals("override-text", Prompts.produce("k1").content().asText());
        Prompts.resetOverride("k1");
        assertThrows(PromptNotFoundException.class, () -> Prompts.produce("k1"));
    }

    @Test
    void globalBackendSubstitutesForMissingDefaults() throws Exception {
        Prompts.setGlobalBackend(key -> TextNode.valueOf("backend:" + key));
        assertEquals("backend:unknown", Prompts.produce("unknown").content().asText());
    }

    @Test
    void overrideBeatsGlobalBackend() throws Exception {
        Prompts.setGlobalBackend(key -> TextNode.valueOf("backend:" + key));
        Prompts.replace("k1", new StaticTextSource("override-text"));
        assertEquals("override-text", Prompts.produce("k1").content().asText());
    }

    @Test
    void missingKeyThrows_namingTheKey() {
        PromptNotFoundException miss = assertThrows(PromptNotFoundException.class, () -> Prompts.produce("nonexistent"));
        assertEquals("nonexistent", miss.getKey());
        assertTrue(miss.getMessage().contains("nonexistent"), "the refusal names the key: " + miss.getMessage());
        assertTrue(miss.getLLMMessage().contains("nonexistent"),
                "the LLM-facing message names the key too: " + miss.getLLMMessage());
    }

    @Test
    void ofWithAKeyLeavesTheOverrideLayerAlone() throws Exception {
        Prompts.replace("k1", new StaticTextSource("override-text"));
        Prompts.of("k1", "new-default");
        assertEquals("override-text", Prompts.produce("k1").content().asText(),
                "of(key, text) registers a DEFAULT - an override in place still wins");
        Prompts.resetOverride("k1");
        assertEquals("new-default", Prompts.produce("k1").content().asText());
    }

    @Test
    void inlinePromptsWearTheReservedNamespace_andAreNotRegistered() throws Exception {
        Prompt inline = Prompts.of("throwaway text");
        assertTrue(inline.key().startsWith("inline:"),
                "the framework-minted namespace keeps one-shots out of the app's key space: " + inline.key());
        assertThrows(PromptNotFoundException.class, () -> Prompts.produce(inline.key()),
                "an inline prompt is not registered and not substitutable");
    }

    @Test
    void scanPackageAfterAnyScanIsASilentNoOp() throws Exception {
        Prompts.of("k1", "default-text");
        Prompts.scanPackage("scannerrejects");
        assertEquals("default-text", Prompts.produce("k1").content().asText(),
                "one scan per process: a later scanPackage cannot fire - even at a package whose"
                        + " declarations would fail the scan loudly");
    }

    @Test
    void duplicateDefaultRegistrationIsRejected() {
        Prompts.registerDefault("dup.key", new StaticTextSource("first"));
        assertThrows(PromptRegistrationException.class,
                () -> Prompts.registerDefault("dup.key", new StaticTextSource("second")),
                "two declarations claiming one key is a programmer error, never last-write-wins");
    }

    @Test
    void defaultScanPackageRedirectsTheAutoScan() {
        try {
            // scannerrejects holds a deliberately invalid declaration; the auto-scan reaching
            // it proves the redirect took effect before the first produce
            Prompts.setDefaultScanPackage("scannerrejects");
            assertThrows(PromptRegistrationException.class, () -> Prompts.produce("anything"),
                    "the first produce auto-scans the redirected package");
        }
        finally {
            Prompts.setDefaultScanPackage("ai.redouble");
            Prompts.resetAll();
        }
    }

    @Test
    void clearGlobalBackendRevertsBehavior() throws Exception {
        Prompts.setGlobalBackend(key -> TextNode.valueOf("backend:" + key));
        Prompts.clearGlobalBackend();
        assertThrows(PromptNotFoundException.class, () -> Prompts.produce("unknown"));
    }
}
