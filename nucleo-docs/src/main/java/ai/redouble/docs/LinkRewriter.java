/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Rewrites links in a page's markdown before conversion.
 *
 * Two passes. Inter-doc links: every markdown link whose target is a .md file is resolved
 * relative to the source file's directory and looked up in the page manifest; resolvable
 * targets are rewritten to the generated page, preserving any #anchor. A link whose TEXT
 * is itself a path (the markdown idiom {@code [../PACKAGE.md](../PACKAGE.md)}, right for
 * reading in the tree) gets the resolved page's title as its text - a reader of the site
 * should see the package's name, never a relative path. A target that does not resolve by
 * path falls back to a unique-basename match across the manifest; if that fails too the
 * link is left untouched, and a warning goes to stderr when it names no file at all. External
 * (scheme-qualified) targets are never touched, and neither is a link that starts inside code.
 * File links: a link to a file of the reactor that is not a page - a source file, a plain
 * document, a markdown file the contents do not list - leads to a copy of the file the site
 * ships as text. Javadoc links: backtick-wrapped class names ({@code `Foo`},
 * {@code `Foo<T>`}, {@code `@Foo`}), member references ({@code `Foo.bar(...)`},
 * {@code `Foo#bar`}, {@code `Foo.CONSTANT`} - to the class page, since prose never
 * carries the parameter types an exact method anchor needs) and package names
 * ({@code `ai.redouble.nucleo.harness`} - to the package summary) that match the index
 * become links into the generated javadoc tree. Backticks are the author's "this is code"
 * signal and the only text these passes touch; prose mentions stay prose.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class LinkRewriter {
    private static final Pattern DOC_LINK = Pattern.compile("\\[([^\\[\\]]*)\\]\\(([^)\\s#]+\\.md)(#[^)]*)?\\)");
    private static final Pattern IMAGE_LINK = Pattern.compile("\\]\\(([^)\\s#]+\\.(?:svg|png|jpe?g|gif))\\)");
    private static final Pattern FILE_LINK = Pattern.compile("\\[([^\\[\\]]*)\\]\\(([^)\\s#]+)(#[^)]*)?\\)");
    private static final Pattern IMAGE_NAME = Pattern.compile(".*\\.(?:svg|png|jpe?g|gif)");
    private final Path root;
    private final Map<Path, String> pathToId = new HashMap<>();
    private final Map<String, List<String>> basenameToIds = new HashMap<>();
    private final Map<String, String> idToTitle = new HashMap<>();
    private final Map<String, String> classIndex;
    private final Map<String, Path> shipped = new TreeMap<>();

    public LinkRewriter(Path root, List<GenerateDocs.Page> pages, Map<String, String> classIndex) {
        this.root = root.toAbsolutePath().normalize();
        this.classIndex = classIndex;
        for (GenerateDocs.Page page : pages) {
            Path source = page.source().toAbsolutePath().normalize();
            pathToId.put(source, page.id());
            basenameToIds.computeIfAbsent(source.getFileName().toString(), k -> new ArrayList<>()).add(page.id());
            idToTitle.put(page.id(), page.title());
        }
    }

    /**
     * A link that starts inside code - a fenced block or an inline code span - is code, shown
     * as written; a link whose text is code ({@code [`Foo`](x.md)}) starts in prose and is
     * rewritten.
     */
    public String rewriteDocLinks(String content, Path originalMd) {
        Path baseDir = originalMd.toAbsolutePath().getParent();
        boolean[] code = codeMask(content);
        Matcher matcher = DOC_LINK.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String text = matcher.group(1);
            String target = matcher.group(2);
            String anchor = matcher.group(3) == null ? "" : matcher.group(3);
            if (target.contains("://") || code[matcher.start()]) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String pageId = pathToId.get(baseDir.resolve(target).normalize());
            if (pageId == null) {
                List<String> candidates = basenameToIds.getOrDefault(fileName(target), List.of());
                if (candidates.size() == 1) {
                    pageId = candidates.get(0);
                }
            }
            if (pageId == null) {
                // A markdown file of the tree that is not a page is rewriteFileLinks' to ship; only a link to nothing is the author's to fix
                if (!Files.isRegularFile(baseDir.resolve(target).normalize())) {
                    System.err.println("WARNING: unresolved doc link '" + target + "' in " + originalMd);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            // Path-shaped text reads right in the tree, wrong on the site: name the page instead
            if (text.replace("`", "").endsWith(".md")) {
                text = idToTitle.get(pageId);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement("[" + text + "](" + pageId + ".html" + anchor + ")"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * A link to a file of the reactor that is not a page - a source file, a plain document
     * like a license, a markdown file the contents do not list - leads to a copy the site
     * ships ({@link #ship}), so a reader of the site can open what a reader of the tree can.
     * Runs after {@link #rewriteDocLinks}: a link to a page points at the generated page by
     * then, which is no file of the tree. Images are {@link #rewriteImageLinks}'s, a link
     * that starts inside code stays as written, and so does a link to no file at all.
     */
    public String rewriteFileLinks(String content, Path originalMd) {
        Path baseDir = originalMd.toAbsolutePath().getParent();
        boolean[] code = codeMask(content);
        Matcher matcher = FILE_LINK.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(2);
            if (target.contains(":") || code[matcher.start()] || IMAGE_NAME.matcher(target).matches()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            Path file = baseDir.resolve(target).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement("[" + matcher.group(1) + "](" + ship(file) + ")"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Has the site carry a copy of a file of the reactor and answers the copy's name: the
     * file's own name with {@code .txt} appended, so a browser shows it as text whatever its
     * extension says. The copies sit flat beside the pages, so two different files with one
     * name are refused.
     */
    public String ship(Path file) {
        file = file.toAbsolutePath().normalize();
        String name = file.getFileName() + ".txt";
        Path earlier = shipped.putIfAbsent(name, file);
        if (earlier != null && !earlier.equals(file)) {
            throw new IllegalStateException("the site ships linked files flat, by name, and two of them are named " + file.getFileName() + ": " + earlier + " and " + file);
        }
        return name;
    }

    /** The files the site ships, by the name each copy carries. */
    public Map<String, Path> shipped() {return shipped;}

    /**
     * Which characters of a markdown text are code: every character of a fenced block (from
     * a line opening with three backticks to the next such line) and of an inline code span
     * (a backtick to the next backtick on the same line).
     */
    static boolean[] codeMask(String content) {
        boolean[] code = new boolean[content.length() + 1];
        boolean fenced = false;
        int lineStart = 0;
        while (lineStart < content.length()) {
            int lineEnd = content.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? content.length() : lineEnd + 1;
            String line = content.substring(lineStart, lineEnd);
            boolean fenceLine = line.stripLeading().startsWith("```");
            if (fenced || fenceLine) {
                Arrays.fill(code, lineStart, lineEnd, true);
                if (fenceLine) {
                    fenced = !fenced;
                }
            } else {
                int open = line.indexOf('`');
                while (open >= 0) {
                    int close = line.indexOf('`', open + 1);
                    if (close < 0) {
                        break;
                    }
                    Arrays.fill(code, lineStart + open, lineStart + close + 1, true);
                    open = line.indexOf('`', close + 1);
                }
            }
            lineStart = lineEnd;
        }
        return code;
    }

    /**
     * Images are copied into the site flat, by basename; a reference that reaches its
     * file relative to the markdown source (however many directories up) is rewritten to
     * that basename. A reference to a file that does not exist stays as written and warns.
     */
    public String rewriteImageLinks(String content, Path originalMd) {
        Path baseDir = originalMd.toAbsolutePath().getParent();
        Matcher matcher = IMAGE_LINK.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(1);
            if (target.contains("://") || !Files.isRegularFile(baseDir.resolve(target).normalize())) {
                if (!target.contains("://")) {
                    System.err.println("WARNING: image '" + target + "' in " + originalMd + " does not exist");
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String basename = fileName(target);
            matcher.appendReplacement(out, Matcher.quoteReplacement("](" + basename + ")"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public String rewriteJavadocLinks(String content) {
        for (Map.Entry<String, String> entry : classIndex.entrySet()) {
            String className = entry.getKey();
            String link = "](javadoc/" + entry.getValue() + ")";
            content = content.replace("`" + className + "`", "[" + className + link);
            content = content.replace("`@" + className + "`", "[@" + className + link);
            // `Foo<T>` and member references: `Foo.bar(...)`, `Foo#bar`, `Foo.CONSTANT`
            Pattern qualified = Pattern.compile(
                    "`" + Pattern.quote(className) + "(<[^>]+>|[.#][A-Za-z][A-Za-z0-9_]*(?:\\([^`]*?\\))?)`");
            Matcher matcher = qualified.matcher(content);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String withoutBackticks = matcher.group().substring(1, matcher.group().length() - 1);
                matcher.appendReplacement(out, Matcher.quoteReplacement("[" + withoutBackticks + link));
            }
            matcher.appendTail(out);
            content = out.toString();
        }
        for (String packageName : packageNames()) {
            content = content.replace("`" + packageName + "`",
                    "[" + packageName + "](javadoc/" + packageName.replace('.', '/') + "/package-summary.html)");
        }
        return content;
    }

    /** The packages of the indexed classes; only they have a summary page to link. */
    private Set<String> packageNames() {
        Set<String> packages = new TreeSet<>();
        for (String path : classIndex.values()) {
            packages.add(path.substring(0, path.lastIndexOf('/')).replace('/', '.'));
        }
        return packages;
    }

    private static String fileName(String target) {
        int slash = target.lastIndexOf('/');
        return slash < 0 ? target : target.substring(slash + 1);
    }
}
