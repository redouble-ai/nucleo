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
 * A decision entry in the catalog: its kind follows the provider key as the embeddings family's
 * does, it carries no grade and no output ceiling, it states exactly one bound - a quota
 * window or a concurrency - and the {@code decision} pin names one. A grade pin may not name
 * it, and no other kind may declare a concurrency.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class DecisionCatalogTest {
    static final String LOCAL = "{ \"id\": \"kev-9b\", \"identity\": \"kev-9b\", \"provider_key\": \"systemone-decision\","
            + " \"wire_model_id\": \"kev-latest\", \"max_context_tokens\": 16384, \"max_concurrent\": 1, \"input_price_per_million\": 0.0, \"currency\": \"USD\" }";
    static final String HOSTED = "{ \"id\": \"jev-1.13.0\", \"identity\": \"jev-1.13\", \"provider_key\": \"systemone-decision\","
            + " \"wire_model_id\": \"jev-1.13.0\", \"max_context_tokens\": 64000, \"tpm\": 15000000, \"rpm\": 1200, \"input_price_per_million\": 0.042, \"currency\": \"USD\" }";
    static final String LLM = "{ \"id\": \"nova-micro\", \"identity\": \"nova-micro\", \"grade\": \"MICRO\", \"provider_key\": \"bedrock-converse\","
            + " \"wire_model_id\": \"us.amazon.nova-micro-v1:0\", \"max_context_tokens\": 128000, \"max_output_tokens\": 10000, \"tpm\": 4000000 }";

    private static JsonModelsBackend catalog(String models, String pins) {
        return new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("test",
                "{ \"models\": [" + models + "]" + (pins != null ? ", \"pins\": " + pins : "") + " }", false)));
    }

    @Test
    void aDecisionEntryIsItsOwnKind_withNoGradeAndNoOutput() {
        ModelSpec local = catalog(LOCAL + "," + HOSTED + "," + LLM, null).spec("kev-9b");
        assertEquals(ModelKind.DECISION, local.kind());
        assertTrue(local.isDecision());
        assertFalse(local.isEmbeddings());
        assertTrue(local.hasSeat(), "a decision entry is a seat the deployment can name");
        assertNull(local.getGrade());
        assertEquals(0, local.getMaxOutputTokens(), "no output ceiling: a decision entry generates nothing");
        assertEquals(16384, local.getMaxContextTokens(), "the state it reads");
        assertEquals(1, local.getMaxConcurrent());
        assertEquals(0, local.getTpm(), "a concurrency-bounded entry declares no window");
        assertTrue(local.isPriced(), "input-only pricing is whole for a decision entry, a zero price included");
        ModelSpec hosted = catalog(HOSTED, null).spec("jev-1.13.0");
        assertNull(hosted.getMaxConcurrent(), "a hosted entry declares its window");
        assertEquals(15000000, hosted.getTpm());
        assertEquals(ModelKind.LLM, catalog(LLM, null).spec("nova-micro").kind());
    }

    @Test
    void aDecisionEntryStatesExactlyOneBound() {
        String both = LOCAL.replace("\"max_concurrent\": 1", "\"max_concurrent\": 1, \"tpm\": 1000");
        String neither = LOCAL.replace(" \"max_concurrent\": 1,", "");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> catalog(both, null)).getMessage().contains("both"));
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> catalog(neither, null)).getMessage().contains("neither"));
        String zero = LOCAL.replace("\"max_concurrent\": 1", "\"max_concurrent\": 0");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> catalog(zero, null)).getMessage().contains("max_concurrent"));
        String noState = LOCAL.replace(" \"max_context_tokens\": 16384,", "");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> catalog(noState, null)).getMessage().contains("max_context_tokens"),
                "the state ceiling is required");
    }

    @Test
    void aDecisionEntryMayNotCarryAGrade_andNoOtherKindMayDeclareAConcurrency() {
        String graded = LOCAL.replace("\"identity\": \"kev-9b\",", "\"identity\": \"kev-9b\", \"grade\": \"SMALL\",");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> catalog(graded, null)).getMessage().contains("ladder"));
        String concurrentLlm = LLM.replace("\"tpm\": 4000000", "\"tpm\": 4000000, \"max_concurrent\": 2");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> catalog(concurrentLlm, null)).getMessage().contains("max_concurrent"));
    }

    @Test
    void theDecisionPinNamesADecisionEntry_andAGradePinMayNot() {
        assertEquals("kev-9b", catalog(LOCAL + "," + LLM, "{ \"decision\": \"kev-9b\", \"MICRO\": [\"nova-micro\"] }").pins().decision());
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog(LOCAL + "," + LLM, "{ \"decision\": \"nova-micro\" }")).getMessage().contains("not a decision entry"));
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog(LOCAL + "," + LLM, "{ \"MICRO\": [\"kev-9b\"] }")).getMessage().contains("decision entry"));
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog(LOCAL, "{ \"decision\": \"absent\" }")).getMessage().contains("not an entry"));
    }
}
