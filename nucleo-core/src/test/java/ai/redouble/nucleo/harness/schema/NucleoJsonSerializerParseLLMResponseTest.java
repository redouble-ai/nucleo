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
 * {@link NucleoJsonSerializer#parseLLMResponse}: the pipeline that turns a model's raw text into
 * an instance. The last balanced JSON span is taken from the text (preamble, fences and trailing
 * prose dropped), raw control characters inside strings are escaped, typographic punctuation
 * outside string values is rewritten to ASCII while a value keeps what the model wrote, an
 * answer wrapped under its type name or written in the schema's own shape is unwrapped, and
 * the result is read with the lenient mapper. A text with no JSON span is refused as an
 * extraction failure; JSON that will not map is refused as a mapping failure that names the
 * target. {@link
 * NucleoJsonSerializer#extractJsonWithProse} splits the same text into the span and the prose
 * around it for callers that keep the model's reasoning.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class NucleoJsonSerializerParseLLMResponseTest {

    public static class Answer {
        private String verdict;
        private List<Integer> scores;

        public String getVerdict() { return verdict; }
        public void setVerdict(String verdict) { this.verdict = verdict; }
        public List<Integer> getScores() { return scores; }
        public void setScores(List<Integer> scores) { this.scores = scores; }
    }

    @Test
    void theLastBalancedSpanIsTheAnswer() throws IOException {
        Answer answer = NucleoJsonSerializer.parseLLMResponse(
                "Thinking about [1] and [2]...\nDraft: {\"verdict\": \"no\"}\nFinal:\n```json\n{\"verdict\": \"yes\", \"scores\": [1, 2]}\n```\nDone.",
                Answer.class);
        assertEquals("yes", answer.getVerdict(), "preamble, a draft span and trailing prose are all dropped");
        assertEquals(List.of(1, 2), answer.getScores());
    }

    @Test
    void rawControlCharactersInsideStringsAreEscaped() throws IOException {
        Answer answer = NucleoJsonSerializer.parseLLMResponse("{\"verdict\": \"line one\nline two\ttabbed\"}", Answer.class);
        assertEquals("line one\nline two\ttabbed", answer.getVerdict(), "a newline and a tab a model typed raw survive as characters");
    }

    @Test
    void theTwoSchemaEchoesAreUnwrapped() throws IOException {
        assertEquals("wrapped", NucleoJsonSerializer.parseLLMResponse("{\"Answer\": {\"verdict\": \"wrapped\"}}", Answer.class).getVerdict(),
                "an answer under its own type name");
        assertEquals("shaped", NucleoJsonSerializer.parseLLMResponse("{\"@type\": \"Answer\", \"@fields\": {\"verdict\": \"shaped\"}}", Answer.class).getVerdict(),
                "an answer in the schema's own shape");
    }

    @Test
    void typographicPunctuationInsideAValueIsPreserved() throws IOException {
        Answer answer = NucleoJsonSerializer.parseLLMResponse(
                "{\"verdict\": \"a — b, she said “hi”, don’t… (1–2)\"}", Answer.class);
        assertEquals("a — b, she said “hi”, don’t… (1–2)", answer.getVerdict(),
                "an em dash, curly quotes, an apostrophe, an ellipsis and an en dash inside a value survive verbatim");
    }

    @Test
    void typographicQuotesAroundKeysAndValuesAreReadAsQuotes() throws IOException {
        Answer answer = NucleoJsonSerializer.parseLLMResponse("{“verdict”: ‘yes’, “scores”: [1]}", Answer.class);
        assertEquals("yes", answer.getVerdict(), "curly double quotes around a key and curly single quotes around a value are the parser's quotes");
        assertEquals(List.of(1), answer.getScores());
    }

    @Test
    void aValueMentioningDataAndABracketIsStillTheAnswer() throws IOException {
        Answer answer = NucleoJsonSerializer.parseLLMResponse("{\"verdict\": \"see Data: [1] and Items: {2} below\", \"scores\": [2]}", Answer.class);
        assertEquals("see Data: [1] and Items: {2} below", answer.getVerdict(), "the last balanced span is the answer, whatever its strings say");
        assertEquals(List.of(2), answer.getScores());
    }

    @Test
    void anAnswerThatWillNotMapIsRefusedNamingTheTarget() {
        IOException refusal = assertThrows(IOException.class,
                () -> NucleoJsonSerializer.parseLLMResponse("{\"verdict\": \"ok\", \"scores\": [\"not a number\"]}", Answer.class));
        assertTrue(refusal.getMessage().startsWith("Failed to parse JSON to Answer"), "a mapping failure names the target: " + refusal.getMessage());
    }

    @Test
    void aTextWithNoJsonSpanIsAnExtractionFailure() {
        IOException refusal = assertThrows(IOException.class, () -> NucleoJsonSerializer.parseLLMResponse("I cannot answer that.", Answer.class));
        assertTrue(refusal.getMessage().startsWith("Failed to extract valid JSON"), refusal.getMessage());
        IOException truncated = assertThrows(IOException.class, () -> NucleoJsonSerializer.parseLLMResponse("{\"verdict\": \"cut", Answer.class));
        assertTrue(truncated.getMessage().startsWith("Failed to extract valid JSON"), "an unbalanced span is no span: " + truncated.getMessage());
    }

    @Test
    void proseAroundTheSpanIsKeptForCallersThatWantIt() {
        NucleoJsonSerializer.JsonProseSplit split = NucleoJsonSerializer.extractJsonWithProse("I weighed both.\n{\"verdict\": \"yes\"}\nThat is my answer.");
        assertEquals("{\"verdict\": \"yes\"}", split.json());
        assertTrue(split.prose().startsWith("I weighed both.") && split.prose().endsWith("That is my answer."),
                "the text before and after the span, surrounding whitespace trimmed: " + split.prose());
        assertFalse(split.prose().contains("verdict"), "the span itself is not prose");
        NucleoJsonSerializer.JsonProseSplit bare = NucleoJsonSerializer.extractJsonWithProse("{\"verdict\": \"yes\"}");
        assertEquals("", bare.prose(), "pure JSON has no prose");
        NucleoJsonSerializer.JsonProseSplit none = NucleoJsonSerializer.extractJsonWithProse("no json here");
        assertEquals("", none.json());
        assertEquals("no json here", none.prose(), "text with no span is all prose");
        assertEquals(new NucleoJsonSerializer.JsonProseSplit("", ""), NucleoJsonSerializer.extractJsonWithProse(null), "null splits into two empties");
    }
}
