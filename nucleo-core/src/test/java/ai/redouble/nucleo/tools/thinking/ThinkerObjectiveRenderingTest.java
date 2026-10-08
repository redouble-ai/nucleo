/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the shape in which a thinker's input reaches the model. The {@link ThinkerObjective}
 * renders, through the same model-facing write the conversation applies to a PojoBlock, as
 * one JSON object with a {@code prompt} field and an {@code input} field. The input is the
 * JSON of its own fields under their schema names, with {@code @LLMContextIgnore} fields
 * left out. A subclass of {@link ThinkerInput} contributes fields and nothing else.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-08)
 */
public class ThinkerObjectiveRenderingTest {

    public static class PatentInput extends ThinkerInput {
        @LLMDescription("Maximum number of patents to return")
        private Integer maxPatents;
        public PatentInput() {}
        public Integer getMaxPatents() { return maxPatents; }
        public void setMaxPatents(Integer maxPatents) { this.maxPatents = maxPatents; }
    }

    private static ThinkerObjective objective() {
        PatentInput input = new PatentInput();
        input.setQuery("find prior art for a folding kayak");
        input.setDepth(Depth.QUICK);
        input.setMaxPatents(5);
        input.setArtifactRefs(List.of("«artifact:list~edtwhl»"));
        ThinkerObjective objective = new ThinkerObjective();
        objective.setPrompt(new TextPrompt("patent.search", TextNode.valueOf("Search the patent office.")));
        objective.setInput(input);
        return objective;
    }

    private static JsonNode rendered() throws Exception {
        String json = NucleoJsonSerializer.writeSummarizedWithRefs(objective(), new ArtifactRegistry());
        return NucleoJsonSerializer.readTree(json);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void objectiveIsOneJsonObjectWithPromptAndInput() throws Exception {
        JsonNode root = rendered();
        assertEquals(Set.of("input", "prompt"), fieldNames(root),
                "the model sees exactly a prompt and an input");
        assertEquals("patent.search", root.get("prompt").get("key").asText(),
                "the prompt field carries the registered prompt's key");
        assertEquals("Search the patent office.", root.get("prompt").get("content").asText(),
                "the prompt field carries the registered prompt's content");
    }

    @Test
    void inputIsTheJsonOfItsFieldsUnderSchemaNames() throws Exception {
        JsonNode input = rendered().get("input");
        assertEquals(Set.of("depth", "max_patents", "query"), fieldNames(input),
                "the input is its fields under their schema names, inherited and own alike");
        assertEquals("find prior art for a folding kayak", input.get("query").asText());
        assertEquals("QUICK", input.get("depth").asText());
        assertEquals(5, input.get("max_patents").asInt());
    }

    @Test
    void contextIgnoredFieldStaysOutOfTheModelsView() throws Exception {
        String json = NucleoJsonSerializer.writeSummarizedWithRefs(objective(), new ArtifactRegistry());
        assertFalse(json.contains("artifact_refs"), "artifactRefs is control data for the framework, never shown to the model");
        assertFalse(json.contains("edtwhl"), "the conveyed reference does not leak through the objective");
    }
}
