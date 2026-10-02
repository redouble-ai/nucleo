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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publishing a generated site into a checkout of the documentation repository: a version
 * not published before is added, one published before is replaced whole; the list of
 * versions, the routing file, the robots file, the not-found page and the repository's
 * license and notice follow the directories; and what is not a site, not the repository
 * or not a version is refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-01)
 */
class PublishDocsTest {
    @TempDir
    Path work;
    Path site;
    Path repository;

    @BeforeEach
    void aSiteAndAnEmptyRepository() throws IOException {
        site = work.resolve("generated");
        repository = work.resolve("docs-repo");
        write(site.resolve("index.html"), "front page");
        write(site.resolve("javadoc/index.html"), "javadoc");
        write(site.resolve("LICENSE.txt"), "the license");
        write(site.resolve("NOTICE.txt"), "the notice");
        write(site.resolve(".gitignore"), "*\n");
        write(repository.resolve("site/nucleo/versions.json"), "[]\n");
    }

    @Test
    void aVersionNotPublishedBeforeIsAddedAsADirectoryOfItsOwn() throws IOException {
        PublishDocs.publish(site, repository, "0.1.1");
        assertEquals("front page", read("site/nucleo/0.1.1/index.html"), "the site's pages land under the version");
        assertEquals("javadoc", read("site/nucleo/0.1.1/javadoc/index.html"), "the site's subdirectories come along");
        assertFalse(Files.exists(repository.resolve("site/nucleo/0.1.1/.gitignore")), "the site's own .gitignore stays behind, or the repository would ignore what was published");
        assertEquals("[\"0.1.1\"]\n", read("site/nucleo/versions.json"), "the list names the version");
    }

    @Test
    void aVersionPublishedBeforeIsReplacedWholeAndTheOthersAreLeftAlone() throws IOException {
        PublishDocs.publish(site, repository, "0.1.0");
        PublishDocs.publish(site, repository, "0.1.1");
        write(repository.resolve("site/nucleo/0.1.1/dropped-page.html"), "a page the new publication no longer has");
        write(site.resolve("index.html"), "front page, corrected");
        PublishDocs.publish(site, repository, "0.1.1");
        assertEquals("front page, corrected", read("site/nucleo/0.1.1/index.html"), "the version carries the new publication");
        assertFalse(Files.exists(repository.resolve("site/nucleo/0.1.1/dropped-page.html")), "no page of the earlier publication survives");
        assertEquals("front page", read("site/nucleo/0.1.0/index.html"), "another version is not touched");
        assertEquals("[\"0.1.1\", \"0.1.0\"]\n", read("site/nucleo/versions.json"), "the list names each version once");
    }

    @Test
    void theListIsNewestFirstWhateverTheOrderOfPublishing() throws IOException {
        for (String version : List.of("0.10.0", "0.2.0", "1.0.0", "1.0.0-RC1", "0.9")) {
            PublishDocs.publish(site, repository, version);
        }
        assertEquals("[\"1.0.0\", \"1.0.0-RC1\", \"0.10.0\", \"0.9\", \"0.2.0\"]\n", read("site/nucleo/versions.json"),
                "numbers compare as numbers, and a qualified version comes before the one it leads to");
    }

    @Test
    void theAddressWithoutAVersionIsServedByTheNewestVersion() throws IOException {
        PublishDocs.publish(site, repository, "0.2.0");
        PublishDocs.publish(site, repository, "0.1.1");
        String redirects = read("site/_redirects");
        assertTrue(redirects.contains("/nucleo/current/*   /nucleo/0.2.0/:splat   200\n"), "publishing an older version leaves current on the newest: " + redirects);
        assertTrue(redirects.contains("/                   /nucleo/current/                302\n") && redirects.contains("/nucleo             /nucleo/current/                302\n"),
                "the site's root and the product's root lead to current: " + redirects);
    }

    @Test
    void theFilesACodingAgentLooksForAtTheRootLeadToTheNewestVersions() throws IOException {
        PublishDocs.publish(site, repository, "0.1.1");
        String redirects = read("site/_redirects");
        assertTrue(redirects.contains("/llms.txt           /nucleo/current/llms.txt        302\n") && redirects.contains("/llms-full.txt      /nucleo/current/llms-full.txt   302\n"),
                "as redirects, so the links inside resolve against the version: " + redirects);
    }

