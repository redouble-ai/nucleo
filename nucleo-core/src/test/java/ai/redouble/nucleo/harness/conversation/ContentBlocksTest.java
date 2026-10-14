/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payload blocks refuse to exist without their payload: an {@link ImageBlock} or a
 * {@link FileBlock} built with a null base64 string is refused at construction, naming the
 * block and the field, so no counter, encoder or reader downstream ever meets one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-14)
 */
class ContentBlocksTest {

    @Test
    void anImageBlockWithoutItsPayloadIsRefusedAtConstruction() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> new ImageBlock(null, "image/png", "a probe"),
                "a block the wire cannot carry is never built");
        assertTrue(refusal.getMessage().contains("ImageBlock") && refusal.getMessage().contains("base64"), refusal.getMessage());
        assertEquals("AA==", new ImageBlock("AA==", "image/png", null).base64(), "a payload, however small, is a block");
    }

    @Test
    void aFileBlockWithoutItsPayloadIsRefusedAtConstruction() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> new FileBlock(null, "application/pdf", "doc.pdf"),
                "a block the wire cannot carry is never built");
        assertTrue(refusal.getMessage().contains("FileBlock") && refusal.getMessage().contains("base64"), refusal.getMessage());
        assertEquals("AA==", new FileBlock("AA==", "application/pdf", "doc.pdf").base64(), "a payload, however small, is a block");
    }
}
