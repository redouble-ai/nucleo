/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.knuddels.jtokkit.api.*;

/**
 * The counter for OpenAI's own chat models: exact {@code o200k_base} text, and OpenAI's
 * published image rule. An image is scaled to fit 2048 pixels on its long side and then 768 on
 * its short side, cut into 512-pixel tiles, and costs 85 tokens plus 170 per tile; the client
 * sends images at automatic detail, which OpenAI serves at that high-detail price for anything
 * but a thumbnail. An image whose size cannot be read from its header falls back to the
 * generic rule, the floor of 85 or four tokens per KB.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
public class OpenAITokenCounter extends TiktokenCounter {
    public static final int IMAGE_BASE_TOKENS = 85;
    public static final int IMAGE_TILE_TOKENS = 170;
    public static final int TILE_PIXELS = 512;
    public static final int LONG_SIDE_LIMIT = 2048;
    public static final int SHORT_SIDE_LIMIT = 768;

    public OpenAITokenCounter() {
        super(EncodingType.O200K_BASE, 1.0, IMAGE_BASE_TOKENS, DEFAULT_PDF_TOKENS_PER_KB);
    }

    @Override
    public int countImageTokens(ImageBlock image) {
        ImageDimensions size = ImageDimensions.of(image);
        if (size == null) {
            return super.countImageTokens(image);
        }
        return imageTokens(size.width(), size.height());
    }

    /** OpenAI's high-detail image price for an image of the given pixel size. */
    public static int imageTokens(int width, int height) {
        double w = width;
        double h = height;
        double longSide = Math.max(w, h);
        if (longSide > LONG_SIDE_LIMIT) {
            double scale = LONG_SIDE_LIMIT / longSide;
            w *= scale;
            h *= scale;
        }
        double shortSide = Math.min(w, h);
        if (shortSide > SHORT_SIDE_LIMIT) {
            double scale = SHORT_SIDE_LIMIT / shortSide;
            w *= scale;
            h *= scale;
        }
        int tiles = (int)Math.ceil(w / TILE_PIXELS) * (int)Math.ceil(h / TILE_PIXELS);
        return IMAGE_BASE_TOKENS + IMAGE_TILE_TOKENS * tiles;
    }
}
