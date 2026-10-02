/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import com.fasterxml.jackson.databind.*;

/**
 * Produces the content for a Prompt given its key. Functional interface - one method,
 * one argument. The key is threaded through from {@code Prompts.produce(key)}, so a
 * single source (e.g. a DB-backed source) can transparently back every registered key.
 *
 * <p>Instance state is structurally excluded: {@code produce(String key)} has no access
 * to thinker fields, caller objects, or any per-invocation context. A Prompt identified
 * by {@code key} must resolve to the same conceptual artifact regardless of who invokes
 * it; otherwise DB / A/B / remote substitution is impossible. Data that varies per
 * invocation belongs in the {@code ThinkerObjective.input} field alongside the Prompt,
 * not inside a PromptSource body.
 *
 * <p>Legitimate runtime variation (today's date, environment flags, feature toggles) is
 * allowed: read PROCESS-GLOBAL state inside the source body. Thread safety is the
 * source's responsibility.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
@FunctionalInterface
public interface PromptSource {
    JsonNode produce(String key);
}
