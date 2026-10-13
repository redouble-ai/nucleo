/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.util.*;

/**
 * The pixel size of an image, read from the first bytes of its payload: PNG, JPEG, GIF and
 * WebP headers, which is what a provider's image billing is computed from. Nothing is decoded
 * beyond the header, so an estimate costs a few hundred bytes of parsing per image and no
 * imaging library.
 *
 * @param width  pixels across
 * @param height pixels down
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
public record ImageDimensions(int width, int height) {

    /** The dimensions of the block's image, or null when its bytes are not a header this reader knows. */
    public static ImageDimensions of(ImageBlock image) {
        if (image == null || image.base64() == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image.base64());
        }
        catch (IllegalArgumentException notBase64) {
            return null;
        }
        return of(bytes);
    }

    /** The dimensions encoded in the header of a PNG, JPEG, GIF or WebP payload, or null for anything else. */
    public static ImageDimensions of(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G') && bytes.length >= 24) {
            return new ImageDimensions(be32(bytes, 16), be32(bytes, 20));
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8') && bytes.length >= 10) {
            return new ImageDimensions(le16(bytes, 6), le16(bytes, 8));
        }
        if (startsWith(bytes, 0xFF, 0xD8)) {
            return jpeg(bytes);
        }
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && bytes.length >= 30 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return webp(bytes);
        }
        return null;
    }

    /** JPEG: the first start-of-frame marker carries height then width, big-endian. */
    private static ImageDimensions jpeg(byte[] bytes) {
        int i = 2;
        while (i + 9 < bytes.length) {
            if ((bytes[i] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = bytes[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                return new ImageDimensions(be16(bytes, i + 7), be16(bytes, i + 5));
            }
            int length = be16(bytes, i + 2);
            if (length < 2) {
                return null;
            }
            i += 2 + length;
        }
        return null;
    }

    /** WebP: VP8X carries 24-bit sizes minus one, VP8L packs 14-bit sizes minus one, VP8 carries 14-bit sizes. */
    private static ImageDimensions webp(byte[] bytes) {
        String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X" -> {
                int width = 1 + (bytes[24] & 0xFF | (bytes[25] & 0xFF) << 8 | (bytes[26] & 0xFF) << 16);
                int height = 1 + (bytes[27] & 0xFF | (bytes[28] & 0xFF) << 8 | (bytes[29] & 0xFF) << 16);
                return new ImageDimensions(width, height);
            }
            case "VP8L" -> {
                int packed = bytes[21] & 0xFF | (bytes[22] & 0xFF) << 8 | (bytes[23] & 0xFF) << 16 | (bytes[24] & 0xFF) << 24;
                return new ImageDimensions(1 + (packed & 0x3FFF), 1 + (packed >> 14 & 0x3FFF));
            }
            case "VP8 " -> {
                return new ImageDimensions(le16(bytes, 26) & 0x3FFF, le16(bytes, 28) & 0x3FFF);
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static int be32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static int be16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }
}
