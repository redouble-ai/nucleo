/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * The site's reading order, parsed from {@code nucleo-docs/CONTENTS.md}: the one place that
 * says which documents the site carries, what each is called, and in which order a reader
 * meets them.
 *
 * The file is plain markdown, readable in the tree. Its H1 is the site's title; the
 * paragraph under it is the site's introduction; the list before the first {@code ##}
 * heading is the front matter; every {@code ##} heading opens a part, whose paragraph is the
 * part's introduction and whose list is its pages, in reading order. A list item is exactly
 * {@code - [Title](relative/path.md)}, the title being what the site calls the page; an item
 * indented by two spaces nests under the item above it. Anything else in a list is refused,
 * and so is a link to a file that does not exist or a file listed twice.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class Contents {
    public record Entry(String title, Path source, int depth) {}
    public record Part(String title, String intro, List<Entry> entries) {}
    private static final Pattern ITEM = Pattern.compile("^( *)- \\[([^\\]]+)\\]\\(([^)\\s]+\\.md)\\)\\s*$");
    private final Path file;
    private final String title;
    private final String intro;
    private final List<Entry> frontMatter;
    private final List<Part> parts;

    private Contents(Path file, String title, String intro, List<Entry> frontMatter, List<Part> parts) {
        this.file = file;
        this.title = title;
        this.intro = intro;
        this.frontMatter = frontMatter;
        this.parts = parts;
    }

    public static Contents read(Path file) throws IOException {
        Path base = file.toAbsolutePath().normalize().getParent();
        List<String> lines = Files.readAllLines(file);
        String title = null;
        StringBuilder siteIntro = new StringBuilder();
        List<Entry> frontMatter = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        String partTitle = null;
        StringBuilder partIntro = new StringBuilder();
        List<Entry> partEntries = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            String where = file + ":" + lineNumber;
            if (line.startsWith("# ")) {
                if (title != null) {
                    throw new IllegalStateException(where + ": a second H1; the contents carry one title");
                }
                title = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                if (partTitle != null) {
                    parts.add(part(partTitle, partIntro, partEntries, where));
                }
                partTitle = line.substring(3).trim();
                partIntro = new StringBuilder();
                partEntries = new ArrayList<>();
            } else if (line.stripLeading().startsWith("- ")) {
                Matcher item = ITEM.matcher(line);
                if (!item.matches()) {
                    throw new IllegalStateException(where + ": a list item is exactly '- [Title](relative/path.md)', optionally indented by two spaces: " + line);
                }
                int indent = item.group(1).length();
                if (indent != 0 && indent != 2) {
                    throw new IllegalStateException(where + ": an item nests by exactly two spaces: " + line);
                }
                List<Entry> entries = partTitle == null ? frontMatter : partEntries;
                if (indent == 2 && entries.isEmpty()) {
                    throw new IllegalStateException(where + ": a nested item needs an item above it to nest under: " + line);
                }
                Path source = base.resolve(item.group(3)).normalize();
                if (!Files.isRegularFile(source)) {
                    throw new IllegalStateException(where + ": no such file: " + item.group(3));
                }
                if (!seen.add(source)) {
                    throw new IllegalStateException(where + ": listed twice: " + item.group(3));
                }
                entries.add(new Entry(item.group(2), source, indent / 2));
            } else if (!line.isBlank()) {
                StringBuilder paragraph = partTitle == null ? siteIntro : partIntro;
                paragraph.append(paragraph.isEmpty() ? "" : " ").append(line.trim());
            }
        }
        if (title == null) {
            throw new IllegalStateException(file + ": no H1; the contents' H1 is the site's title");
        }
        if (partTitle != null) {
            parts.add(part(partTitle, partIntro, partEntries, file + ":" + lineNumber));
        }
        return new Contents(file.toAbsolutePath().normalize(), title, siteIntro.toString(), frontMatter, parts);
    }

    private static Part part(String title, StringBuilder intro, List<Entry> entries, String where) {
        if (entries.isEmpty()) {
            throw new IllegalStateException(where + ": part '" + title + "' lists no page");
        }
        return new Part(title, intro.toString(), List.copyOf(entries));
    }

    public Path file() {return file;}
    public String title() {return title;}
    public String intro() {return intro;}
    public List<Entry> frontMatter() {return frontMatter;}
    public List<Part> parts() {return parts;}
}
