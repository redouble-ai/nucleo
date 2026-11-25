/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;

import java.lang.annotation.*;

/**
 * Marks a field of an artifact that a model is shown as a summary when its value is long.
 *
 * <p>The annotation is legal only on a field declared in a class that implements
 * {@link Artifact}, because a model that is shown a summary must be able to read the
 * whole value, and an artifact is what it can read back by reference
 * ({@code get_artifact_field}). A field of any type may carry it. The placement is
 * checked three times: by the annotation processor of {@code nucleo-processor} at
 * compile time, by the Quarkus build, and when {@code JobDispatcher} starts, which
 * refuses to start with a {@link SummarizableOutsideArtifactException} that names every
 * misplaced field.
 *
 * <p>The serializer makes the summary, working on the value as it would write it without
 * the annotation: a text as it is, any other value as its JSON. A value longer than the
 * {@link #threshold()} is shown as its summary, which is one of, in this order: the
 * {@link #staticSummary()}; what the field's own {@link #summarizer()} returns; a cut of
 * the text when {@link #llmSafe()} is false; a model's summary, made once before a
 * conversation is sent. Until a model's summary is made the text is shown cut. A summary
 * is kept on the artifact ({@link Artifact#getSummaries()}) and stored with a persisted
 * conversation, so a model is shown the same summary every time; the artifact itself
 * always holds, and is stored with, the whole value.
 *
 * <p>The {@link #value()} hint describes the content type and is given to the model that
 * summarizes. A runtime warning is logged if it is empty.
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
 *
 * {@literal @}LLMSummarizable(value = "stack trace", threshold = 500, summarizer = TopFrames.class)
 * private String stackTrace;
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
     * Content type hint. Describes what the field contains
     * (e.g., "web page content", "patent claims", "scientific paper abstract").
     * Given to the model that summarizes the field.
     * A runtime warning is logged if empty.
     */
    String value() default "";

    /**
     * Length above which the field is shown as a summary: the characters of a text, and
     * for any other value the characters of its compact JSON.
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
     * When false, the content never reaches a model for summarizing: it is shown cut to
     * the {@link #size()}'s budget, marked with its full length.
     * Default: true (content is safe for LLM summarization)
     */
    boolean llmSafe() default true;

    /**
     * When true, the field's value is a tree (a map or a list) that keeps its shape for
     * the model, and each text leaf of it longer than the {@link #threshold()} is shown as
     * its own summary. A leaf's summary is kept under the field's name followed by the
     * leaf's JSON pointer ({@code data/results/0/text}), which is also the path
     * {@code get_artifact_field} reads the whole leaf by.
     *
     * <p>Default: {@code false}: the field's value is summarized as one.
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

    /**
     * The class that summarizes this field, for a value whose structure a general summary
     * would lose: JSON, a stack trace, a list of identifiers. When stated, the serializer
     * hands the field's value to an instance of it, whatever the value's type, and no other
     * summarizer is used for the field. The value is summarized when it is longer than
     * {@link #threshold()}: the length of a text, and for any other value the length of
     * its JSON. The class has a public no-argument constructor.
     *
     * <p>Default: {@link FieldSummarizer.None}, which states none. The field's text, or
     * the JSON of a field of another type, is then cut or summarized by a model, as the
     * class documentation states.
     */
    Class<? extends FieldSummarizer<?>> summarizer() default FieldSummarizer.None.class;
}
