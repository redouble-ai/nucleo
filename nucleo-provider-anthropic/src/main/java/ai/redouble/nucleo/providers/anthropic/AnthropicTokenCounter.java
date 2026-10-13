/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.knuddels.jtokkit.api.*;

/**
 * The counter for Claude: {@code cl100k_base} at Claude's density, and Anthropic's published
 * image rule. An image whose long side exceeds 1568 pixels is scaled down to it, and the image
 * then costs its pixel count over 750, at most 1600 tokens, since Anthropic scales any larger
 * image down to that ceiling. An image whose size cannot be read from its header falls back to
 * the generic rule with the ceiling as its floor, so the estimate errs high.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
public class AnthropicTokenCounter extends TiktokenCounter {
    /** Claude tokens per cl100k token, measured against Anthropic's usage reports. */
    public static final double CLAUDE_DENSITY = 1.10;
    public static final int IMAGE_MAX_TOKENS = 1600;
    public static final int IMAGE_PIXELS_PER_TOKEN = 750;
    public static final int LONG_SIDE_LIMIT = 1568;

    public AnthropicTokenCounter() {
        super(EncodingType.CL100K_BASE, CLAUDE_DENSITY, IMAGE_MAX_TOKENS, DEFAULT_PDF_TOKENS_PER_KB);
    }

    @Override
    public int countImageTokens(ImageBlock image) {
        ImageDimensions size = ImageDimensions.of(image);
        if (size == null) {
            return super.countImageTokens(image);
        }
        return imageTokens(size.width(), size.height());
    }

    /** Anthropic's image price for an image of the given pixel size. */
    public static int imageTokens(int width, int height) {
        double w = width;
        double h = height;
        double longSide = Math.max(w, h);
        if (longSide > LONG_SIDE_LIMIT) {
            double scale = LONG_SIDE_LIMIT / longSide;
            w *= scale;
            h *= scale;
        }
        return (int)Math.min(IMAGE_MAX_TOKENS, Math.ceil(w * h / IMAGE_PIXELS_PER_TOKEN));
    }
}
