/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

/**
 * Bedrock Converse native image: a {@code ContentBlock.image} carrying the decoded bytes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class BedrockImageBlockEncoder extends ImageBlockEncoder<ContentBlock> {
    @Override
    public ContentBlock encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.ImageBlock ib = (ContentBlocks.ImageBlock) block;
        return ContentBlock.builder()
                .image(ImageBlock.builder()
                        .format(format(ib.mimeType()))
                        .source(ImageSource.builder()
                                .bytes(SdkBytes.fromByteArray(Base64.getDecoder().decode(ib.base64())))
                                .build())
                        .build())
                .build();
    }

    /** The Converse image format of a MIME type; the file encoder reuses it for image files. */
    static ImageFormat format(String mimeType) {
        if (mimeType == null) {
            return ImageFormat.PNG;
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ImageFormat.JPEG;
            case "image/gif" -> ImageFormat.GIF;
            case "image/webp" -> ImageFormat.WEBP;
            default -> ImageFormat.PNG;
        };
    }
}
