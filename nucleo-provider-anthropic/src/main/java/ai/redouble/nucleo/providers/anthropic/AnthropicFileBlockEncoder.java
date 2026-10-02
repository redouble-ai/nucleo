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
 * Anthropic native file: PDFs become a {@code DocumentBlockParam}, image files become an
 * {@code ImageBlockParam} (reusing {@link AnthropicImageBlockEncoder#mediaType}); any other type has
 * no Anthropic representation and produces nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicFileBlockEncoder extends FileBlockEncoder<ContentBlockParam> {
    private static final Logger log = LoggerFactory.getLogger(AnthropicFileBlockEncoder.class);
    @Override
    public ContentBlockParam encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.FileBlock fb = (ContentBlocks.FileBlock) block;
        String mimeType = fb.mimeType() != null ? fb.mimeType().toLowerCase() : "";
        if (mimeType.equals("application/pdf")) {
            Base64PdfSource pdf = Base64PdfSource.builder().data(fb.base64()).build();
            return ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(DocumentBlockParam.Source.ofBase64(pdf)).build());
        }
        if (isImage(mimeType)) {
            Base64ImageSource source = Base64ImageSource.builder()
                    .mediaType(AnthropicImageBlockEncoder.mediaType(mimeType)).data(fb.base64()).build();
            return ContentBlockParam.ofImage(ImageBlockParam.builder().source(ImageBlockParam.Source.ofBase64(source)).build());
        }
        log.warn("Unsupported file type for Anthropic: {}, skipping file: {}", fb.mimeType(), fb.filename());
        return null;
    }

    private static boolean isImage(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp" -> true;
            default -> false;
        };
    }
}
