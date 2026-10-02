/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.conversation.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reasoning vocabulary: each structured {@link Reasoning} renders a user-friendly
 * description from the parts a model filled in and says so in words when it filled none, and
 * {@link SimpleReasoning}'s description is its thought; a {@link ReasonablePojo} carries its
 * reasoning as a described field of the schema so the model writes it alongside the answer,
 * through either shipped binding.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class ReasoningTest {

    public static class Verdict extends StringReasonablePojo {
        @LLMDescription("The answer")
        private String answer;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }

    public static class Path extends ChainOfThoughtReasonablePojo {
        @LLMDescription("The answer")
        private String answer;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }

    @Test
    void simpleReasoningIsItsThoughtOrNullWithoutOne() {
        SimpleReasoning reasoning = new SimpleReasoning();
        reasoning.setThought("because");
        assertEquals("because", reasoning.getUserFriendlyDescription());
        assertNull(new SimpleReasoning().getUserFriendlyDescription(), "with no thought there is nothing to surface to a person: null, never words");
    }

    @Test
    void toolSelectionReasoningJoinsRationaleAndConfidence() {
        ToolSelectionReasoning reasoning = new ToolSelectionReasoning();
        assertEquals("No tool selection reasoning provided", reasoning.getUserFriendlyDescription());
        reasoning.setConfidence("high");
        assertEquals("Confidence: high", reasoning.getUserFriendlyDescription(), "confidence alone");
        reasoning.setRationale("needs a search");
        assertEquals("needs a search (Confidence: high)", reasoning.getUserFriendlyDescription(), "both parts");
    }

    @Test
    void chainOfThoughtReasoningListsItsSteps() {
        ChainOfThoughtReasoning reasoning = new ChainOfThoughtReasoning();
        assertEquals("No chain of thought provided", reasoning.getUserFriendlyDescription());
        reasoning.setApproach("split the problem");
        reasoning.setSteps(List.of("first", "second"));
        assertEquals("Approach: split the problem\nSteps: first → second", reasoning.getUserFriendlyDescription());
        reasoning.setApproach(null);
        assertEquals("Steps: first → second", reasoning.getUserFriendlyDescription(), "steps alone");
    }

    @Test
    void analysisReasoningJoinsRelevanceAndCaveats() {
        AnalysisReasoning reasoning = new AnalysisReasoning();
        assertEquals("No analysis reasoning provided", reasoning.getUserFriendlyDescription());
        reasoning.setRelevanceJustification("it matches the query");
        reasoning.setCaveats("one source only");
        assertEquals("Relevance: it matches the query\nCaveats: one source only", reasoning.getUserFriendlyDescription());
        reasoning.setRelevanceJustification("");
        assertEquals("Caveats: one source only", reasoning.getUserFriendlyDescription(), "an empty part is left out");
    }

    @Test
    void aReasonablePojoCarriesItsReasoningInTheSchema() throws IOException {
        JsonNode notation = NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Verdict.class).toLLMSchema());
        JsonNode reasoning = notation.path("@fields").path("reasoning");
        assertEquals("SimpleReasoning", reasoning.path("@type").asText(), "the reasoning type the POJO binds");
        assertEquals("The justification for this response, as a reader would check it", reasoning.path("@description").asText(),
                "the description asks for a checkable justification, never for the model's thought process");
        assertTrue(reasoning.path("@fields").has("thought"), "described down to the thought");
        Verdict verdict = NucleoJsonSerializer.parse("{\"answer\": \"yes\", \"reasoning\": {\"thought\": \"clear case\"}}", Verdict.class);
        assertEquals("clear case", verdict.getReasoning().getThought(), "and reads back typed");
    }

    @Test
    void theChainOfThoughtBindingCarriesItsStepsInTheSchema() throws IOException {
        JsonNode notation = NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Path.class).toLLMSchema());
        JsonNode reasoning = notation.path("@fields").path("reasoning");
        assertEquals("ChainOfThoughtReasoning", reasoning.path("@type").asText(), "the reasoning type the POJO binds");
        assertTrue(reasoning.path("@fields").path("steps").path("@required").asBoolean(), "described down to the required steps");
        Path path = NucleoJsonSerializer.parse("{\"answer\": \"yes\", \"reasoning\": {\"approach\": \"split\", \"steps\": [\"a\", \"b\"]}}", Path.class);
        assertEquals(List.of("a", "b"), path.getReasoning().getSteps(), "and reads back typed");
    }
}
