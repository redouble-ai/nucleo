/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.ext.tables.*;
import com.vladsch.flexmark.util.ast.*;
import com.vladsch.flexmark.util.sequence.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * What the site's search box searches: one entry per place a reader can land on, written
 * as the page asset {@code search-index.js}. A page gives one entry for its opening, found
 * by the page's title, and one per heading, found by the heading and reached at the
 * heading's anchor; a build that ships the javadoc tree adds one entry per public class,
 * reached at the class's javadoc page. The text of an entry is what the reader sees under
 * that heading, code included, with the markdown syntax gone.
 *
 * The index is a script rather than a JSON file because a page opened from disk may load a
 * script and may not fetch a file. The page loads it, and the search library, the first
 * time the search box takes focus.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-01)
 */
public class SearchIndex {
    /** The part every class entry carries where a page's entry carries its part of the contents. */
    public static final String JAVADOC = "Javadoc";

    /**
     * One place a search lands on: the address relative to the site's root, the title of
     * the page or the name of the class, the part of the contents the page belongs to (null
     * for the front matter), the heading of the section (null for a page's opening and for a
     * class) and the text searched.
     */
    public record Entry(String url, String title, String part, String heading, String text) {}
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final List<Entry> entries = new ArrayList<>();

    /**
     * The entries of one page, from the document the page was rendered from: rendering is
     * what gives each heading its anchor, so the document must have been rendered already.
     */
    public void addPage(GenerateDocs.Page page, Document document) {
        String url = page.id() + ".html";
        String heading = null;
        StringBuilder text = new StringBuilder();
        for (Node block : document.getChildren()) {
            if (block instanceof Heading next) {
                entries.add(new Entry(url, page.title(), page.part(), heading, plain(text.toString())));
                url = page.id() + ".html#" + next.getAnchorRefId();
                text.setLength(0);
                collect(next, text);
                heading = plain(text.toString());
                text.setLength(0);
            } else {
                collect(block, text);
            }
        }
        entries.add(new Entry(url, page.title(), page.part(), heading, plain(text.toString())));
    }

    /**
     * The text under a node as its reader sees it: the words of prose and of code spans as
     * written, a link as its text, a code block as its code, a space where one block or
     * table cell ends and where a line breaks. Raw HTML, which is where the sample
     * directives live, and the rule under a table's head are no text.
     */
    private static void collect(Node node, StringBuilder text) {
        switch (node) {
            case HtmlBlockBase _, HtmlInlineBase _, TableSeparator _ -> {}
            case Text chars -> text.append(chars.getChars());
            case HtmlEntity entity -> text.append(Escaping.unescapeHtml(entity.getChars()));
            case AutoLink link -> text.append(link.getText());
            case SoftLineBreak _, HardLineBreak _ -> text.append(' ');
            case FencedCodeBlock code -> text.append(code.getContentChars()).append(' ');
            case IndentedCodeBlock code -> text.append(code.getContentChars()).append(' ');
            default -> {
                for (Node child : node.getChildren()) {
                    collect(child, text);
                }
                if (node instanceof Block || node instanceof TableCell) {
                    text.append(' ');
                }
            }
        }
    }

    /** One entry per class of the javadoc class index, found by the class's name and its package. */
    public void addClasses(Map<String, String> classIndex) {
        for (Map.Entry<String, String> entry : new TreeMap<>(classIndex).entrySet()) {
            String path = entry.getValue();
            String qualifiedName = path.substring(0, path.length() - ".html".length()).replace('/', '.');
            entries.add(new Entry("javadoc/" + path, entry.getKey(), JAVADOC, null, qualifiedName));
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Writes {@code search-index.js}, the entries as the array {@code SEARCH_INDEX}. */
    public void write(Path docsDir) throws IOException {
        StringBuilder js = new StringBuilder("const SEARCH_INDEX = [\n");
        for (Entry entry : entries) {
            js.append("    {\"url\": ").append(literal(entry.url()))
                    .append(", \"title\": ").append(literal(entry.title()))
                    .append(", \"part\": ").append(literal(entry.part()))
                    .append(", \"heading\": ").append(literal(entry.heading()))
                    .append(", \"text\": ").append(literal(entry.text())).append("},\n");
        }
        js.append("];\n");
        Files.writeString(docsDir.resolve("search-index.js"), js.toString());
    }

    private static String plain(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /** A JavaScript literal: null for null, otherwise the string quoted. */
    static String literal(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
