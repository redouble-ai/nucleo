/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

/**
 * OpenAI native file: image-compatible files become an {@code image_url} element; anything else has
 * no OpenAI file channel and falls back to a filename text element via the injected wrapper.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class OpenAIFileBlockEncoder extends FileBlockEncoder<ObjectNode> {
    private static final Logger log = LoggerFactory.getLogger(OpenAIFileBlockEncoder.class);
    @Override
    public ObjectNode encode(ContentBlock block) {
        FileBlock fb = (FileBlock) block;
        if (isImageCompatible(fb.mimeType())) {
            return OpenAIImageBlockEncoder.imageUrlElement(fb.mimeType(), fb.base64());
        }
        log.warn("OpenAI doesn't directly support file type: {}, adding filename to text: {}", fb.mimeType(), fb.filename());
        return textWrapper.wrap("[File: " + fb.filename() + " (" + fb.mimeType() + ")]");
    }

    private static boolean isImageCompatible(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase();
        return lower.startsWith("image/") && (lower.contains("jpeg") || lower.contains("jpg")
                || lower.contains("png") || lower.contains("gif") || lower.contains("webp"));
    }
}
