/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;


/**
 * Represents a single chunk of streaming response from an LLM.
 *
 * @param content The text content of this chunk
 * @param tokenCount Estimated token count in this chunk (may be 0 if not available)
 * @param isLast Whether this is the final chunk in the stream
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public record StreamChunk(
    String content,
    int tokenCount,
    boolean isLast
) {
    /**
     * Creates a simple content chunk that's not the last one.
     */
    public static StreamChunk of(String content) {
        return new StreamChunk(content, 0, false);
    }

    /**
     * Creates a final chunk marker with optional content.
     */
    public static StreamChunk done(String content) {
        return new StreamChunk(content != null ? content : "", 0, true);
    }

    /**
     * Creates a final chunk marker with no content.
     */
    public static StreamChunk done() {
        return done(null);
    }
}