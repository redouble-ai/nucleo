/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.sources;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;

import java.util.concurrent.*;

/**
 * {@link PromptSource} dispatching to one of two inner sources by a configurable
 * probability. The key is forwarded to whichever branch is selected so the branch sources
 * can honor it (e.g. a {@link ai.redouble.nucleo.prompt.sources.DbTextSource} can look up the key).
 *
 * <p>{@code probA} must be in {@code [0, 1]}; anything else is refused at construction
 * with {@link IllegalArgumentException}. Uses {@link ThreadLocalRandom} for dispatch -
 * no shared state, contention-free. Not a {@link StaticPromptSource}: output varies
 * call-to-call, so the Prompts facade re-produces on every request.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class AbTestSource implements PromptSource {
    private final PromptSource sourceA;
    private final PromptSource sourceB;
    private final double probA;

    public AbTestSource(PromptSource sourceA, PromptSource sourceB, double probA) {
        if (probA < 0.0 || probA > 1.0) {
            throw new IllegalArgumentException("probA must be in [0, 1], got " + probA);
        }
        this.sourceA = sourceA;
        this.sourceB = sourceB;
        this.probA = probA;
    }

    @Override
    public JsonNode produce(String key) {
        return ThreadLocalRandom.current().nextDouble() < probA
               ? sourceA.produce(key)
               : sourceB.produce(key);
    }
}
