/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The site as a reader walks it, generated from a reactor of two package pages, a README
 * and a contents file: the reading order in the nav, the frame and the previous and next
 * links of every page, the example callout, the checked samples, the llms.txt pair, the
 * search index and the search box, the version badge, the copyright and license footer,
 * and the refusals when the contents and the tree disagree.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class GenerateDocsTest {
    @TempDir
    Path root;
    Path site;

    @BeforeEach
    void reactor() throws IOException {
        site = root.resolve("site");
        write("LICENSE", "The license, whole.\n");
        write("NOTICE", "Nucleo\nCopyright 2024-present The Authors\n\nWhat the notice goes on to say.\n");
        write("README.md", "# Nucleo\n\nThe front page.\n");
        write("mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md", """
                # Package: ai.redouble.nucleo.alpha

                Alpha explained, then its [beta](beta/PACKAGE.md).

                > **Example:** [the hello example](../../../../../../../../README.md).
                """);
        write("mod-a/src/main/java/ai/redouble/nucleo/alpha/beta/PACKAGE.md", "# Package: ai.redouble.nucleo.alpha.beta\n\nBeta.\n");
        write("mod-a/src/main/java/ai/redouble/nucleo/alpha/Hello.java", """
                package ai.redouble.nucleo.alpha;

                class Hello {
                    // region greet
                    String greet() {
                        return "hello";
                    }
                    // endregion
                }
                """);
        contents("""
                # Nucleo documentation

                The site's introduction.

                - [Overview](../README.md)

                ## Getting to know alpha

                Alpha first, beta under it.

                - [All about alpha](../mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md)
                  - [Beta, nested](../mod-a/src/main/java/ai/redouble/nucleo/alpha/beta/PACKAGE.md)
                """);
    }

    @Test
    void theNavListsTheFrontMatterThenEachPartInReadingOrder() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        String page = read("alpha.html");
        int overview = page.indexOf("<a href=\"index.html\">Overview</a>");
        int part = page.indexOf("<span class=\"section-title\">Getting to know alpha</span>");
        int alpha = page.indexOf("<a href=\"alpha.html\">All about alpha</a>");
        int beta = page.indexOf("<a href=\"alpha-beta.html\">Beta, nested</a>");
        assertTrue(overview > 0 && overview < part && part < alpha && alpha < beta, "front matter, then the part, then its pages in the contents' order");
        assertTrue(page.indexOf("<ul class=\"nav-nested\">", alpha) < beta, "the indented item nests under the item above it");
    }

    @Test
    void everyPageCarriesItsPartTitleAndOriginAboveAndItsNeighboursBelow() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        String alpha = read("alpha.html");
        assertTrue(alpha.contains("<div class=\"crumb\">Part 1 · Getting to know alpha</div>"), "the breadcrumb names the numbered part");
        assertTrue(alpha.contains("<h1>All about alpha</h1>"), "the title comes from the contents");
        assertFalse(alpha.contains("Package: ai.redouble.nucleo.alpha</h1>"), "the source's own H1 gives way to the frame");
        assertTrue(alpha.contains("<div class=\"origin\">ai.redouble.nucleo.alpha · mod-a</div>"), "the origin names the package and the module");
        assertTrue(alpha.contains("<a class=\"prev\" href=\"index.html\">"), "the previous page is the one before in reading order");
        assertTrue(alpha.contains("<a class=\"next\" href=\"alpha-beta.html\">"), "the next page is the one after in reading order");
        String index = read("index.html");
        assertFalse(index.contains("class=\"crumb\""), "the front matter belongs to no part");
        assertFalse(index.contains("class=\"prev\""), "the first page has no previous");
        assertFalse(read("alpha-beta.html").contains("class=\"next\""), "the last page has no next");
    }

    @Test
    void aLinkToAnotherPageReadsAsThatPagesTitle() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        assertTrue(read("alpha.html").contains("<a href=\"alpha-beta.html\">beta</a>"), "a .md link resolves to the generated page");
    }

    @Test
    void aBlockquoteOpeningWithExampleIsTheHighlightedCallout() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        assertTrue(read("alpha.html").contains("<blockquote class=\"example\">"), "the example pointer is styled as a callout");
    }

    @Test
    void llmsTxtIsTheContentsAndLlmsFullTxtEveryPageInOrder() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        String index = read("llms.txt");
        assertTrue(index.startsWith("# Nucleo documentation\n\n> The site's introduction.\n"), "the title and the introduction open the index");
        assertTrue(index.contains("## Getting to know alpha\n\nAlpha first, beta under it.\n\n- [All about alpha](alpha.html)\n  - [Beta, nested](alpha-beta.html)\n"),
                "each part with its introduction and its pages, nesting kept");
        String full = read("llms-full.txt");
        assertTrue(full.indexOf("# All about alpha") < full.indexOf("# Beta, nested"), "the pages in reading order");
        assertTrue(full.contains("[beta](alpha-beta.html)"), "links point at the site's pages");
        assertFalse(full.contains("Package: ai.redouble.nucleo.alpha\n"), "each page under its contents title");
    }

    @Test
    void theSearchIndexCarriesEachPagesOpeningAndEachSectionAtItsAnchor() throws IOException {
        appendToAlpha("""

                ## The rules of `alpha`

                A rule with `code`, a [link](beta/PACKAGE.md) and "quotes"
                over two lines.

                <!-- sample: Hello.java#greet -->
                ```java
                String greet() {
                    return "hello";
                }
                ```

                | Kind | Role |
                |---|---|
                | first | leads |
                """);
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        String index = read("search-index.js");
        assertTrue(index.startsWith("const SEARCH_INDEX = [\n") && index.endsWith("];\n"), "the index is one array a script declares");
        assertTrue(index.contains("{\"url\": \"index.html\", \"title\": \"Overview\", \"part\": null, \"heading\": null, \"text\": \"The front page.\"},\n"),
                "a front matter page's opening: its title, no part, no heading: " + index);
        assertTrue(index.contains("{\"url\": \"alpha.html\", \"title\": \"All about alpha\", \"part\": \"Part 1 · Getting to know alpha\", \"heading\": null, "
                        + "\"text\": \"Alpha explained, then its beta. Example: the hello example.\"},\n"),
                "a page's opening carries its part and the text above its first heading, links as their text: " + index);
        assertTrue(index.contains("{\"url\": \"alpha.html#the-rules-of-alpha\", \"title\": \"All about alpha\", \"part\": \"Part 1 · Getting to know alpha\", \"heading\": \"The rules of alpha\", "
                        + "\"text\": \"A rule with code, a link and \\\"quotes\\\" over two lines. String greet() { return \\\"hello\\\"; } Kind Role first leads\"},\n"),
                "a section: its heading and its text as read, code and table cells included, the sample directive and the markdown syntax gone: " + index);
        assertTrue(read("alpha.html").contains("id=\"the-rules-of-alpha\""), "the anchor the entry lands on is the id the page gives the heading");
        assertFalse(index.contains("\"part\": \"Javadoc\""), "a build without the javadoc tree has no class to land on");
    }

    @Test
    void withTheJavadocTreeEveryPublicClassIsASearchEntryAtItsJavadocPage() throws IOException {
        write("mod-a/src/main/java/ai/redouble/nucleo/alpha/Greeter.java", "package ai.redouble.nucleo.alpha;\n\npublic class Greeter {}\n");
        GenerateDocs.generate(root, site, "1.2.3", true, true);
        String index = read("search-index.js");
        assertTrue(index.contains("{\"url\": \"javadoc/ai/redouble/nucleo/alpha/Greeter.html\", \"title\": \"Greeter\", \"part\": \"Javadoc\", \"heading\": null, "
                + "\"text\": \"ai.redouble.nucleo.alpha.Greeter\"},\n"), "a public class is found by its name and its package: " + index);
        assertFalse(index.contains("\"title\": \"Hello\""), "a package-private class has no javadoc page to land on");
    }

    @Test
    void everyPageCarriesTheSearchBoxAndLoadsTheIndexOnlyWhenItIsUsed() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        for (String name : new String[]{"index.html", "alpha.html", "alpha-beta.html"}) {
            String page = read(name);
            assertTrue(page.contains("<input type=\"search\" id=\"search-box\""), name + " carries the search box");
            assertTrue(page.contains("loadScript('search-index.js')") && page.contains("/minisearch/"), name + " loads the index and the search library from its script");
            assertFalse(page.contains("<script src=\"search-index.js\">"), name + " does not load the index while it is only read");
        }
    }

    @Test
    void aSitePublishedBesideOtherVersionsNamesTheListOfThemOnItsVersionBadge() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        for (String name : new String[]{"index.html", "alpha.html", "alpha-beta.html"}) {
            String page = read(name);
            assertTrue(page.contains("<span class=\"nav-version\" id=\"nav-version\" data-versions=\"../versions.json\">1.2.3</span>"),
                    name + " carries the version the site was generated for and the list in the directory above the site");
            assertTrue(page.contains("fetch(versionBadge.dataset.versions)") && page.contains("offerVersions(versions)"), name + " reads the list the badge names and offers its versions");
        }
    }

    @Test
    void aSiteThatStandsAloneCarriesItsVersionAndNamesNoList() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, false);
        for (String name : new String[]{"index.html", "alpha.html", "alpha-beta.html"}) {
            String page = read(name);
            assertTrue(page.contains("<span class=\"nav-version\" id=\"nav-version\">1.2.3</span>"), name + " carries the version as a plain badge");
            assertFalse(page.contains("data-versions"), name + " names no list of versions, so the page asks for none");
        }
    }

    @Test
    void everyPageEndsWithTheNoticesCopyrightLineAndTheLicenseTheSiteCarries() throws IOException {
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        for (String name : new String[]{"index.html", "alpha.html", "alpha-beta.html"}) {
            assertTrue(read(name).contains("<footer class=\"page-foot\">Copyright 2024-present The Authors. Licensed under the <a href=\"LICENSE.txt\">Apache License, Version 2.0</a>; "
                    + "see the <a href=\"NOTICE.txt\">notice</a>.</footer>"), name + " states whose work it is, taken from the NOTICE, and links the license and the notice");
        }
        assertEquals("The license, whole.\n", read("LICENSE.txt"), "the site carries the reactor's LICENSE");
        assertEquals("Nucleo\nCopyright 2024-present The Authors\n\nWhat the notice goes on to say.\n", read("NOTICE.txt"), "the site carries the reactor's NOTICE");
    }

    @Test
    void aNoticeWithoutACopyrightLineFailsTheBuild() throws IOException {
        write("NOTICE", "Nucleo\n\nNo line names the copyright holder.\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("NOTICE") && refused.getMessage().contains("must carry a line that opens with 'Copyright '"),
                "the refusal names the file and the line it needs: " + refused.getMessage());
    }

    @Test
    void aPackageDocTheContentsDoNotListFailsTheBuildNamingIt() throws IOException {
        write("mod-a/src/main/java/ai/redouble/nucleo/gamma/PACKAGE.md", "# Package: ai.redouble.nucleo.gamma\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("mod-a/src/main/java/ai/redouble/nucleo/gamma/PACKAGE.md"), "the refusal names the unlisted file: " + refused.getMessage());
    }

    @Test
    void aSampleEqualToItsRegionPasses() throws IOException {
        appendToAlpha("""

                <!-- sample: Hello.java#greet -->
                ```java
                String greet() {
                    return "hello";
                }
                ```
                """);
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        assertTrue(read("alpha.html").contains("<code class=\"language-java\">String greet()"), "the checked sample renders as its fence");
    }

    @Test
    void aSampleThatDiffersFromItsRegionFailsWithTheRegionsText() throws IOException {
        appendToAlpha("""

                <!-- sample: Hello.java#greet -->
                ```java
                String greet() {
                    return "goodbye";
                }
                ```
                """);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("return \"hello\";"), "the refusal carries the region as it stands: " + refused.getMessage());
    }

    @Test
    void aSampleNamingAMissingRegionFails() throws IOException {
        appendToAlpha("\n<!-- sample: Hello.java#wave -->\n```java\nwave();\n```\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("no '// region wave'"), "the refusal names the missing region: " + refused.getMessage());
    }

    @Test
    void aSampleNamingAMissingFileFails() throws IOException {
        appendToAlpha("\n<!-- sample: Gone.java#greet -->\n```java\ngreet();\n```\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("no such file:") && refused.getMessage().contains("Gone.java"),
                "the refusal names the missing file: " + refused.getMessage());
    }

    @Test
    void aSampleDirectiveWithNoFenceUnderItFails() throws IOException {
        appendToAlpha("\n<!-- sample: Hello.java#greet -->\n\nProse instead of the fence.\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("followed on the next line by the fenced sample"),
                "the refusal states where the fence belongs: " + refused.getMessage());
    }

    @Test
    void backtickedPublicClassesAndPackagesLinkIntoTheJavadoc() throws IOException {
        write("mod-a/src/main/java/ai/redouble/nucleo/alpha/Greeter.java", "package ai.redouble.nucleo.alpha;\n\npublic class Greeter {}\n");
        appendToAlpha("\n`Greeter` lives in `ai.redouble.nucleo.alpha`, beside `Hello`.\n");
        GenerateDocs.generate(root, site, "1.2.3", true, true);
        String alpha = read("alpha.html");
        assertTrue(alpha.contains("<a href=\"javadoc/ai/redouble/nucleo/alpha/Greeter.html\">Greeter</a>"), "a public class links to its page");
        assertTrue(alpha.contains("<a href=\"javadoc/ai/redouble/nucleo/alpha/package-summary.html\">ai.redouble.nucleo.alpha</a>"), "a package links to its summary");
        assertTrue(alpha.contains("<code>Hello</code>"), "a package-private class has no javadoc page and stays code");
    }

    @Test
    void aLinkThatResolvesNowhereStaysAsWritten() throws IOException {
        appendToAlpha("\nSee [elsewhere](nowhere/PACKAGE.md).\n");
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        assertTrue(read("alpha.html").contains("<a href=\"nowhere/PACKAGE.md\">elsewhere</a>"), "an unresolved link is left for its author to fix");
    }

    @Test
    void aLinkWrittenInsideCodeIsShownAsWrittenAndALinkWithCodeTextResolves() throws IOException {
        appendToAlpha("\nThe syntax is `[text](beta/PACKAGE.md)`, and [`beta`](beta/PACKAGE.md) is a link.\n\n```\n[fenced](beta/PACKAGE.md)\n```\n");
        GenerateDocs.generate(root, site, "1.2.3", false, true);
        String alpha = read("alpha.html");
        assertTrue(alpha.contains("<code>[text](beta/PACKAGE.md)</code>"), "a link inside a code span stays code");
        assertTrue(alpha.contains("[fenced](beta/PACKAGE.md)"), "a link inside a fence stays code");
        assertTrue(alpha.contains("<a href=\"alpha-beta.html\"><code>beta</code></a>"), "a link whose text is code resolves");
    }

    @Test
    void twoPagesWithOneIdFailTheBuild() throws IOException {
        write("notes/ALPHA.md", "# Alpha notes\n");
        write("mod-a/src/main/java/ai/redouble/nucleo/ALPHA.md", "# Alpha again\n");
        contents("""
                # Docs

                - [Overview](../README.md)

                ## Part

                - [All about alpha](../mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md)
                  - [Beta, nested](../mod-a/src/main/java/ai/redouble/nucleo/alpha/beta/PACKAGE.md)
                - [Notes](../notes/ALPHA.md)
                - [Again](../mod-a/src/main/java/ai/redouble/nucleo/ALPHA.md)
                """);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("two pages share the id alpha"), "the refusal names the id: " + refused.getMessage());
    }

    @Test
    void theContentsRefuseAListItemOfAnyOtherShape() throws IOException {
        contents("# Docs\n\n- [Overview](../README.md) and more\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> Contents.read(root.resolve("nucleo-docs/CONTENTS.md")));
        assertTrue(refused.getMessage().contains("exactly '- [Title](relative/path.md)'"), "the refusal states the accepted shape: " + refused.getMessage());
    }

    @Test
    void theContentsRefuseALinkToAFileThatDoesNotExist() throws IOException {
        contents("# Docs\n\n- [Overview](../README.md)\n\n## Part\n\n- [Gone](../gone/PACKAGE.md)\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> Contents.read(root.resolve("nucleo-docs/CONTENTS.md")));
        assertTrue(refused.getMessage().contains("no such file: ../gone/PACKAGE.md"), "the refusal names the missing file: " + refused.getMessage());
    }

    @Test
    void theContentsRefuseAFileListedTwice() throws IOException {
        contents("""
                # Docs

                - [Overview](../README.md)

                ## Part

                - [All about alpha](../mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md)
                  - [Beta, nested](../mod-a/src/main/java/ai/redouble/nucleo/alpha/beta/PACKAGE.md)
                - [Alpha again](../mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md)
                """);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> Contents.read(root.resolve("nucleo-docs/CONTENTS.md")));
        assertTrue(refused.getMessage().contains("listed twice"), "the refusal names the duplicate: " + refused.getMessage());
    }

    @Test
    void theContentsMustListTheReactorsReadme() throws IOException {
        contents("""
                # Docs

                ## Part

                - [All about alpha](../mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md)
                  - [Beta, nested](../mod-a/src/main/java/ai/redouble/nucleo/alpha/beta/PACKAGE.md)
                """);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> GenerateDocs.generate(root, site, "1.2.3", false, true));
        assertTrue(refused.getMessage().contains("must list the reactor's README.md"),
                "the refusal names the site's index page: " + refused.getMessage());
    }

    private void appendToAlpha(String markdown) throws IOException {
        Path alpha = root.resolve("mod-a/src/main/java/ai/redouble/nucleo/alpha/PACKAGE.md");
        Files.writeString(alpha, Files.readString(alpha) + markdown);
    }

    private void contents(String markdown) throws IOException {
        write("nucleo-docs/CONTENTS.md", markdown);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String name) throws IOException {
        return Files.readString(site.resolve(name));
    }
}
