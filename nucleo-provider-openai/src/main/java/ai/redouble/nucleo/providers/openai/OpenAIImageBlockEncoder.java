/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * OpenAI native image: an {@code image_url} content element with a base64 data URL.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class OpenAIImageBlockEncoder extends ImageBlockEncoder<ObjectNode> {
    @Override
    public ObjectNode encode(ContentBlock block) {
        ImageBlock ib = (ImageBlock) block;
        return imageUrlElement(ib.mimeType(), ib.base64());
    }

    /** An {@code image_url} content element carrying the payload as a base64 data URL at automatic detail. */
    static ObjectNode imageUrlElement(String mimeType, String base64) {
        ObjectNode imageContent = NucleoJsonSerializer.createObjectNode();
        imageContent.put("type", "image_url");
        ObjectNode imageUrl = NucleoJsonSerializer.createObjectNode();
        imageUrl.put("url", "data:" + mimeType + ";base64," + base64);
        imageUrl.put("detail", "auto");
        imageContent.set("image_url", imageUrl);
        return imageContent;
    }
}
