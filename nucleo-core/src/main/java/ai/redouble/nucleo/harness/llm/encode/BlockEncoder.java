/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Encodes one {@link ContentBlock} into a provider's native content block of type {@code B}.
 *
 * <p>One concrete encoder per block type forms the base layer ({@link TextBlockEncoder},
 * {@link ImageBlockEncoder}, ...); a provider that renders a block natively subclasses the logical
 * parent and overrides {@link #encode} (e.g. {@code AnthropicImageBlockEncoder extends
 * ImageBlockEncoder}). The text-rendering bases are shared across providers - the provider-specific
 * "string to native text block" step is the injected {@link #textWrapper}, so the bases carry no
 * provider knowledge and the wrap is written once per provider.
 *
 * <p>Encoders are looked up by block class (a {@code Class -> Class} map on the client) and
 * instantiated through the no-arg constructor, so they hold no construction-time state; the wrapper
 * is injected via {@link #setTextWrapper} before first use. {@link #encode} returns {@code null} when
 * the block produces no wire content (an empty text block, a thinking block on a provider with no
 * thinking channel, a tool definition that travels out-of-band).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public abstract class BlockEncoder<B> {
    protected TextWrapper<B> textWrapper;

    public void setTextWrapper(TextWrapper<B> textWrapper) {
        this.textWrapper = textWrapper;
    }

    public abstract B encode(ContentBlock block);
}