    @Test
    void searchEnginesAreKeptToTheCurrentAddressAndOutOfEveryNumberedCopy() throws IOException {
        PublishDocs.publish(site, repository, "0.1.1");
        PublishDocs.publish(site, repository, "0.2.0");
        String robots = read("site/robots.txt");
        assertTrue(robots.endsWith("User-agent: *\nDisallow: /nucleo/0.2.0/\nDisallow: /nucleo/0.1.1/\n"), "every published version's own directory is kept out: " + robots);
        assertFalse(robots.contains("Disallow: /nucleo/current"), "the address the newest version is served under stays open: " + robots);
    }

    @Test
    void anAddressThatLeadsNowhereGetsAPageThatLeadsToTheCurrentDocumentation() throws IOException {
        PublishDocs.publish(site, repository, "0.1.1");
        String notFound = read("site/404.html");
        assertTrue(notFound.contains("<h1>Page not found</h1>") && notFound.contains("<a href=\"/nucleo/current/\">"), "the page says what happened and where the documentation is: " + notFound);
        assertTrue(notFound.contains("href=\"/nucleo/current/style.css\""), "it borrows the look of the current version, by an address that works wherever the page is shown");
    }

    @Test
    void theRepositorysOwnLicenseAndNoticeAreThoseOfTheNewestVersion() throws IOException {
        PublishDocs.publish(site, repository, "0.2.0");
        assertEquals("the license", read("LICENSE"), "the license of the version just published");
        assertEquals("the notice", read("NOTICE"), "the notice of the version just published");
        write(site.resolve("NOTICE.txt"), "the notice of an older version");
        PublishDocs.publish(site, repository, "0.1.1");
        assertEquals("the notice", read("NOTICE"), "publishing an older version leaves the repository's notice alone");
        assertEquals("the notice of an older version", read("site/nucleo/0.1.1/NOTICE.txt"), "each version keeps its own");
        write(site.resolve("NOTICE.txt"), "the notice of a newer version");
        PublishDocs.publish(site, repository, "0.3.0");
        assertEquals("the notice of a newer version", read("NOTICE"), "a newer version brings its notice");
    }

    @Test
    void aDirectoryWithoutAGeneratedSiteIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> PublishDocs.publish(work.resolve("nothing"), repository, "0.1.1"));
        assertTrue(refused.getMessage().contains("no generated site at") && refused.getMessage().contains("index.html"), "the refusal names what is missing: " + refused.getMessage());
    }

    @Test
    void aDirectoryThatIsNotTheDocumentationRepositoryIsRefusedAndLeftUntouched() throws IOException {
        Path elsewhere = work.resolve("elsewhere");
        Files.createDirectories(elsewhere);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> PublishDocs.publish(site, elsewhere, "0.1.1"));
        assertTrue(refused.getMessage().contains("is not a checkout of the documentation repository") && refused.getMessage().contains("site/nucleo/versions.json"),
                "the refusal names what a checkout carries: " + refused.getMessage());
        try (var entries = Files.list(elsewhere)) {
            assertEquals(0, entries.count(), "nothing was written there");
        }
    }

    @Test
    void whatIsNotAVersionIsRefusedNamingTheAcceptedShape() {
        for (String version : List.of("latest", "0.1.1/../../x", "", "v1.0", "${project.version}")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> PublishDocs.publish(site, repository, version), version);
            assertTrue(refused.getMessage().contains("like 0.1.1 or 1.0.0-RC1"), "the refusal shows what a version looks like: " + refused.getMessage());
        }
    }

    @Test
    void aDirectoryAmongTheVersionsThatIsNotOneIsRefused() throws IOException {
        Files.createDirectories(repository.resolve("site/nucleo/drafts"));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> PublishDocs.publish(site, repository, "0.1.1"));
        assertTrue(refused.getMessage().contains("drafts") && refused.getMessage().contains("is not a published version"), "the refusal names the directory: " + refused.getMessage());
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String path) throws IOException {
        return Files.readString(repository.resolve(path));
    }
}
