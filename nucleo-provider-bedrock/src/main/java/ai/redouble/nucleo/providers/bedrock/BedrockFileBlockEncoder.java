/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import org.slf4j.*;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

/**
 * Bedrock Converse native file: a document type Converse reads (PDF, Word, Excel, CSV, HTML,
 * text, Markdown) becomes a {@code ContentBlock.document} carrying the decoded bytes, an image
 * file becomes a {@code ContentBlock.image} (the image encoder's format mapping); any other type
 * has no Converse representation and produces nothing.
 *
 * <p>Converse requires every document to carry a name of letters, digits, single spaces, hyphens,
 * parentheses and square brackets, so the file name is reduced to those characters (the rest
 * become spaces) and a file without a usable name is named {@code document}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class BedrockFileBlockEncoder extends FileBlockEncoder<ContentBlock> {
    private static final Logger log = LoggerFactory.getLogger(BedrockFileBlockEncoder.class);
    /** The name a document gets when its file name leaves nothing Converse accepts. */
    static final String UNNAMED = "document";

    @Override
    public ContentBlock encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.FileBlock fb = (ContentBlocks.FileBlock) block;
        String mimeType = fb.mimeType() != null ? fb.mimeType().toLowerCase(Locale.ROOT) : "";
        byte[] bytes = Base64.getDecoder().decode(fb.base64());
        DocumentFormat format = documentFormat(mimeType);
        if (format != null) {
            return ContentBlock.builder()
                    .document(DocumentBlock.builder()
                            .format(format)
                            .name(documentName(fb.filename()))
                            .source(DocumentSource.builder().bytes(SdkBytes.fromByteArray(bytes)).build())
                            .build())
                    .build();
        }
        if (mimeType.startsWith("image/")) {
            return ContentBlock.builder()
                    .image(ImageBlock.builder()
                            .format(BedrockImageBlockEncoder.format(mimeType))
                            .source(ImageSource.builder().bytes(SdkBytes.fromByteArray(bytes)).build())
                            .build())
                    .build();
        }
        log.warn("Unsupported file type for Bedrock Converse: {}, skipping file: {}", fb.mimeType(), fb.filename());
        return null;
    }

    /** The Converse document format of a MIME type, or null when Converse reads no document of that type. */
    static DocumentFormat documentFormat(String mimeType) {
        return switch (mimeType) {
            case "application/pdf" -> DocumentFormat.PDF;
            case "application/msword" -> DocumentFormat.DOC;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentFormat.DOCX;
            case "application/vnd.ms-excel" -> DocumentFormat.XLS;
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> DocumentFormat.XLSX;
            case "text/csv" -> DocumentFormat.CSV;
            case "text/html" -> DocumentFormat.HTML;
            case "text/plain" -> DocumentFormat.TXT;
            case "text/markdown" -> DocumentFormat.MD;
            default -> null;
        };
    }

    /** The file name reduced to the characters Converse accepts in a document name, single-spaced. */
    static String documentName(String filename) {
        if (filename == null) {
            return UNNAMED;
        }
        String name = filename.replaceAll("[^A-Za-z0-9\\-()\\[\\] ]", " ").replaceAll("\\s+", " ").strip();
        return name.isEmpty() ? UNNAMED : name;
    }
}
