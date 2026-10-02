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
 * Checks that the code a page shows is the code that compiles. A fenced block preceded by
 * the directive {@code <!-- sample: path/To.java#region -->} is a checked sample: its body
 * must equal, line for line, the lines between {@code // region <name>} and
 * {@code // endregion} in that source file, the path relative to the markdown file. The
 * comparison ignores the region's common indentation and trailing whitespace; marker lines
 * of other regions nested inside are not part of the sample. The page carries the code
 * inline, so it reads whole in the tree, and the build proves it current: a missing file, a
 * missing region, a directive with no fence under it, or a fence that differs fails the
 * site with the region's text as it stands, ready to paste.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class Samples {
    private static final Pattern SAMPLE = Pattern.compile(
            "<!-- sample: ([^\\s#]+)#([A-Za-z0-9_-]+) -->\\n(```[a-z]*\\n(.*?)\\n```)?", Pattern.DOTALL);

    /** The problems of every checked sample in one page; empty when all match. */
    public static List<String> check(String markdown, Path page) throws IOException {
        List<String> problems = new ArrayList<>();
        Matcher matcher = SAMPLE.matcher(markdown);
        while (matcher.find()) {
            String where = page + ", sample " + matcher.group(1) + "#" + matcher.group(2);
            if (matcher.group(3) == null) {
                problems.add(where + ": the directive must be followed on the next line by the fenced sample");
                continue;
            }
            Path source = page.toAbsolutePath().getParent().resolve(matcher.group(1)).normalize();
            if (!Files.isRegularFile(source)) {
                problems.add(where + ": no such file: " + source);
                continue;
            }
            List<String> region = region(Files.readAllLines(source), matcher.group(2));
            if (region == null) {
                problems.add(where + ": " + source.getFileName() + " has no '// region " + matcher.group(2) + "' closed by '// endregion'");
                continue;
            }
            if (!normalized(Arrays.asList(matcher.group(4).split("\n", -1))).equals(region)) {
                problems.add(where + ": the fence differs from the source; the region reads:\n" + String.join("\n", region));
            }
        }
        return problems;
    }

    /** The region's lines without their common indentation and trailing whitespace; null when absent. */
    static List<String> region(List<String> lines, String name) {
        List<String> body = null;
        int depth = 0;
        for (String line : lines) {
            String marker = line.trim();
            if (body == null) {
                if (marker.equals("// region " + name)) {
                    body = new ArrayList<>();
                }
                continue;
            }
            if (marker.startsWith("// region ")) {
                depth++;
                continue;
            }
            if (marker.equals("// endregion")) {
                if (depth == 0) {
                    return normalized(body);
                }
                depth--;
                continue;
            }
            body.add(line);
        }
        return null;
    }

    private static List<String> normalized(List<String> lines) {
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.isBlank()) {
                indent = Math.min(indent, line.length() - line.stripLeading().length());
            }
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.add(line.isBlank() ? "" : line.substring(indent).stripTrailing());
        }
        return out;
    }
}
