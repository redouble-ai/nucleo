/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.artifacts.*;

import java.nio.file.*;

/**
 * What a decision model reads of each artifact of this package, one line each, registered
 * as the types' text formatters: a decision thinker's state carries one digest line per
 * artifact and nothing else of it, so these lines are the whole of what the model decides on.
 * A folder reads as its own name; a file as its name, kind, size and first words; a document as its name, length and
 * first words; a statement as its source and its text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public final class Digests {
    /** How many characters of a file's or a document's first words a digest carries. */
    static final int HEAD_CHARS = 100;
    /** How many characters of a statement a digest carries. */
    static final int STATEMENT_CHARS = 150;

    private Digests() {}

    /** Registers the formatters; idempotent, so a thinker of this package registers them when its class loads. */
    public static void register() {
        TextFormatterRegistry.register(Folder.class, folder -> "the folder " + name(folder.getPath()));
        TextFormatterRegistry.register(FileEntry.class, file -> file.getName() + " (" + file.getKind() + ", " + size(file.getBytes()) + ")"
                + (file.getHead() != null && !file.getHead().isBlank() ? ": " + cut(file.getHead(), HEAD_CHARS) : ""));
        TextFormatterRegistry.register(Document.class, document -> document.getName() + " (" + document.getText().length() + " characters): "
                + cut(document.getText(), HEAD_CHARS));
        TextFormatterRegistry.register(Statement.class, statement -> statement.getSource() + " #" + statement.getOrdinal() + ": "
                + cut(statement.getText(), STATEMENT_CHARS));
    }

    /** A folder's own name: where it sits on disk tells the model nothing and costs it tokens every turn; the root has no name, so it reads as itself. */
    static String name(String path) {
        Path name = Path.of(path).getFileName();
        return name != null ? name.toString() : path;
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return (bytes + 512) / 1024 + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** The text on one line, cut with an ellipsis past the length. */
    static String cut(String text, int chars) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() > chars ? line.substring(0, chars) + "..." : line;
    }
}
