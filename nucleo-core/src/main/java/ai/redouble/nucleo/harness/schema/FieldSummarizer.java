/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * Writes what a model is shown in place of a field's value when the value is long.
 *
 * <p>A field names its own summarizer with {@link LLMSummarizable#summarizer()} when its
 * value has a structure that a general summary would lose: JSON, a stack trace, a list of
 * identifiers. The serializer then hands the field's value to that class, whatever the
 * value's type, and uses no other summarizer for the field. The class has a public
 * no-argument constructor; one instance serves the field for the life of the process, so
 * it keeps no state.
 *
 * <p>{@link Summarizer} is this interface for text, and is what a serialization uses for
 * every field that names no summarizer of its own.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 * @param <T> the type of the field's value
 */
public interface FieldSummarizer<T> {
    /**
     * Summarizes a value that exceeds the annotation's threshold.
     *
     * @param value the field's whole value
     * @param annotation the {@code @LLMSummarizable} annotation of the field
     * @return the text a model is shown in place of the value
     */
    String summarize(T value, LLMSummarizable annotation);

    /**
     * What {@link LLMSummarizable#summarizer()} holds on a field that names no summarizer
     * of its own. It is a marker and is never asked to summarize.
     */
    final class None implements FieldSummarizer<Void> {
        @Override
        public String summarize(Void value, LLMSummarizable annotation) {
            throw new UnsupportedOperationException("FieldSummarizer.None marks a field that names no summarizer and summarizes nothing");
        }
    }
}
