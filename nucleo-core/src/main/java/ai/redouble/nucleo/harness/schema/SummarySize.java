/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;


/**
 * Target size for a summary produced from an {@link LLMSummarizable} field.
 *
 * <p>Pure vocabulary - the enum carries no strings and no numbers. The summarization
 * tool maps each value to a prose prompt phrase (so the model is steered structurally
 * rather than given a precise count to verify) and to a hard {@code max_tokens}
 * backstop (so runaway output is bounded). The truncation fallback maps to a parallel
 * character budget so the LLM happy path and the truncation path produce comparably
 * sized output.
 *
 * <p>The annotation default is {@link #SHORT}, audited as the natural size for general
 * prose summaries (web page content, article abstracts, drug label sections, etc.).
 * Fields that want explicit deviations declare it on the annotation:
 * <pre>
 * {@literal @}LLMSummarizable(value = "web page text from search result", size = SummarySize.BRIEF)
 * private String text;
 *
 * {@literal @}LLMSummarizable(value = "patent specification", size = SummarySize.PARAGRAPHS)
 * private String specification;
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-22)
 */
public enum SummarySize {
    /** A one or two sentence overview. Use for short context-insertion snippets. */
    BRIEF,
    /** A short summary of about a paragraph. Default for general prose. */
    SHORT,
    /** A couple of short paragraphs. Use for long-form documents where one paragraph loses too much. */
    PARAGRAPHS
}
