/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/** A single pin from the page: the slot ({@code embeddings} or {@code decision}) and the entry id; null clears the slot. */
public record PinEdit(String slot, String id) {}
