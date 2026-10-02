/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import com.vladsch.flexmark.ext.tables.*;
import com.vladsch.flexmark.html.*;
import com.vladsch.flexmark.parser.*;
import com.vladsch.flexmark.util.ast.*;
import com.vladsch.flexmark.util.data.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Generates the HTML documentation site from the markdown files in the reactor's source
 * trees. Runs at the site phase (after the aggregate javadoc); the whole pipeline is
 * Maven plus a JDK, so it runs the same on any contributor's machine.
 *
 * Pipeline:
 * 1. Read the reading order, {@code nucleo-docs/CONTENTS.md} ({@link Contents}): the pages
 *    the site carries, their titles, their parts, their order. Every PACKAGE.md under any
 *    module's src/main/java must be listed there, so no page is left out of the story.
 * 2. Build a class index over all .java files for javadoc linking. The javadoc tree is
 *    produced by the aggregate goal bound to pre-site, so one tree covers every module.
 * 3. Per page, in reading order: {@link Samples} checks every checked sample against its
 *    source region, the source's own H1 gives way to the page frame, {@link LinkRewriter}
 *    resolves inter-doc .md links, points a link to any other file of the reactor at a copy
 *    the site ships, and links backticked class names to javadoc, and flexmark converts the
 *    body to HTML.
 * 4. Wrap each body in the shared template: the search box in the titlebar, the part and
 *    title above the body, the previous and next pages of the reading order and the
 *    copyright and license footer below it, the sidebar {@link NavGenerator} renders once
 *    from the contents beside it. The reactor's LICENSE and NOTICE ride along as
 *    {@code LICENSE.txt} and {@code NOTICE.txt}, which the footer links, and so does every
 *    file a page links that is not a page itself, each under its own name with .txt appended.
 * 5. Write {@code llms.txt} and {@code llms-full.txt}, the contents and the whole site as
 *    markdown for a coding agent, and {@code search-index.js}, what the search box searches
 *    ({@link SearchIndex}), and verify: every page appears in the nav, no .md href is left,
 *    every local image resolves, every anchor the search index lands on exists.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class GenerateDocs {
    /**
     * One page of the site: its id (the file name without .html), its title from the
     * contents, its markdown source, the part it belongs to as the breadcrumb reads it (null
     * for the front matter) and the package and module the source documents (null for a
     * document outside every module's source tree).
     */
    public record Page(String id, String title, Path source, String part, String origin) {}
    private static final Pattern LEFTOVER_MD_HREF = Pattern.compile("href=\"[^\"]*\\.md(#[^\"]*)?\"");
    private static final Pattern LOCAL_SRC = Pattern.compile("src=\"([^\"]+)\"");
    private static final Pattern EXAMPLE_CALLOUT = Pattern.compile("<blockquote>\\s*<p><strong>Example");

    /**
     * Arguments: the reactor root and the version the site documents, then optionally
     * {@code --out <dir>} for a different output directory (the demo build embeds the site
     * into its jar this way) and {@code --no-javadoc-links} for a build that does not ship
     * the javadoc tree - backticked class names then stay code chips instead of becoming
     * dead links - and {@code --no-versions} for a build that is not published beside other
     * versions - its version badge then names no list of versions and asks for none.
     */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String version = args[1];
        Path docsDir = root.resolve("src/main/docs");
        boolean javadocLinks = true;
        boolean versionsBeside = true;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--out")) {
                i++;
                docsDir = Path.of(args[i]).toAbsolutePath().normalize();
            } else if (args[i].equals("--no-javadoc-links")) {
                javadocLinks = false;
            } else if (args[i].equals("--no-versions")) {
                versionsBeside = false;
            } else {
                throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        generate(root, docsDir, version, javadocLinks, versionsBeside);
        System.out.println();
        System.out.println("HTML documentation generated successfully.");
        System.out.println("Open " + docsDir.resolve("index.html") + " in your browser to view.");
    }

    /**
     * The whole site from the reactor at {@code root} into {@code docsDir}, every page
     * carrying {@code version}; with links into the javadoc tree when the site ships one,
     * and with the version badge offering the other versions when the site is published
     * beside them.
     */
    public static void generate(Path root, Path docsDir, String version, boolean javadocLinks, boolean versionsBeside) throws IOException {
        root = root.toAbsolutePath().normalize();
        docsDir = docsDir.toAbsolutePath().normalize();
        Files.createDirectories(docsDir);
        System.out.println("Reading the contents and the module source trees...");
        System.out.println("Reactor: " + root);
        System.out.println("Output:  " + docsDir);
        // Remove old generated files; the javadoc subdirectory is the javadoc plugin's
        cleanGenerated(docsDir);
        copyResource("/style.css", docsDir.resolve("style.css"));
        // The brand lockup in every titlebar and the favicon, both linking the pages to Redouble
        copyResource("/redoubleAI.svg", docsDir.resolve("redoubleAI.svg"));
        copyResource("/redoubleAI.png", docsDir.resolve("redoubleAI.png"));
        // Every page's footer names the copyright holder the notice names
        String copyright = copyright(root.resolve("NOTICE"));
        List<Path> srcRoots = sourceRoots(root);
        copySvgs(srcRoots, docsDir);
        Map<String, String> classIndex = Map.of();
        if (javadocLinks) {
            classIndex = buildClassIndex(srcRoots);
            System.out.println("Indexed " + classIndex.size() + " classes for javadoc links");
            writeClassIndexJs(classIndex, docsDir);
        }
        Contents contents = Contents.read(root.resolve("nucleo-docs/CONTENTS.md"));
        List<Page> pages = discoverPages(root, srcRoots, contents);
        System.out.println("Found " + pages.size() + " documentation files in " + contents.parts().size() + " parts");
        Map<Path, Page> bySource = new HashMap<>();
        for (Page page : pages) {
            bySource.put(page.source(), page);
        }
        String nav = NavGenerator.generate(contents, bySource, version, versionsBeside);
        LinkRewriter rewriter = new LinkRewriter(root, pages, classIndex);
        // The site is a copy of the work, so it carries the work's license and notice whether
        // or not a page links them; the footer of every page does
        rewriter.ship(root.resolve("LICENSE"));
        rewriter.ship(root.resolve("NOTICE"));
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
        options.set(HtmlRenderer.RENDER_HEADER_ID, true);
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        List<String> sampleProblems = new ArrayList<>();
        SearchIndex search = new SearchIndex();
        StringBuilder full = new StringBuilder("# " + contents.title() + "\n\n> " + contents.intro() + "\n");
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            String markdown = readUtf8(page.source());
            sampleProblems.addAll(Samples.check(markdown, page.source()));
            markdown = withoutTitle(markdown, page.source());
            markdown = rewriter.rewriteDocLinks(markdown, page.source());
            markdown = rewriter.rewriteFileLinks(markdown, page.source());
            markdown = rewriter.rewriteImageLinks(markdown, page.source());
            full.append("\n---\n\n# ").append(page.title()).append("\n\n");
            if (page.part() != null) {
                full.append(page.part()).append("\n\n");
            }
            full.append(markdown.strip()).append("\n");
            markdown = rewriter.rewriteJavadocLinks(markdown);
            Document document = parser.parse(markdown);
            String body = EXAMPLE_CALLOUT.matcher(renderer.render(document))
                    .replaceAll("<blockquote class=\"example\">\n<p><strong>Example");
            search.addPage(page, document);
            Page previous = i > 0 ? pages.get(i - 1) : null;
            Page next = i + 1 < pages.size() ? pages.get(i + 1) : null;
            Path out = docsDir.resolve(page.id() + ".html");
            Files.writeString(out, wrap(page, nav, body, previous, next, copyright, javadocLinks));
            System.out.println("Generated " + page.id() + ".html from " + page.source().getFileName());
        }
        if (!sampleProblems.isEmpty()) {
            throw new IllegalStateException("checked samples out of date:\n" + String.join("\n\n", sampleProblems));
        }
        for (Map.Entry<String, Path> file : rewriter.shipped().entrySet()) {
            Files.copy(file.getValue(), docsDir.resolve(file.getKey()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(docsDir.resolve("llms.txt"), llmsIndex(contents, bySource));
        Files.writeString(docsDir.resolve("llms-full.txt"), full.toString());
        search.addClasses(classIndex);
        search.write(docsDir);
        verify(docsDir, pages, nav, search);
    }

    /**
     * The copyright line of the reactor's NOTICE, the line that opens with "Copyright"; a
     * NOTICE without one is refused, since every page's footer states it.
     */
    static String copyright(Path notice) throws IOException {
        for (String line : readUtf8(notice).split("\n")) {
            if (line.startsWith("Copyright ")) {
                return line.strip();
            }
        }
        throw new IllegalStateException(notice + " must carry a line that opens with 'Copyright ', which the footer of every page states");
    }

    /**
     * The source's own first line is its H1, which the page frame replaces with the title
     * from the contents; a source that opens any other way is refused.
     */
    static String withoutTitle(String markdown, Path source) {
        String stripped = markdown.stripLeading();
        if (!stripped.startsWith("# ")) {
            throw new IllegalStateException(source + " must open with its H1");
        }
        int end = stripped.indexOf('\n');
        return end < 0 ? "" : stripped.substring(end + 1);
    }

    /**
     * The contents for a coding agent, in the llms.txt shape: the title, the introduction as
     * a quote, the front matter, then a section per part with its introduction and its pages.
     */
    static String llmsIndex(Contents contents, Map<Path, Page> pages) {
        StringBuilder out = new StringBuilder("# " + contents.title() + "\n\n> " + contents.intro() + "\n\n");
        for (Contents.Entry entry : contents.frontMatter()) {
            out.append(llmsItem(entry, pages));
        }
        for (Contents.Part part : contents.parts()) {
            out.append("\n## ").append(part.title()).append("\n\n");
            if (!part.intro().isEmpty()) {
                out.append(part.intro()).append("\n\n");
            }
            for (Contents.Entry entry : part.entries()) {
                out.append(llmsItem(entry, pages));
            }
        }
        return out.toString();
    }

    private static String llmsItem(Contents.Entry entry, Map<Path, Page> pages) {
        return "  ".repeat(entry.depth()) + "- [" + entry.title() + "](" + pages.get(entry.source()).id() + ".html)\n";
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** One source root per module; modules without java sources drop out here. */
    private static List<Path> sourceRoots(Path root) throws IOException {
        List<Path> roots = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(root)) {
            for (Path module : modules) {
                Path src = module.resolve("src/main/java");
                if (Files.isDirectory(src)) {
                    roots.add(src);
                }
            }
        }
        Collections.sort(roots);
        return roots;
    }

    private static void cleanGenerated(Path docsDir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(docsDir, "*.{html,css,svg,js,png,txt}")) {
            for (Path entry : entries) {
                Files.delete(entry);
            }
        }
    }

    /**
     * The class index as a page asset: the template's script links class names inside
     * highlighted code blocks through it, after highlight.js has tokenized them. Only a
     * build that ships the javadoc tree writes it.
     */
    private static void writeClassIndexJs(Map<String, String> classIndex, Path docsDir) throws IOException {
        StringBuilder js = new StringBuilder("const JAVADOC_CLASS_INDEX = {\n");
        for (Map.Entry<String, String> entry : new TreeMap<>(classIndex).entrySet()) {
            js.append("    \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\",\n");
        }
        js.append("};\n");
        Files.writeString(docsDir.resolve("class-index.js"), js.toString());
    }

    static void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = GenerateDocs.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new FileNotFoundException("classpath resource " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Diagrams referenced from the markdown ride along by basename, as the pages link them. */
    private static void copySvgs(List<Path> srcRoots, Path docsDir) throws IOException {
        for (Path srcRoot : srcRoots) {
            try (var files = Files.walk(srcRoot)) {
                for (Path svg : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".svg"))::iterator) {
                    Files.copy(svg, docsDir.resolve(svg.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Class name to javadoc path, public top-level types only - javadoc does not generate
     * pages for package-private classes, so linking them would produce dead links.
     */
    private static Map<String, String> buildClassIndex(List<Path> srcRoots) throws IOException {
        Map<String, String> index = new HashMap<>();
        for (Path srcRoot : srcRoots) {
            try (var files = Files.walk(srcRoot)) {
                for (Path java : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String name = java.getFileName().toString();
                    String className = name.substring(0, name.length() - ".java".length());
                    String packageName = null;
                    boolean isPublicTopLevel = false;
                    Pattern declaration = Pattern.compile(
                            "^public .*(class|interface|enum|record) +" + Pattern.quote(className) + "([^A-Za-z0-9_]|$)");
                    for (String line : readUtf8(java).split("\n")) {
                        if (packageName == null && line.startsWith("package ")) {
                            packageName = line.substring("package ".length()).replace(";", "").trim();
                        }
                        if (declaration.matcher(line).find()) {
                            isPublicTopLevel = true;
                            break;
                        }
                    }
                    if (packageName != null && isPublicTopLevel) {
                        index.put(className, packageName.replace('.', '/') + "/" + className + ".html");
                    }
                }
            }
        }
        return index;
    }

    /**
     * The page manifest, in reading order, drives conversion, link resolution, the nav and
     * the previous and next links, so none of them can drift from the contents. Every
     * PACKAGE.md under a module's source tree must be listed; one that is not fails the
     * build, naming it and the contents file.
     */
    private static List<Page> discoverPages(Path root, List<Path> srcRoots, Contents contents) throws IOException {
        List<Page> pages = new ArrayList<>();
        for (Contents.Entry entry : contents.frontMatter()) {
            pages.add(new Page(idFor(root, srcRoots, entry.source()), entry.title(), entry.source(), null, originFor(srcRoots, entry.source())));
        }
        int number = 0;
        for (Contents.Part part : contents.parts()) {
            number++;
            String crumb = "Part " + number + " · " + part.title();
            for (Contents.Entry entry : part.entries()) {
                pages.add(new Page(idFor(root, srcRoots, entry.source()), entry.title(), entry.source(), crumb, originFor(srcRoots, entry.source())));
            }
        }
        Set<Path> listed = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Page page : pages) {
            listed.add(page.source());
            if (!seen.add(page.id())) {
                throw new IllegalStateException("two pages share the id " + page.id() + "; rename one of their sources");
            }
        }
        if (!seen.contains("index")) {
            throw new IllegalStateException(contents.file() + " must list the reactor's README.md, the site's index page");
        }
        List<String> unlisted = new ArrayList<>();
        for (Path srcRoot : srcRoots) {
            try (var files = Files.walk(srcRoot)) {
                files.filter(p -> p.getFileName().toString().equals("PACKAGE.md"))
                        .map(p -> p.toAbsolutePath().normalize())
                        .filter(p -> !listed.contains(p))
                        .map(p -> root.relativize(p).toString())
                        .sorted()
                        .forEach(unlisted::add);
            }
        }
        if (!unlisted.isEmpty()) {
            throw new IllegalStateException("every PACKAGE.md has a place in " + contents.file() + "; not listed there: " + String.join(", ", unlisted));
        }
        return pages;
    }

    /**
     * A page's id: {@code index} for the reactor's README.md; for a PACKAGE.md, the package
     * path relative to the module's src/main/java with the ai/redouble/nucleo prefix
     * stripped (ai/redouble for packages outside Nucleo, like the demo) and slashes turned
     * into dashes, so ai/redouble/nucleo/harness/admission becomes harness-admission; for
     * any other document, its file name lowercased, without .md, underscores turned into
     * dashes (PROGRESS_GUIDELINES.md becomes progress-guidelines).
     */
    static String idFor(Path root, List<Path> srcRoots, Path source) {
        if (source.equals(root.toAbsolutePath().normalize().resolve("README.md"))) {
            return "index";
        }
        String name = source.getFileName().toString();
        if (!name.equals("PACKAGE.md")) {
            return name.substring(0, name.length() - ".md".length()).toLowerCase(Locale.ROOT).replace('_', '-');
        }
        for (Path srcRoot : srcRoots) {
            if (source.startsWith(srcRoot)) {
                String packageDir = srcRoot.relativize(source.getParent()).toString().replace('\\', '/');
                String id = packageDir.startsWith("ai/redouble/nucleo/")
                        ? packageDir.substring("ai/redouble/nucleo/".length())
                        : packageDir.substring("ai/redouble/".length());
                return id.replace('/', '-');
            }
        }
        throw new IllegalStateException(source + " is a PACKAGE.md outside every module's src/main/java");
    }

    /** The package and module a source under a module's src/main/java documents, as the page frame shows them. */
    static String originFor(List<Path> srcRoots, Path source) {
        for (Path srcRoot : srcRoots) {
            if (source.startsWith(srcRoot)) {
                String packageName = srcRoot.relativize(source.getParent()).toString().replace('\\', '/').replace('/', '.');
                String module = srcRoot.getParent().getParent().getParent().getFileName().toString();
                return packageName + " · " + module;
            }
        }
        return null;
    }

    /**
     * Nav coverage is guaranteed by NavGenerator's design; this assertion keeps the
     * invariant honest if that class ever changes. The same goes for the search index:
     * every anchor it sends a reader to must be an id its page carries.
     */
    private static void verify(Path docsDir, List<Page> pages, String nav, SearchIndex search) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Page page : pages) {
            if (!Files.isRegularFile(docsDir.resolve(page.id() + ".html"))) {
                problems.add(page.id() + ".html was not generated");
            }
            if (!nav.contains("href=\"" + page.id() + ".html\"")) {
                problems.add(page.id() + ".html is not linked from the navigation");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
        Map<String, String> htmlByFile = new HashMap<>();
        for (Page page : pages) {
            String html = readUtf8(docsDir.resolve(page.id() + ".html"));
            htmlByFile.put(page.id() + ".html", html);
            if (LEFTOVER_MD_HREF.matcher(html).find()) {
                System.err.println("WARNING: " + page.id() + ".html carries unresolved .md links");
            }
            // Every local src (images) must point at a file inside the site
            Matcher src = LOCAL_SRC.matcher(html);
            while (src.find()) {
                String target = src.group(1);
                if (target.startsWith("http:") || target.startsWith("https:") || target.startsWith("data:")) {
                    continue;
                }
                Path resolved = docsDir.resolve(target).normalize();
                if (!resolved.startsWith(docsDir) || !Files.isRegularFile(resolved)) {
                    problems.add(page.id() + ".html references missing local file: " + target);
                }
            }
        }
        for (SearchIndex.Entry entry : search.entries()) {
            int hash = entry.url().indexOf('#');
            if (hash >= 0 && !htmlByFile.get(entry.url().substring(0, hash)).contains("id=\"" + entry.url().substring(hash + 1) + "\"")) {
                problems.add("the search index points at " + entry.url() + ", an anchor that page does not carry");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    /** Malformed bytes are replaced rather than fatal; a stray encoding must not kill the site build. */
    static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * The page frame around a rendered body: the part as a breadcrumb, the title, and the
     * package and module the page documents above it; the previous and next pages of the
     * reading order below it, then the footer: the copyright line and the license the
     * site ships as LICENSE.txt and NOTICE.txt.
     */
    private static String wrap(Page page, String nav, String body, Page previous, Page next, String copyright, boolean javadocLinks) {
        String classIndexScript = javadocLinks ? "<script src=\"class-index.js\"></script>" : "";
        StringBuilder content = new StringBuilder("<header class=\"page-head\">\n");
        if (page.part() != null) {
            content.append("<div class=\"crumb\">").append(escape(page.part())).append("</div>\n");
        }
        content.append("<h1>").append(escape(page.title())).append("</h1>\n");
        if (page.origin() != null) {
            content.append("<div class=\"origin\">").append(escape(page.origin())).append("</div>\n");
        }
        content.append("</header>\n").append(body).append("<div class=\"pager\">\n");
        if (previous != null) {
            content.append("<a class=\"prev\" href=\"").append(previous.id()).append(".html\"><span class=\"dir\">Previous</span><span class=\"to\">")
                    .append(escape(previous.title())).append("</span></a>\n");
        }
        if (next != null) {
            content.append("<a class=\"next\" href=\"").append(next.id()).append(".html\"><span class=\"dir\">Next</span><span class=\"to\">")
                    .append(escape(next.title())).append("</span></a>\n");
        }
        content.append("</div>\n");
        content.append("<footer class=\"page-foot\">").append(escape(copyright))
                .append(". Licensed under the <a href=\"LICENSE.txt\">Apache License, Version 2.0</a>; see the <a href=\"NOTICE.txt\">notice</a>.</footer>\n");
        String title = escape(page.title()) + " - Nucleo";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <link rel="icon" href="redoubleAI.png">
                    <script>
                        // Before first paint: a stored theme choice must win over the system preference without a flash.
                        (function() { var t = localStorage.getItem('nucleo-theme'); if (t) document.documentElement.dataset.theme = t; })();
                    </script>
                    <link rel="stylesheet" href="style.css">
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js"></script>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/bash.min.js"></script>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/json.min.js"></script>
                    %s
                </head>
                <body>
                    <div class="titlebar"><a class="brand" href="https://redouble.ai" aria-label="Redouble AI"><img src="redoubleAI.svg" alt="Redouble AI" height="20"></a> <span class="app">Nucleo</span> <span class="dim">documentation</span>
                        <div class="search" id="search">
                            <input type="search" id="search-box" placeholder="Search" aria-label="Search the documentation" autocomplete="off" spellcheck="false">
                            <kbd>/</kbd>
                            <div class="search-results" id="search-results" hidden></div>
                        </div>
                        <div class="theme-switch" id="theme-switch">
                            <button type="button" data-mode="system">System</button>
                            <button type="button" data-mode="light">Light</button>
                            <button type="button" data-mode="dark">Dark</button>
                        </div>
                    </div>
                    <div class="container">
                        %s
                        <main>
                            %s
                        </main>
                    </div>
                    <a href="#" class="back-to-top" id="backToTop">^</a>
                    <script>
                        // Theme switch: an explicit choice is stored and wins; System clears it
                        const themeButtons = Array.from(document.querySelectorAll('.theme-switch button'));
                        function applyTheme(mode) {
                            if (mode === 'light' || mode === 'dark') {
                                document.documentElement.dataset.theme = mode;
                                localStorage.setItem('nucleo-theme', mode);
                            } else {
                                delete document.documentElement.dataset.theme;
                                localStorage.removeItem('nucleo-theme');
                            }
                            themeButtons.forEach(function(b) {
                                b.classList.toggle('active', b.dataset.mode === (mode || 'system'));
                            });
                        }
                        themeButtons.forEach(function(b) {
                            b.addEventListener('click', function() { applyTheme(b.dataset.mode); });
                        });
                        applyTheme(localStorage.getItem('nucleo-theme') || 'system');

                        // Initialize syntax highlighting on the language-tagged fences
                        hljs.highlightAll();

                        // Class names inside highlighted code link into the javadoc, the way
                        // backticked names in prose do. highlightAll() above defers its work to a
                        // DOMContentLoaded listener hljs registered ON WINDOW when its script
                        // loaded; the walk must register on window too - a document listener would
                        // fire before the event reaches window, ahead of the highlighting - and
                        // after it, so it sees the class_ spans highlight.js produced.
                        if (typeof JAVADOC_CLASS_INDEX !== 'undefined') {
                            window.addEventListener('DOMContentLoaded', function() {
                                document.querySelectorAll('pre code .hljs-title.class_').forEach(function(span) {
                                    const path = JAVADOC_CLASS_INDEX[span.textContent];
                                    if (path && !span.querySelector('a')) {
                                        const link = document.createElement('a');
                                        link.href = 'javadoc/' + path;
                                        link.textContent = span.textContent;
                                        span.textContent = '';
                                        span.appendChild(link);
                                    }
                                });
                            });
                        }

                        // Search. The index and the search library load the first time the box
                        // takes focus, so a page that is only read pays for neither. A script that
                        // fails to load is forgotten, so the next focus tries it again.
                        const searchBox = document.getElementById('search-box');
                        const searchPanel = document.getElementById('search-results');
                        const searchScripts = {};
                        let searchEngine = null;
                        let searchActive = -1;
                        function loadScript(src) {
                            if (!searchScripts[src]) {
                                searchScripts[src] = new Promise(function(resolve, reject) {
                                    const script = document.createElement('script');
                                    script.src = src;
                                    script.onload = resolve;
                                    script.onerror = function() {
                                        delete searchScripts[src];
                                        script.remove();
                                        reject(new Error('could not load ' + src));
                                    };
                                    document.head.appendChild(script);
                                });
                            }
                            return searchScripts[src];
                        }
                        function loadSearch() {
                            return Promise.all([
                                loadScript('search-index.js'),
                                loadScript('https://cdnjs.cloudflare.com/ajax/libs/minisearch/7.2.0/umd/index.min.js')
                            ]).then(function() {
                                if (!searchEngine) {
                                    // A section is found by its heading, a page's opening and a class by
                                    // the title: one name field, weighed above the text under it
                                    searchEngine = new MiniSearch({
                                        fields: ['name', 'text'],
                                        extractField: function(entry, field) {
                                            return field === 'name' ? (entry.heading || entry.title) : entry[field];
                                        },
                                        searchOptions: {
                                            boost: { name: 4 },
                                            combineWith: 'AND',
                                            prefix: function(term) { return term.length > 2; },
                                            fuzzy: function(term) { return term.length > 3 ? 0.2 : false; }
                                        }
                                    });
                                    searchEngine.addAll(SEARCH_INDEX.map(function(entry, i) {
                                        return Object.assign({ id: i }, entry);
                                    }));
                                }
                            });
                        }
                        function isWordChar(c) {
                            return c !== undefined && (c.toLowerCase() !== c.toUpperCase() || (c >= '0' && c <= '9'));
                        }
                        // The next place at or after `from` where a matched term opens a word and
                        // ends before `to`; of two terms opening the same word, the longer one
                        function nextMatch(lower, terms, from, to) {
                            let best = null;
                            terms.forEach(function(term) {
                                let at = lower.indexOf(term, from);
                                while (at >= 0 && isWordChar(lower[at - 1])) {
                                    at = lower.indexOf(term, at + 1);
                                }
                                const end = at + term.length;
                                if (at >= 0 && end <= to && (best === null || at < best.at || (at === best.at && end > best.end))) {
                                    best = { at: at, end: end };
                                }
                            });
                            return best;
                        }
                        // The stretch of an entry's text around its first match, cut between
                        // words, the matches marked
                        function searchSnippet(text, terms) {
                            const lower = text.toLowerCase();
                            const first = nextMatch(lower, terms, 0, text.length);
                            let from = first === null ? 0 : Math.max(0, first.at - 60);
                            if (from > 0) {
                                from = text.lastIndexOf(' ', first.at) < from ? first.at : text.indexOf(' ', from) + 1;
                            }
                            let to = Math.min(text.length, from + 200);
                            if (to < text.length && text.lastIndexOf(' ', to) > from) {
                                to = text.lastIndexOf(' ', to);
                            }
                            const out = document.createElement('span');
                            out.className = 'hit-text';
                            let at = from;
                            if (from > 0) {
                                out.append('… ');
                            }
                            for (let match = nextMatch(lower, terms, at, to); match !== null; match = nextMatch(lower, terms, at, to)) {
                                out.append(text.slice(at, match.at));
                                const mark = document.createElement('mark');
                                mark.textContent = text.slice(match.at, match.end);
                                out.append(mark);
                                at = match.end;
                            }
                            out.append(text.slice(at, to));
                            if (to < text.length) {
                                out.append(' …');
                            }
                            return out;
                        }
                        function searchNote(text) {
                            const note = document.createElement('div');
                            note.className = 'search-note';
                            note.textContent = text;
                            searchPanel.replaceChildren(note);
                            searchPanel.hidden = false;
                        }
                        function setActiveHit(index) {
                            const hits = searchPanel.querySelectorAll('a');
                            hits.forEach(function(hit, i) { hit.classList.toggle('active', i === index); });
                            searchActive = index;
                            if (hits[index]) {
                                hits[index].scrollIntoView({ block: 'nearest' });
                            }
                        }
                        function renderSearch() {
                            const query = searchBox.value.trim();
                            searchActive = -1;
                            if (!query) {
                                searchPanel.hidden = true;
                                return;
                            }
                            const results = searchEngine.search(query);
                            if (results.length === 0) {
                                searchNote('Nothing found for "' + query + '"');
                                return;
                            }
                            searchPanel.replaceChildren.apply(searchPanel, results.slice(0, 12).map(function(result) {
                                const entry = SEARCH_INDEX[result.id];
                                const hit = document.createElement('a');
                                hit.href = entry.url;
                                const title = document.createElement('span');
                                title.className = 'hit-title';
                                title.textContent = entry.heading ? entry.title + ' › ' + entry.heading : entry.title;
                                hit.append(title);
                                if (entry.part) {
                                    const part = document.createElement('span');
                                    part.className = 'hit-part';
                                    part.textContent = entry.part;
                                    hit.append(part);
                                }
                                if (entry.text) {
                                    hit.append(searchSnippet(entry.text, result.terms));
                                }
                                hit.addEventListener('click', function() { searchPanel.hidden = true; });
                                return hit;
                            }));
                            searchPanel.hidden = false;
                            setActiveHit(0);
                        }
                        function search() {
                            loadSearch().then(renderSearch, function(error) {
                                console.error(error);
                                searchNote('Search is unavailable: ' + error.message);
                            });
                        }
                        searchBox.addEventListener('focus', search);
                        searchBox.addEventListener('input', search);
                        searchBox.addEventListener('keydown', function(e) {
                            const hits = searchPanel.querySelectorAll('a');
                            if (e.key === 'ArrowDown' && hits.length > 0) {
                                e.preventDefault();
                                setActiveHit(searchActive + 1 < hits.length ? searchActive + 1 : 0);
                            } else if (e.key === 'ArrowUp' && hits.length > 0) {
                                e.preventDefault();
                                setActiveHit(searchActive > 0 ? searchActive - 1 : hits.length - 1);
                            } else if (e.key === 'Enter' && hits[searchActive] && !searchPanel.hidden) {
                                e.preventDefault();
                                hits[searchActive].click();
                            } else if (e.key === 'Escape') {
                                searchBox.value = '';
                                searchPanel.hidden = true;
                                searchBox.blur();
                            }
                        });
                        // A slash typed anywhere outside a field goes to the search box
                        document.addEventListener('keydown', function(e) {
                            const field = e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.isContentEditable;
                            if (e.key === '/' && !field && !e.ctrlKey && !e.metaKey && !e.altKey) {
                                e.preventDefault();
                                searchBox.focus();
                            }
                        });
                        document.addEventListener('click', function(e) {
                            if (!document.getElementById('search').contains(e.target)) {
                                searchPanel.hidden = true;
                            }
                        });

                        // Versions. A site published beside other versions names, on its version
                        // badge, the list of them: versions.json in the directory above, newest
                        // first. The badge then becomes a choice among them. A site that stands
                        // alone names no list, and one opened from disk cannot fetch a file; both
                        // keep the plain badge, and so does a site whose list is not there yet.
                        const versionBadge = document.getElementById('nav-version');
                        function offerVersions(versions) {
                            const current = versionBadge.textContent;
                            const select = document.createElement('select');
                            select.setAttribute('aria-label', 'Version of the documentation');
                            versions.forEach(function(version) {
                                const option = document.createElement('option');
                                option.textContent = version;
                                select.append(option);
                            });
                            select.value = current;
                            // The same page of the chosen version where it has one, its front page where it does not
                            select.addEventListener('change', function() {
                                const page = location.pathname.split('/').pop() || 'index.html';
                                const there = '../' + select.value + '/';
                                fetch(there + page, { method: 'HEAD' }).then(function(found) {
                                    location.href = there + (found.ok ? page + location.hash : 'index.html');
                                });
                            });
                            // A page restored by the Back button shows its own version again, not the one chosen on leaving it
                            window.addEventListener('pageshow', function() { select.value = current; });
                            versionBadge.replaceChildren(select);
                            versionBadge.classList.add('choice');
                        }
                        if (versionBadge.dataset.versions && location.protocol !== 'file:') {
                            fetch(versionBadge.dataset.versions).then(function(response) {
                                return response.ok ? response.json() : null;
                            }).then(function(versions) {
                                if (versions !== null) {
                                    offerVersions(versions);
                                }
                            });
                        }

                        // Back to top button
                        window.addEventListener('scroll', function() {
                            const backToTop = document.getElementById('backToTop');
                            if (window.pageYOffset > 300) {
                                backToTop.style.display = 'block';
                            } else {
                                backToTop.style.display = 'none';
                            }
                        });
                        document.getElementById('backToTop').addEventListener('click', function(e) {
                            e.preventDefault();
                            window.scrollTo({ top: 0, behavior: 'smooth' });
                        });

                        // Collapsible navigation sections
                        function toggleSection(sectionId) {
                            const section = document.querySelector('[data-section="' + sectionId + '"]');
                            if (section) {
                                section.classList.toggle('collapsed');
                                const icon = section.querySelector('.collapse-icon');
                                if (icon) {
                                    icon.textContent = section.classList.contains('collapsed') ? '+' : '-';
                                }
                                // Save expanded sections to localStorage (since collapsed is default)
                                const expanded = document.querySelectorAll('.nav-section:not(.collapsed)');
                                const expandedIds = Array.from(expanded).map(s => s.dataset.section);
                                localStorage.setItem('expandedSections', JSON.stringify(expandedIds));
                            }
                        }

                        document.addEventListener('DOMContentLoaded', function() {
                            // The nav is shared by all pages; mark the link for this page active
                            const page = location.pathname.split('/').pop() || 'index.html';
                            document.querySelectorAll('nav a').forEach(function(a) {
                                if (a.getAttribute('href') === page) {
                                    a.classList.add('active');
                                }
                            });

                            // Start with all sections collapsed
                            document.querySelectorAll('.nav-section').forEach(function(section) {
                                section.classList.add('collapsed');
                                const icon = section.querySelector('.collapse-icon');
                                if (icon) icon.textContent = '+';
                            });

                            // Restore expanded sections from localStorage
                            const saved = localStorage.getItem('expandedSections');
                            if (saved) {
                                const expandedIds = JSON.parse(saved);
                                expandedIds.forEach(function(id) {
                                    const section = document.querySelector('[data-section="' + id + '"]');
                                    if (section) {
                                        section.classList.remove('collapsed');
                                        const icon = section.querySelector('.collapse-icon');
                                        if (icon) icon.textContent = '-';
                                    }
                                });
                            }

                            // Always expand the section containing the active link
                            const activeLink = document.querySelector('nav a.active');
                            if (activeLink) {
                                const section = activeLink.closest('.nav-section');
                                if (section && section.classList.contains('collapsed')) {
                                    section.classList.remove('collapsed');
                                    const icon = section.querySelector('.collapse-icon');
                                    if (icon) icon.textContent = '-';
                                }
                            }
                        });
                    </script>
                </body>
                </html>
                """.formatted(title, classIndexScript, nav, content);
    }
}
