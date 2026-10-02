/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.sources;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;

/**
 * Fetches prompt content from a remote URL, passing the key as a query parameter. Like
 * {@link DbTextSource}, the HTTP mechanics are injected via a caller-provided fetcher so
 * this module does not depend on any specific HTTP client.
 *
 * <p>Not a {@link StaticPromptSource} - the remote endpoint may return different content
 * between calls (version updates, feature flags, etc.).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class RemoteTextSource implements PromptSource {
    private final Fetcher fetcher;

    public RemoteTextSource(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public JsonNode produce(String key) {
        return fetcher.fetch(key);
    }

    @FunctionalInterface
    public interface Fetcher {
        JsonNode fetch(String key);
    }
}
