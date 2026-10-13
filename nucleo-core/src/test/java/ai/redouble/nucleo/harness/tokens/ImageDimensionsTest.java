/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ImageDimensions} reads the pixel size from the header of a PNG, JPEG, GIF or WebP
 * payload and answers null for anything else, bytes that are not an image and text that is
 * not base64 alike.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class ImageDimensionsTest {

    static byte[] png(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
        out.writeBytes(be32(width));
        out.writeBytes(be32(height));
        out.writeBytes(new byte[]{8, 6, 0, 0, 0});
        return out.toByteArray();
    }

    static String pngBase64(int width, int height) {
        return Base64.getEncoder().encodeToString(png(width, height));
    }

    private static byte[] be32(int value) {
        return new byte[]{(byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value};
    }

    @Test
    void aPngHeaderCarriesWidthThenHeight() {
        assertEquals(new ImageDimensions(1024, 768), ImageDimensions.of(png(1024, 768)));
        assertEquals(new ImageDimensions(1024, 768), ImageDimensions.of(new ImageBlock(pngBase64(1024, 768), "image/png", null)));
    }

    @Test
    void aGifHeaderCarriesLittleEndianSizes() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', (byte)0x20, 0x03, (byte)0x58, 0x02, 0, 0};
        assertEquals(new ImageDimensions(800, 600), ImageDimensions.of(gif));
    }

    @Test
    void aJpegStartOfFrameCarriesHeightThenWidth() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte)0xFF, (byte)0xD8});
        out.writeBytes(new byte[]{(byte)0xFF, (byte)0xE0, 0, 4, 'J', 'F'});
        out.writeBytes(new byte[]{(byte)0xFF, (byte)0xC0, 0, 11, 8, 0x04, 0x38, 0x07, (byte)0x80, 3, 1, 0x22, 0});
        assertEquals(new ImageDimensions(1920, 1080), ImageDimensions.of(out.toByteArray()), "the first SOF marker, past the APP0 segment");
    }

    @Test
    void aWebpExtendedHeaderCarriesSizesMinusOne() {
        byte[] webp = new byte[30];
        System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, webp, 8, 4);
        System.arraycopy("VP8X".getBytes(), 0, webp, 12, 4);
        webp[24] = (byte)0xFF;
        webp[25] = 0x03;
        webp[27] = (byte)0xFF;
        webp[28] = 0x01;
        assertEquals(new ImageDimensions(1024, 512), ImageDimensions.of(webp));
    }

    @Test
    void anythingElseHasNoDimensions() {
        assertNull(ImageDimensions.of(new byte[3 * 1024]), "bytes that are no image header");
        assertNull(ImageDimensions.of(new ImageBlock("%%not base64%%", "image/png", null)), "text that is not base64");
        assertNull(ImageDimensions.of(new ImageBlock(null, "image/png", null)));
        assertNull(ImageDimensions.of((ImageBlock)null));
        assertNull(ImageDimensions.of(new byte[]{(byte)0xFF, (byte)0xD8, 0, 0}), "a JPEG that ends before any frame");
    }
}
