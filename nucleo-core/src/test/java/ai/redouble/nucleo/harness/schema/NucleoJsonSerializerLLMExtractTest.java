/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import org.junit.jupiter.api.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NucleoJsonSerializer#extractJsonFromLLMResponse(String)}.
 *
 * <p>The extractor runs on raw LLM output and must pick the final JSON payload
 * regardless of whether it is an object or an array, and regardless of what
 * bracket-shaped text appears in the preamble.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class NucleoJsonSerializerLLMExtractTest {

    @Test
    public void cleanObject() throws Exception {
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse("{\"a\":1}");
        assertEquals("{\"a\":1}", out);
    }

    @Test
    public void cleanArray() throws Exception {
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse("[1,2,3]");
        assertEquals("[1,2,3]", out);
    }

    @Test
    public void objectAfterArrayLookingPreamble() throws Exception {
        String raw = "Here are candidates: [1, 2, 3]\n\n{\"reasoning\":\"x\",\"answer\":\"y\"}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"reasoning\":\"x\",\"answer\":\"y\"}", out);
    }

    @Test
    public void arrayAnswerAfterObjectLookingPreamble() throws Exception {
        String raw = "Notes: {draft only}\nFinal list:\n[\"a\",\"b\",\"c\"]";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("[\"a\",\"b\",\"c\"]", out);
    }

    @Test
    public void markdownFenceObject() throws Exception {
        String raw = "Sure, here you go:\n```json\n{\"k\":\"v\"}\n```\n";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"k\":\"v\"}", out);
    }

    @Test
    public void markdownFenceArray() throws Exception {
        String raw = "```json\n[1,2,3]\n```";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("[1,2,3]", out);
    }

    @Test
    public void preambleAndTrailingProse() throws Exception {
        String raw = "Thinking... [cite 1] and [cite 2]\n{\"a\":[1,2,3],\"b\":\"ok\"}\nDone!";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"a\":[1,2,3],\"b\":\"ok\"}", out);
    }

    @Test
    public void stringContainingUnbalancedBrackets() throws Exception {
        String raw = "{\"sql\":\"SELECT * FROM t WHERE x IN (1,2,3) -- }}}\"}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals(raw, out);
    }

    @Test
    public void stringContainingBracketsInsideObject() throws Exception {
        String raw = "{\"note\":\"imidazo[4,5-c] and [text](url)\",\"n\":1}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals(raw, out);
    }

    @Test
    public void preambleWithMarkdownLinkBeforeObject() throws Exception {
        String raw = "See [the docs](https://example.com/a) for background.\n\n{\"ready\":true}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"ready\":true}", out);
    }

    @Test
    public void escapedQuoteInsideString() throws Exception {
        String raw = "{\"q\":\"he said \\\"}\\\" loudly\"}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals(raw, out);
    }

    @Test
    public void nestedObjects() throws Exception {
        String raw = "prelude\n{\"outer\":{\"inner\":{\"x\":[1,2]}},\"done\":true}\ntrailing";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"outer\":{\"inner\":{\"x\":[1,2]}},\"done\":true}", out);
    }

    @Test
    public void multipleTopLevelSpansReturnsLast() throws Exception {
        String raw = "First draft: {\"v\":1}\nRevised: {\"v\":2}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"v\":2}", out);
    }

    @Test
    public void truncatedResponseThrows() {
        String raw = "Here's my answer: {\"reasoning\":\"this got cut";
        assertThrows(IOException.class,
            () -> NucleoJsonSerializer.extractJsonFromLLMResponse(raw));
    }

    @Test
    public void emptyThrows() {
        assertThrows(IOException.class,
            () -> NucleoJsonSerializer.extractJsonFromLLMResponse(""));
    }

    @Test
    public void nullThrows() {
        assertThrows(IOException.class,
            () -> NucleoJsonSerializer.extractJsonFromLLMResponse(null));
    }

    @Test
    public void onlyPreambleNoJsonThrows() {
        assertThrows(IOException.class,
            () -> NucleoJsonSerializer.extractJsonFromLLMResponse("I don't know the answer."));
    }

    @Test
    public void parseLLMResponseReportsExtractionFailureDistinctly() {
        IOException e = assertThrows(IOException.class,
            () -> NucleoJsonSerializer.parseLLMResponse("no json anywhere in this answer", java.util.Map.class));
        assertTrue(e.getMessage().startsWith("Failed to extract valid JSON"),
            "an extraction failure names itself, distinct from a mapping failure: " + e.getMessage());
    }

    @Test
    public void strayCloserInPreambleIgnored() throws Exception {
        String raw = "oops } stray\n{\"ok\":true}";
        String out = NucleoJsonSerializer.extractJsonFromLLMResponse(raw);
        assertEquals("{\"ok\":true}", out);
    }
}
