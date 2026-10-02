/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two halves of the {@code x-nucleo-*} vocabulary, pinned together because they only
 * make sense as a pair: the generator writes the keywords so a consuming harness can read
 * facts it cannot derive, and the model-facing render takes them out again.
 *
 * <p>Also pins required-ness as structure rather than prose: a description that happens to
 * contain the literal "(REQUIRED)" produces no required field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public class NucleoSchemaKeywordsTest {

    @TypeAlias("link:cite:test")
    public static class CitedThing extends AbstractArtifact {
        @LLMDescription("Digital object identifier")
        private String doi;

        public String getDoi() { return doi; }
        public void setDoi(String doi) { this.doi = doi; }
    }

    @LLMDescription("A result carrying every annotation the schema publishes")
    public static class AnnotatedResult {
        @LLMRequired
        @LLMDescription("What was asked")
        private String question;

        @LLMSummarizable(value = "page content", size = SummarySize.BRIEF, llmSafe = false, threshold = 4000)
        @LLMDescription("The retrieved page")
        private String body;

        @LLMContextIgnore
        @LLMDescription("Bookkeeping the model never needs")
        private String trace;

        @LLMDescription("The citation this result rests on")
        private CitedThing citation;

        @LLMDescription("Every citation consulted")
        private List<CitedThing> citations;

        @LLMDescription("The same, as an array")
        private CitedThing[] citationArray;

        @LLMDescription("Where the answer came from")
        private Provenance provenance;

        @LLMDescription("Answer (REQUIRED) only when the caller asked for one")
        private String note;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public String getTrace() { return trace; }
        public void setTrace(String trace) { this.trace = trace; }
        public CitedThing getCitation() { return citation; }
        public void setCitation(CitedThing citation) { this.citation = citation; }
        public List<CitedThing> getCitations() { return citations; }
        public void setCitations(List<CitedThing> citations) { this.citations = citations; }
        public CitedThing[] getCitationArray() { return citationArray; }
        public void setCitationArray(CitedThing[] citationArray) { this.citationArray = citationArray; }
        public Provenance getProvenance() { return provenance; }
        public void setProvenance(Provenance provenance) { this.provenance = provenance; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    @LLMDescription("A nested object with its own required field")
    public static class Provenance {
        @LLMRequired
        @LLMDescription("Which source")
        private String source;

        @LLMDescription("Mentions (REQUIRED) without being one")
        private String caveat;

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getCaveat() { return caveat; }
        public void setCaveat(String caveat) { this.caveat = caveat; }
    }

    private static JsonNode schemaOf(Class<?> type) throws Exception {
        return NucleoJsonSerializer.readTree(new PojoResponseHandler<>(type).writeDefinition().toJsonSchema());
    }

    private static JsonNode property(JsonNode schema, String name) {
        return schema.get("properties").get(name);
    }

    @Test
    void summarizableIsPublishedAsDeclared() throws Exception {
        JsonNode body = property(schemaOf(AnnotatedResult.class), "body");
        JsonNode summarizable = body.get(NucleoSchemaKeywords.SUMMARIZABLE);
        assertNotNull(summarizable, "a summarizable field must say so: " + body);
        assertEquals("page content", summarizable.get("hint").asText());
        assertEquals("BRIEF", summarizable.get("size").asText());
        assertEquals(4000, summarizable.get("threshold").asInt());
        assertFalse(summarizable.get("llmSafe").asBoolean(), "llmSafe=false is the fact that stops a paraphrase");
        assertFalse(summarizable.get("preSummarized").asBoolean(), "preSummarized is published as declared");
        assertEquals("", summarizable.get("staticSummary").asText(), "and so is staticSummary, empty when none");
    }

    @Test
    void contextIgnoreAndTypeAliasArePublished() throws Exception {
        JsonNode schema = schemaOf(AnnotatedResult.class);
        assertTrue(property(schema, "trace").get(NucleoSchemaKeywords.CONTEXT_IGNORE).asBoolean());
        assertEquals("link:cite:test", property(schema, "citation").get(NucleoSchemaKeywords.TYPE_ALIAS).asText());
    }

    @Test
    void unannotatedFieldsCarryNoKeywords() throws Exception {
        JsonNode question = property(schemaOf(AnnotatedResult.class), "question");
        Iterator<String> names = question.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            assertFalse(name.startsWith(NucleoSchemaKeywords.PREFIX), "unannotated field grew " + name);
        }
    }

    @Test
    void requiredComesFromTheAnnotationNeverFromTheDescriptionText() throws Exception {
        JsonNode required = schemaOf(AnnotatedResult.class).get("required");
        List<String> names = new ArrayList<>();
        required.forEach(node -> names.add(node.asText()));
        assertEquals(List.of("question"), names,
                "only @LLMRequired is required - 'note' merely says the word in its description");
    }

    @Test
    void stripRemovesTheVocabularyAtEveryDepth() throws Exception {
        String stripped = NucleoSchemaKeywords.stripFrom(new PojoResponseHandler<>(AnnotatedResult.class)
                .writeDefinition().toJsonSchema());
        assertFalse(stripped.contains(NucleoSchemaKeywords.PREFIX), "model-facing schema still carries keywords: " + stripped);
        JsonNode schema = NucleoJsonSerializer.readTree(stripped);
        assertEquals("The retrieved page", property(schema, "body").get("description").asText(),
                "the strip takes the keywords and leaves the schema");
        assertNotNull(property(schema, "citation").get("properties").get("doi"), "nested properties survive");
    }

    @Test
    void stripIsSafeOnNothing() throws Exception {
        assertNull(NucleoSchemaKeywords.stripFrom(null));
        assertEquals("", NucleoSchemaKeywords.stripFrom(""));
    }

    @Test
    void collectionsAndArraysCarryTheirElementAlias() throws Exception {
        JsonNode schema = schemaOf(AnnotatedResult.class);
        assertEquals("link:cite:test", property(schema, "citations").get(NucleoSchemaKeywords.TYPE_ALIAS).asText(),
                "a list of artifacts is described by what its elements are");
        assertEquals("link:cite:test", property(schema, "citation_array").get(NucleoSchemaKeywords.TYPE_ALIAS).asText());
    }

    @Test
    void nestedObjectsDeriveRequiredFromTheAnnotation() throws Exception {
        JsonNode nested = property(schemaOf(AnnotatedResult.class), "provenance");
        List<String> names = new ArrayList<>();
        nested.get("required").forEach(node -> names.add(node.asText()));
        assertEquals(List.of("source"), names, "the nested branch reads the flag too, not the description text");
    }

    // ---- the other rendering: what a model actually reads ----

    @Test
    void theNotationFormMarksRequiredFromTheAnnotation() {
        PojoDefinition definition = new PojoResponseHandler<>(AnnotatedResult.class).writeDefinition();
        assertTrue(definition.getFields().get("question").toLLMSchema().contains("\"@required\" : true"));
        assertFalse(definition.getFields().get("note").toLLMSchema().contains("@required"),
                "saying the word in a description never made a field required");
    }

    @Test
    void theNotationFormCarriesNoKeywords() {
        String notation = new PojoResponseHandler<>(AnnotatedResult.class).writeDefinition().toLLMSchema();
        assertFalse(notation.contains(NucleoSchemaKeywords.PREFIX),
                "harness metadata is not addressed to a model: " + notation);
        assertTrue(notation.contains("The retrieved page"), "the notation still describes the field");
    }
}
