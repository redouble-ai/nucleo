/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.FileBlock;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file on Converse travels as the file itself: a PDF (or another document type Converse
 * reads) as a native document block with the decoded bytes and a name Converse accepts, an
 * image file as a native image block, and a type Converse cannot carry as nothing - never as a
 * text placeholder that leaves the model with only a file name.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class BedrockFileBlockEncoderTest {
    private static BedrockFileBlockEncoder encoder() {
        BedrockFileBlockEncoder encoder = new BedrockFileBlockEncoder();
        encoder.setTextWrapper(t -> { throw new AssertionError("a file never rides the text wrapper: " + t); });
        return encoder;
    }

    private static String base64(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPdfBecomesANativeDocumentWithItsBytes() {
        ContentBlock encoded = encoder().encode(new FileBlock(base64("%PDF-1.7 body"), "application/pdf", "scanned-letter.pdf"));
        DocumentBlock document = encoded.document();
        assertNotNull(document, "a native document block");
        assertEquals(DocumentFormat.PDF, document.format());
        assertEquals("%PDF-1.7 body", document.source().bytes().asUtf8String(), "the file's own bytes, decoded");
        assertEquals("scanned-letter pdf", document.name(), "reduced to the characters Converse accepts");
    }

    @Test
    void everyDocumentTypeConverseReadsMapsToItsFormat() {
        assertEquals(DocumentFormat.DOCX, BedrockFileBlockEncoder.documentFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(DocumentFormat.DOC, BedrockFileBlockEncoder.documentFormat("application/msword"));
        assertEquals(DocumentFormat.XLSX, BedrockFileBlockEncoder.documentFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertEquals(DocumentFormat.XLS, BedrockFileBlockEncoder.documentFormat("application/vnd.ms-excel"));
        assertEquals(DocumentFormat.CSV, BedrockFileBlockEncoder.documentFormat("text/csv"));
        assertEquals(DocumentFormat.HTML, BedrockFileBlockEncoder.documentFormat("text/html"));
        assertEquals(DocumentFormat.TXT, BedrockFileBlockEncoder.documentFormat("text/plain"));
        assertEquals(DocumentFormat.MD, BedrockFileBlockEncoder.documentFormat("text/markdown"));
        assertNull(BedrockFileBlockEncoder.documentFormat("application/zip"), "a type Converse reads no document of");
    }

    @Test
    void anImageFileBecomesANativeImage() {
        ContentBlock encoded = encoder().encode(new FileBlock(base64("jpeg bytes"), "image/jpeg", "whiteboard.jpg"));
        assertNotNull(encoded.image());
        assertEquals(ImageFormat.JPEG, encoded.image().format());
    }

    @Test
    void aTypeConverseCannotCarryProducesNothing() {
        assertNull(encoder().encode(new FileBlock(base64("PK"), "application/zip", "bundle.zip")));
    }

    @Test
    void aNameIsReducedToWhatConverseAcceptsAndNeverEmpty() {
        assertEquals("Q1 report (final) [v2]", BedrockFileBlockEncoder.documentName("Q1_report  (final) [v2]"));
        assertEquals(BedrockFileBlockEncoder.UNNAMED, BedrockFileBlockEncoder.documentName("..."));
        assertEquals(BedrockFileBlockEncoder.UNNAMED, BedrockFileBlockEncoder.documentName(null));
    }
}
