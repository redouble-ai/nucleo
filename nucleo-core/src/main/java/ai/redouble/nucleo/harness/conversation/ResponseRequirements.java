/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

/**
 * What the model about to answer requires of a reply, as far as it changes how a reply
 * format is written for it. One class describes one reply, and the text that defines it is
 * written per model: a model that calls tools through its own channel is not asked to write
 * tool calls into the reply, and a model whose reasoning travels in its own block is not
 * asked to write it into the reply either. A {@link ResponseHandler} is given this value when
 * it writes its definition, and a conversation keeps one written definition per format name
 * and per value, so a conversation answered by one kind of model has its format written once.
 *
 * <p>A requirement a provider introduces later is a new component here; no method that takes
 * the value changes.
 *
 * @author Andrey Santrosyan
 * @since 0.1.4 (2026-11-15)
 * @param nativeToolCalling whether the model calls tools through the provider's own tool channel
 * @param nativeThinking    whether a native thinking block is guaranteed to carry the model's reasoning
 */
public record ResponseRequirements(boolean nativeToolCalling, boolean nativeThinking) {
}
