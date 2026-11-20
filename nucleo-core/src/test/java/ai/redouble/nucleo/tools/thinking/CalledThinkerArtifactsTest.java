/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The artifacts a called thinker selects reach the thinker that called it. A thinker that
 * ran as a tool answers with a {@link ThinkerOutput}; the calling thinker writes that output
 * for its own model with {@link NucleoJsonSerializer#writeSummarizedWithRefs} against its own
 * registry. That write puts the selected objects in the place of {@code artifact_refs}, each
 * as {@code {"@ref": ...}}, and registers each one with the calling registry, so the
 * reference the calling model reads is one its registry holds: an artifact tool finds it, and
 * the calling model's own answer can select it.
 *
 * <p>Each case stands a called thinker's registry and a calling thinker's registry side by
 * side and runs the two steps the framework runs between them: the reply gate of the called
 * thinker, and the write of its answer for the calling model.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-19)
 */
public class CalledThinkerArtifactsTest {
    private final ArtifactRegistry calledRegistry = new ArtifactRegistry();
    private final ArtifactRegistry callingRegistry = new ArtifactRegistry();
    private final LinkArtifact selected = new LinkArtifact();
    private final LinkArtifact notSelected = new LinkArtifact();
    private String selectedRef;
    private String notSelectedRef;

    @BeforeEach
    void theCalledThinkerHoldsTwoArtifacts() {
        selectedRef = ArtifactRegistry.normalizeToKey(calledRegistry.register(selected));
        notSelectedRef = ArtifactRegistry.normalizeToKey(calledRegistry.register(notSelected));
    }

    @Test
    void theSelectedArtifact_isWrittenForTheCallingModelAsItsReference_andIsRegisteredWithTheCallingRegistry() throws Exception {
        Report calledAnswer = answeredBy(calledRegistry, "{\"text\": \"Found one.\", \"artifact_refs\": [\"" + selectedRef + "\"]}");
        JsonNode shown = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarizedWithRefs(calledAnswer, callingRegistry));
        assertEquals(1, shown.path("artifact_refs").size(), "one entry for the one selected artifact: " + shown);
        assertEquals(selectedRef, ArtifactRegistry.normalizeToKey(shown.path("artifact_refs").path(0).path(NucleoJsonSerializer.ARTIFACT_REF_AS_SHOWN).asText()),
                "the calling model reads the reference the called thinker named: " + shown);
        assertSame(selected, callingRegistry.get(selectedRef), "and the calling registry holds the object under it, the called thinker's own instance");
    }

    @Test
    void anArtifactTheCalledThinkerDidNotSelect_staysOutOfTheCallingRegistry() throws Exception {
        Report calledAnswer = answeredBy(calledRegistry, "{\"text\": \"Found one.\", \"artifact_refs\": [\"" + selectedRef + "\"]}");
        NucleoJsonSerializer.writeSummarizedWithRefs(calledAnswer, callingRegistry);
        assertNull(callingRegistry.get(notSelectedRef), "the called thinker's selection is all that reaches its caller");
        assertEquals(1, callingRegistry.size());
    }

    @Test
    void theCallingThinkersAnswer_canSelectTheConveyedArtifact_andCarriesTheSameObject() throws Exception {
        Report calledAnswer = answeredBy(calledRegistry, "{\"text\": \"As " + selectedRef + " shows.\"}");
        NucleoJsonSerializer.writeSummarizedWithRefs(calledAnswer, callingRegistry);
        Report callingAnswer = answeredBy(callingRegistry, "{\"text\": \"Relayed.\", \"artifact_refs\": [\"" + selectedRef + "\"]}");
        assertEquals(Set.of(selected), callingAnswer.getArtifacts(), "the calling answer names the reference and its reply gate accepts it");
        assertSame(selected, callingAnswer.getArtifacts().iterator().next(), "the object that leaves the calling thinker is the one the tool built");
    }

    @Test
    void aWriteThatIsNotForAModel_keepsTheListOfReferences() throws Exception {
        Report calledAnswer = answeredBy(calledRegistry, "{\"text\": \"Found one.\", \"artifact_refs\": [\"" + selectedRef + "\"]}");
        JsonNode stored = NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(calledAnswer));
        assertEquals(selectedRef, stored.path("artifact_refs").path(0).asText(), "storage and replay read the references as strings: " + stored);
        JsonNode summarized = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(calledAnswer));
        assertEquals(selectedRef, summarized.path("artifact_refs").path(0).asText(), "and so does the summarized write: " + summarized);
    }

    @Test
    void anOutputThatHoldsReferencesAndNoObjects_isWrittenForAModelWithItsReferencesAsStrings() throws Exception {
        Report built = new Report();
        built.setText("Built in code.");
        built.addArtifactRef(selectedRef);
        JsonNode shown = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarizedWithRefs(built, callingRegistry));
        assertEquals(selectedRef, shown.path("artifact_refs").path(0).asText(), "nothing was resolved, so there is no object to write: " + shown);
        assertEquals(0, callingRegistry.size(), "and nothing to register");
    }

    @Test
    void aReferenceOfTheListThatNoSelectedObjectCarries_followsTheObjectsAsAString() throws Exception {
        Report built = new Report();
        built.setText("Built in code.");
        built.addArtifact(selected);
        built.addArtifactRef(notSelectedRef);
        JsonNode shown = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarizedWithRefs(built, callingRegistry));
        assertEquals(selectedRef, ArtifactRegistry.normalizeToKey(shown.path("artifact_refs").path(0).path(NucleoJsonSerializer.ARTIFACT_REF_AS_SHOWN).asText()), shown.toString());
        assertEquals(notSelectedRef, shown.path("artifact_refs").path(1).asText(), "no reference the output names is dropped from what the model reads: " + shown);
    }

    /** A model's final answer, run through the parse and the reply gate of a thinker whose conversation holds {@code registry}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Report answeredBy(ArtifactRegistry registry, String answerJson) throws Exception {
        ThinkingResponseHandler<Report> handler = new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<Report>>((Class)ThinkingResponse.class),
                new PojoResponseHandler<>(Report.class));
        List<String> violations = new ArrayList<>();
        Report answer = handler.heldArtifacts(handler.parse("{\"final_answer\": true, \"answer\": " + answerJson + "}"), registry, violations).getAnswer();
        assertEquals(List.of(), violations, "the reply gate accepts the answer");
        return answer;
    }

    /** A thinker's answer: one text field beside the inherited list of references. */
    public static class Report extends ThinkerOutput<SimpleReasoning> {
        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
