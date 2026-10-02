/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the schema-echo normalization in {@link NucleoJsonSerializer#parseLLMResponse}:
 * a format-literal model (observed on the Nova family) answers a structured request in
 * the schema notation itself - values filled into {@code @fields} under {@code @type} -
 * instead of as a bare instance. The normalizer hoists every {@code @fields} object into
 * its parent recursively and drops {@code @}-prefixed notation keys, recovering exactly
 * the instance the answer encodes. Left unnormalized, that answer maps to an all-null
 * POJO and reads as a silent decline.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class SchemaEchoNormalizationTest {

    public static class Answer {
        private String verdict;
        private Detail detail;
        private List<String> tags;

        public String getVerdict() { return verdict; }
        public void setVerdict(String verdict) { this.verdict = verdict; }
        public Detail getDetail() { return detail; }
        public void setDetail(Detail detail) { this.detail = detail; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    public static class Detail {
        private Integer score;

        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }
    }

    @Test
    void answerWrappedUnderItsTypeNameRecoversTheInstance() throws IOException {
        // The other echo, observed on the OpenAI reasoning family after a correction: the
        // instance under one key that is the schema's @type name
        Answer answer = NucleoJsonSerializer.parseLLMResponse(
                "{\"Answer\": {\"verdict\": \"accepted\", \"detail\": {\"score\": 7}, \"tags\": [\"a\"]}}", Answer.class);
        assertEquals("accepted", answer.getVerdict(), "the instance under the type-name wrapper is the answer");
        assertEquals(7, answer.getDetail().getScore());
        Answer wrappedEcho = NucleoJsonSerializer.parseLLMResponse(
                "{\"Answer\": {\"@type\": \"Answer\", \"@fields\": {\"verdict\": \"wrapped and echoed\"}}}", Answer.class);
        assertEquals("wrapped and echoed", wrappedEcho.getVerdict(), "a wrapper around a schema echo unwraps, then hoists");
        Answer plain = NucleoJsonSerializer.parseLLMResponse("{\"verdict\": \"plain\"}", Answer.class);
        assertEquals("plain", plain.getVerdict(), "an instance that is not wrapped is read as it is");
    }

    @Test
    void schemaShapedAnswerRecoversTheEncodedInstance() throws IOException {
        String echo = """
                {"@type": "Answer",
                 "@description": "the answer object",
                 "@fields": {
                    "verdict": "accepted",
                    "detail": {"@type": "Detail", "@fields": {"score": 7}},
                    "tags": ["a", "b"]
                 }}""";
        Answer answer = NucleoJsonSerializer.parseLLMResponse(echo, Answer.class);
        assertEquals("accepted", answer.getVerdict(), "the hoist recovers top-level values");
        assertNotNull(answer.getDetail(), "nested schema echoes hoist recursively");
        assertEquals(7, answer.getDetail().getScore());
        assertEquals(List.of("a", "b"), answer.getTags());
    }

    @Test
    void aPlainInstanceIsUntouchedByTheNormalizer() throws IOException {
        Answer answer = NucleoJsonSerializer.parseLLMResponse(
                "{\"verdict\":\"plain\",\"detail\":{\"score\":3},\"tags\":[]}", Answer.class);
        assertEquals("plain", answer.getVerdict(), "well-formed answers pass through unchanged");
        assertEquals(3, answer.getDetail().getScore());
    }
}
