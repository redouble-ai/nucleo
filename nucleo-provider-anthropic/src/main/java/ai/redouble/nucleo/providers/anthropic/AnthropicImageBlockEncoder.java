/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import com.anthropic.models.messages.*;
import org.slf4j.*;

/**
 * Anthropic native image: a base64 {@code ImageBlockParam}. The MIME-to-media-type mapping lives here
 * and is reused by {@link AnthropicFileBlockEncoder} for image files, so it is written once.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicImageBlockEncoder extends ImageBlockEncoder<ContentBlockParam> {
    private static final Logger log = LoggerFactory.getLogger(AnthropicImageBlockEncoder.class);
    @Override
    public ContentBlockParam encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.ImageBlock i = (ContentBlocks.ImageBlock) block;
        Base64ImageSource source = Base64ImageSource.builder().mediaType(mediaType(i.mimeType())).data(i.base64()).build();
        return ContentBlockParam.ofImage(ImageBlockParam.builder().source(ImageBlockParam.Source.ofBase64(source)).build());
    }

    static Base64ImageSource.MediaType mediaType(String mimeType) {
        if (mimeType == null) {
            return Base64ImageSource.MediaType.IMAGE_PNG;
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> {
                log.warn("Unknown image MIME type for Anthropic: {}, defaulting to PNG", mimeType);
                yield Base64ImageSource.MediaType.IMAGE_PNG;
            }
        };
    }
}
