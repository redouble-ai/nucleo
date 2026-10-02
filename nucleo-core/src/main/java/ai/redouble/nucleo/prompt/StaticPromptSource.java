/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

/**
 * Marker sub-interface for {@link PromptSource} implementations whose {@code produce(key)}
 * always returns identical content for the same key. The {@link Prompts} facade caches
 * Prompts produced by a static source indefinitely until the source is replaced.
 *
 * <p>Dynamic sources (e.g. A/B dispatch, DB-backed lookups, date-dependent lambdas)
 * MUST NOT implement this interface; their output is re-produced on every call.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public interface StaticPromptSource extends PromptSource {
}
