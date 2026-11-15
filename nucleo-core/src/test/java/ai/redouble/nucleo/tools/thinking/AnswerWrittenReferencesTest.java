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
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An artifact a thinker's answer refers to in a text field is selected, whatever the answer
 * class. The answer class here is a plain one: fields, accessors and no {@code toString()} of
 * its own, which is how an application writes one. The references are read from the reply as
 * the model wrote it, before it becomes an object of that class, so a reference counts in a
 * top-level field and in a field of a nested object alike, a reference that stands only in a
 * field's name counts for nothing, and one that is both declared and written is selected once.
 *
 * <p>Each case runs the reply through the thinking loop's own two steps: the response
 * handler's parse, whose gate fills {@code artifact_refs}, and the resolution against the
 * registry that the loop performs on the final answer.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
public class AnswerWrittenReferencesTest {
    private final ArtifactRegistry registry = new ArtifactRegistry();
    private final LinkArtifact first = new LinkArtifact();
    private final LinkArtifact second = new LinkArtifact();
    private String firstRef;
    private String secondRef;

    @BeforeEach
    void registerTwoArtifacts() {
        firstRef = ArtifactRegistry.normalizeToKey(registry.register(first));
        secondRef = ArtifactRegistry.normalizeToKey(registry.register(second));
    }

    @Test
    void aReferenceWrittenOnlyInATextField_isSelected() throws Exception {
        Findings answer = answered("{\"summary\": \"As " + firstRef + " shows.\"}");
        assertEquals(List.of(firstRef), answer.getArtifactRefs(), "the reference written in summary left the gate in artifact_refs");
        assertEquals(Set.of(first), answer.getArtifacts(), "and its artifact is the registry's object");
    }

    @Test
    void aReferenceWrittenInAFieldOfANestedObject_isSelected() throws Exception {
        Findings answer = answered("{\"summary\": \"Two points.\", \"points\": [{\"note\": \"plain\"}, {\"note\": \"per " + secondRef + "\"}]}");
        assertEquals(List.of(secondRef), answer.getArtifactRefs(), "a reference in a nested object's field is read");
        assertEquals(Set.of(second), answer.getArtifacts());
    }

    @Test
    void aReferenceWrittenInAFieldNameOnly_selectsNothing() throws Exception {
        Findings answer = answered("{\"summary\": \"Labelled.\", \"labels\": {\"" + firstRef + "\": \"a label\"}}");
        assertEquals("a label", answer.getLabels().get(firstRef), "the name arrived as a name");
        assertEquals(List.of(), answer.getArtifactRefs(), "a reference in a field's name is no value and selects nothing");
        assertTrue(answer.getArtifacts().isEmpty());
    }

    @Test
    void aReferenceDeclaredAndAlsoWritten_isSelectedOnce() throws Exception {
        Findings answer = answered("{\"summary\": \"As " + firstRef + " shows.\", \"artifact_refs\": [\"" + firstRef + "\", \"" + secondRef + "\"]}");
        assertEquals(List.of(firstRef, secondRef), answer.getArtifactRefs(), "declared order, and the written one is not added a second time");
        assertEquals(Set.of(first, second), answer.getArtifacts());
    }

    @Test
    void anAnswerBuiltInCode_selectsWhatItsListNames_andItsTextIsNotRead() {
        Findings answer = new Findings();
        answer.setSummary("As " + firstRef + " shows.");
        answer.addArtifactRef(secondRef);
        answer.resolveArtifacts(registry);
        assertEquals(Set.of(second), answer.getArtifacts(), "off the parse path the list is the selection; the text of a field is read by the parse gate alone");
    }

    /** Parses the model's reply as the thinking loop does, then resolves the answer as the loop does. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Findings answered(String answerJson) throws Exception {
        ThinkingResponseHandler<Findings> handler = new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<Findings>>((Class)ThinkingResponse.class),
                new PojoResponseHandler<>(Findings.class));
        Findings answer = handler.parse("{\"final_answer\": true, \"answer\": " + answerJson + "}").getAnswer();
        answer.resolveArtifacts(registry);
        return answer;
    }

    /** An application's answer class: text at the top level, in nested objects and in a map, and no toString of its own. */
    public static class Findings extends ThinkerOutput<SimpleReasoning> {
        private String summary;
        private List<Point> points;
        private Map<String, String> labels;

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public List<Point> getPoints() {
            return points;
        }

        public void setPoints(List<Point> points) {
            this.points = points;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }
    }

    /** A nested object of the answer. */
    public static class Point {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
