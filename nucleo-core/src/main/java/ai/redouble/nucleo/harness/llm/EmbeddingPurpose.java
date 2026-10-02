/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;


/**
 * What an embedding is for. Some providers (e.g. Cohere) embed the same text
 * differently depending on whether it is a stored corpus item or a live search
 * probe, and require the distinction on every call. Providers without that
 * distinction (OpenAI, Titan) ignore it. The split is a property of the call
 * site, so it travels through the call signature rather than any shared state.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public enum EmbeddingPurpose {
    /** A stored corpus item: chunk text, a saved query, a conversation objective. */
    DOCUMENT,
    /** A live search probe compared against stored DOCUMENT vectors. */
    QUERY
}