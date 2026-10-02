/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.sources;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;

/**
 * DB-backed {@link PromptSource} that looks up content by key from a caller-provided
 * lookup function. The function is expected to issue something like
 * {@code SELECT content FROM prompt WHERE key = ?} and return the row's content as a
 * {@link JsonNode}.
 *
 * <p>The lookup callable is passed in rather than a concrete DB handle so the module
 * stays independent of any specific session/transaction API. Apps wire their own
 * implementation at bootstrap and pass the resulting source to
 * {@code Prompts.setGlobalBackend(...)} or {@code Prompts.replace(key, ...)}.
 *
 * <p>Not a {@link StaticPromptSource} - the backing row can change between calls.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class DbTextSource implements PromptSource {
    private final Lookup lookup;

    public DbTextSource(Lookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public JsonNode produce(String key) {
        return lookup.fetch(key);
    }

    @FunctionalInterface
    public interface Lookup {
        JsonNode fetch(String key);
    }
}
