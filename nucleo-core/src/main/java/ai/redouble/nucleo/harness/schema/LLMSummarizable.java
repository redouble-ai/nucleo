/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;

import java.lang.annotation.*;

/**
 * Controls whether a text field should be summarized when serialized for LLM consumption.
 *
 * <p>When a field's text exceeds the {@link #threshold()}, the serializer delegates to
 * the active {@link Summarizer} to produce a summary. Works on any
 * object - not limited to artifacts.
 *
 * <p>For artifacts ({@link Artifact}), summaries are cached via
 * {@code getCachedSummary}/{@code cacheSummary} so they're computed once and reused.
 * For other objects, summaries are generated inline during serialization (no caching).
 *
 * <p>The {@link #value()} hint describes the content type. It's passed to the Summarizer
 * for context-aware summarization. A runtime warning is logged if empty.
 *
 * <p>Usage:
 * <pre>
 * {@literal @}LLMSummarizable("web page content")
 * private String content;
 *
 * {@literal @}LLMSummarizable(value = "patent claims", threshold = 2000)
 * private String claims;
 *
 * {@literal @}LLMSummarizable(value = "SMILES notation", staticSummary = "SMILES molecular notation")
 * private String smiles;
 *
 * {@literal @}LLMSummarizable(value = "clinical description", llmSafe = false)
 * private String clinicalConsequence;
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LLMSummarizable {
    /**
     * Content type hint for the Summarizer. Describes what the field contains
     * (e.g., "web page content", "patent claims", "scientific paper abstract").
     * Passed to the Summarizer for context-aware summarization.
     * A runtime warning is logged if empty.
     */
    String value() default "";

    /**
     * Character threshold above which the field will be summarized.
     * Default: 8000 characters (~2000 tokens)
     */
    int threshold() default 8000;

    /**
     * Static summary text to use instead of generating one.
     * Useful for non-text content like chemical identifiers, binary data, code.
     * Example: "SMILES molecular notation", "amino acid sequence"
     * If set (non-empty), this overrides all other summarization.
     */
    String staticSummary() default "";

    /**
     * Whether this field's content can survive LLM paraphrasing without damage.
     * Set to false for structured data, precise clinical descriptions, formatted text,
     * or anything where LLM rewriting would corrupt the content.
     * When false, summarizers use their conservative/lossless path (typically truncation).
     * Default: true (content is safe for LLM summarization)
     */
    boolean llmSafe() default true;

    /**
     * When true, the serializer treats the field's value as already-summarized: instead
     * of invoking the {@link Summarizer}, it consults the artifact's
     * {@code summaryCache} keyed by JSON pointer into the value tree. Producers (e.g.
     * the MCP walker) pre-populate the cache; the serializer just reads it.
     *
     * <p>Required for non-String fields (Map / List shapes) where threshold-based
     * String summarization does not apply. The {@link #threshold()} is ignored when
     * {@code preSummarized=true}; presence of a cache entry at a leaf's path is the
     * sole trigger.
     *
     * <p>Default: {@code false} (existing String-field summarization behavior).
     */
    boolean preSummarized() default false;

    /**
     * Target summary size. Drives both the LLM summarizer's prompt phrasing and
     * {@code max_tokens} backstop, and the truncation fallback's character budget.
     * The summarizer never sees the size as a number - it is rendered as a prose
     * phrase ("a one or two sentence overview", "about a paragraph", etc.) so the
     * model is steered structurally rather than given a precise count to verify.
     *
     * <p>Default: {@link SummarySize#SHORT} (about a paragraph). Audited as the
     * natural size for general prose summary fields. Override on fields whose
     * content genuinely wants a one-line snippet ({@link SummarySize#BRIEF}) or a
     * couple of paragraphs ({@link SummarySize#PARAGRAPHS}) for long-form
     * documents.
     */
    SummarySize size() default SummarySize.SHORT;
}
