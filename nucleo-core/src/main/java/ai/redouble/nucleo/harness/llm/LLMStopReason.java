/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;


/**
 * Normalized, provider-agnostic reason an LLM stopped generating. Every client maps its provider's
 * raw stop/finish reason into one of these via {@link #from(String)}, so callers reason about a
 * single typed value (output truncation today, content filtering or tool use tomorrow) instead of
 * matching provider-specific strings at each call site.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-17)
 */
public enum LLMStopReason {
    /** Model finished its turn normally. */
    END_TURN,
    /** Output hit the max_tokens / length ceiling - the content is truncated and incomplete. */
    MAX_TOKENS,
    /** Model emitted a configured stop sequence. */
    STOP_SEQUENCE,
    /** Model stopped to call a tool. */
    TOOL_USE,
    /** Provider filtered / refused the content (safety, guardrail). */
    CONTENT_FILTERED,
    /** Absent or unrecognized stop reason. */
    UNKNOWN;

    /**
     * Normalizes a provider's raw stop/finish reason. Covers Anthropic ({@code end_turn},
     * {@code max_tokens}, {@code stop_sequence}, {@code tool_use}), OpenAI ({@code stop},
     * {@code length}, {@code tool_calls}, {@code content_filter}), and Bedrock Converse
     * ({@code end_turn}, {@code max_tokens}, {@code stop_sequence}, {@code tool_use},
     * {@code content_filtered}, {@code guardrail_intervened}). Null or unknown maps to
     * {@link #UNKNOWN}.
     */
    public static LLMStopReason from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim().toLowerCase()) {
            case "end_turn", "stop", "complete", "completed", "finished" -> END_TURN;
            case "max_tokens", "length", "model_length", "max_token" -> MAX_TOKENS;
            case "stop_sequence" -> STOP_SEQUENCE;
            case "tool_use", "tool_calls" -> TOOL_USE;
            case "content_filter", "content_filtered", "guardrail_intervened", "refusal" -> CONTENT_FILTERED;
            default -> UNKNOWN;
        };
    }
}
