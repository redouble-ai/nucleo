/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code @LLMContextIgnore}: a field stays in the schema and in the full
 * {@link NucleoJsonSerializer#write} (so the caller can populate it and it persists
 * for observability), but is omitted from the model-facing
 * {@link NucleoJsonSerializer#writeSummarizedWithRefs} (LLM_REF) render that goes
 * into the prompt.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class NucleoJsonSerializerContextIgnoreTest {

    public static class Holder {
        @LLMDescription("the actual instruction")
        private String query;
        @LLMContextIgnore
        @LLMDescription("control refs the caller declares but the callee never reads back")
        private List<String> artifactRefs;

        public Holder() {}
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public List<String> getArtifactRefs() { return artifactRefs; }
        public void setArtifactRefs(List<String> artifactRefs) { this.artifactRefs = artifactRefs; }
    }

    private static Holder sample() {
        Holder h = new Holder();
        h.setQuery("process the list");
        h.setArtifactRefs(List.of("«artifact:list~edtwhl»"));
        return h;
    }

    @Test
    void fullWriteKeepsContextIgnoredField() {
        String json = NucleoJsonSerializer.write(sample());
        assertTrue(json.contains("artifact_refs"), "full write() must keep the field for persistence/observability");
        assertTrue(json.contains("edtwhl"), "full write() must keep the value");
        assertTrue(json.contains("process the list"), "full write() keeps ordinary fields");
    }

    @Test
    void llmRefRenderDropsContextIgnoredField() {
        String json = NucleoJsonSerializer.writeSummarizedWithRefs(sample(), new ArtifactRegistry());
        assertFalse(json.contains("artifact_refs"), "LLM_REF render must omit the @LLMContextIgnore field");
        assertFalse(json.contains("edtwhl"), "LLM_REF render must not leak the ignored value");
        assertTrue(json.contains("process the list"), "LLM_REF render keeps ordinary fields");
    }

    @Test
    void summarizedRenderKeepsContextIgnoredField() {
        // SUMMARIZED mode is not the prompt-context render (that is LLM_REF); the
        // field is only dropped for LLM_REF, so summarized output still carries it.
        String json = NucleoJsonSerializer.writeSummarized(sample());
        assertTrue(json.contains("artifact_refs"), "SUMMARIZED mode is not the context render and must keep the field");
    }

    @Test
    void schemaKeepsContextIgnoredField() {
        String schema = PojoResponseHandler.generateSchema(Holder.class).toLLMSchema();
        assertTrue(schema.contains("artifact_refs"),
                "schema must keep the field so the caller can populate it");
    }
}
