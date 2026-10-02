/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A catalog entry of another modality - one whose stated output is neither text nor an
 * embedding - is a model the account offers and the runtime has no client for. It states its
 * modalities and nothing a call would need; the loader accepts it on those terms, refuses a
 * grade on it, and holds every other entry to what a call needs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-23)
 */
class SeatlessEntryTest {

    private static JsonModelsBackend catalog(String entry) {
        return new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("test", "{ \"models\": [" + entry + "] }", false)));
    }

    @Test
    void aModelOfAnotherModalityLoadsWithoutCeilingsLimitsOrPrices() {
        ModelSpec spec = catalog("{ \"id\": \"canvas\", \"identity\": \"canvas\", \"provider_key\": \"bedrock-converse\","
                + " \"wire_model_id\": \"amazon.nova-canvas-v1:0\", \"input_modalities\": [\"TEXT\", \"IMAGE\"],"
                + " \"output_modalities\": [\"IMAGE\"], \"supports_vision\": true }").spec("canvas");
        assertFalse(spec.hasSeat(), "no grade and not an embeddings key: no seat");
        assertEquals(List.of("TEXT", "IMAGE"), spec.getInputModalities());
        assertEquals(List.of("IMAGE"), spec.getOutputModalities());
        assertNull(spec.getGrade());
        assertEquals(0, spec.getTpm(), "nothing a call would need was required");
    }

    @Test
    void aTranscriberWhoseInputStatesNoTextIsSeatlessToo() {
        // TEXT out is not enough: an entry that cannot take text in is not a chat model
        ModelSpec spec = catalog("{ \"id\": \"scribe\", \"identity\": \"scribe\", \"provider_key\": \"bedrock-converse\","
                + " \"wire_model_id\": \"vendor.scribe\", \"input_modalities\": [\"SPEECH\"],"
                + " \"output_modalities\": [\"TEXT\"] }").spec("scribe");
        assertFalse(spec.hasSeat(), "text out of an input stated without text: no seat");
        assertEquals(0, spec.getTpm(), "nothing a call would need was required");
    }

    @Test
    void aModelOfAnotherModalityMayNotCarryAGrade() {
        UncorrectableRuntimeLLMException refused = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog("{ \"id\": \"canvas\", \"identity\": \"canvas\", \"grade\": \"SMALL\", \"provider_key\": \"bedrock-converse\","
                        + " \"wire_model_id\": \"amazon.nova-canvas-v1:0\", \"input_modalities\": [\"TEXT\"], \"output_modalities\": [\"IMAGE\"] }"));
        assertTrue(refused.getMessage().contains("produces no text"), refused.getMessage());
    }

    @Test
    void aTextModelStatingItsModalitiesIsHeldToWhatACallNeeds() {
        UncorrectableRuntimeLLMException refused = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog("{ \"id\": \"chat\", \"identity\": \"chat\", \"provider_key\": \"bedrock-converse\","
                        + " \"wire_model_id\": \"vendor.chat\", \"input_modalities\": [\"TEXT\"], \"output_modalities\": [\"TEXT\"] }"));
        assertTrue(refused.getMessage().contains("tpm"), refused.getMessage());
    }

    @Test
    void aGradedEntryStillNeedsWhatACallNeeds() {
        UncorrectableRuntimeLLMException refused = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog("{ \"id\": \"chat\", \"identity\": \"chat\", \"grade\": \"SMALL\", \"provider_key\": \"bedrock-converse\","
                        + " \"wire_model_id\": \"vendor.chat\", \"max_context_tokens\": 1000, \"max_output_tokens\": 100 }"));
        assertTrue(refused.getMessage().contains("tpm"), refused.getMessage());
    }
}
