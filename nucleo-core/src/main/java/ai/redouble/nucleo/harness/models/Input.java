/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * What a request sends a model beyond text, and what a catalog entry says the model accepts.
 * A request declares the inputs it sends on its binding ({@code ModelBinding.setSends}), the
 * picker serves it only from an entry that accepts every one of them ({@link ModelSpec#accepts}),
 * and the gate refuses a payload carrying an input the request did not declare or the entry
 * does not accept, so which model serves a request is decided when it is declared, never by
 * what its history happens to hold.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public enum Input {
    /** Pictures: an image block, read by an entry whose catalog record says {@code supports_vision}. */
    IMAGES,
    /** Files sent whole, a PDF as a document: a file block, read by an entry whose record says {@code supports_documents}. */
    DOCUMENTS
}
