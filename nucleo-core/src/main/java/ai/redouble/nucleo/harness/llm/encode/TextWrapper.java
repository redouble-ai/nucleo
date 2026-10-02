/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;


/**
 * Wraps a plain string into a provider's native text content block of type {@code B}
 * (Anthropic {@code ContentBlockParam}, OpenAI {@code ObjectNode}, Bedrock {@code ContentBlock}).
 *
 * <p>The single provider-specific text primitive. {@link BlockEncoder}s that render a block as text
 * hold a {@code TextWrapper} and call it; the per-block content string is the encoder's concern, the
 * string-to-native-block step is the wrapper's. One implementation per provider, reused by every text
 * encoder so the wrap is written exactly once.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
@FunctionalInterface
public interface TextWrapper<B> {
    B wrap(String text);
}
