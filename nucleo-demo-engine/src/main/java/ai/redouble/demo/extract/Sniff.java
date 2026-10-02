/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import java.io.*;
import java.nio.file.*;

/**
 * What a file is by its first bytes, which no extension can lie about. The formats the
 * tiers know are signed: PNG, JPEG, GIF, WEBP, PDF, the zip that every Office document is,
 * and the OLE2 container of the old ones. A file with none of these signatures is text or
 * an unsigned binary, which is the classifier's question.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public final class Sniff {
    public enum Kind {PNG, JPEG, GIF, WEBP, PDF, OFFICE, UNSIGNED}

    private Sniff() {}

    public static Kind of(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(12);
        }
        if (starts(head, 0x89, 'P', 'N', 'G')) {
            return Kind.PNG;
        }
        if (starts(head, 0xFF, 0xD8, 0xFF)) {
            return Kind.JPEG;
        }
        if (starts(head, 'G', 'I', 'F', '8')) {
            return Kind.GIF;
        }
        if (starts(head, 'R', 'I', 'F', 'F') && head.length >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Kind.WEBP;
        }
        if (starts(head, '%', 'P', 'D', 'F')) {
            return Kind.PDF;
        }
        if (starts(head, 'P', 'K', 3, 4) || starts(head, 0xD0, 0xCF, 0x11, 0xE0)) {
            return Kind.OFFICE;
        }
        return Kind.UNSIGNED;
    }

    public static String mime(Kind kind) {
        return switch (kind) {
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
            case GIF -> "image/gif";
            case WEBP -> "image/webp";
            default -> throw new IllegalArgumentException(kind + " is not an image");
        };
    }

    public static boolean isImage(Kind kind) {
        return kind == Kind.PNG || kind == Kind.JPEG || kind == Kind.GIF || kind == Kind.WEBP;
    }

    private static boolean starts(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
