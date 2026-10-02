/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three tools of the decision agent's palette on the shipped corpus, and the digests a
 * decision model reads of what they produce: the listing names every file with what its bytes
 * say it is, a read gives a text or Office file's text and refuses the rest with the reason,
 * and a split cuts a document into the statements a person would highlight.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class DecideToolsTest {
    static final Path CORPUS = Path.of("src/main/resources/corpus").toAbsolutePath();

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS), "the shipped corpus is at " + CORPUS);
        JobDispatcher.getInstance().start();
        Digests.register();
    }

    private static ListArtifact<FileEntry> listing() throws Exception {
        ListFolderTool list = new ListFolderTool(Job.workflow("test", "decide-tools"));
        list.setInput(new Folder(CORPUS.toString()));
        return JobDispatcher.getInstance().submit(list).get();
    }

    private static FileEntry file(ListArtifact<FileEntry> listing, String name) {
        return listing.getIterands().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Document read(FileEntry file) throws Exception {
        ReadDocumentTool read = new ReadDocumentTool(Job.workflow("test", "decide-tools"));
        read.setInput(file);
        return JobDispatcher.getInstance().submit(read).get();
    }

    @Test
    void theListingSaysWhatEveryFileIsAndWhatATextFileStartsWith() throws Exception {
        ListArtifact<FileEntry> listing = listing();
        assertEquals("file", listing.getIterandTypeAlias());
        assertEquals(30, listing.getIterands().size(), "every regular file of the corpus");
        assertEquals(FileEntry.TEXT, file(listing, "minutes-2026-05-06.md").getKind());
        assertTrue(file(listing, "minutes-2026-05-06.md").getHead().startsWith("# Management meeting, minutes"), file(listing, "minutes-2026-05-06.md").getHead());
        assertEquals(FileEntry.TEXT, file(listing, "supplier-terms.notes").getKind(), "text under an extension nobody knows, by its bytes");
        assertEquals(FileEntry.BINARY, file(listing, "keys.enc").getKind());
        assertEquals(FileEntry.BINARY, file(listing, "telemetry.dat").getKind());
        assertEquals(FileEntry.IMAGE, file(listing, "notes.txt").getKind(), "a JPEG under a text extension is an image, by its bytes");
        assertEquals(FileEntry.IMAGE, file(listing, "shop-sign.png").getKind());
        assertEquals(FileEntry.PDF, file(listing, "invoice-0417.pdf").getKind());
        assertEquals(FileEntry.OFFICE, file(listing, "price-list.xlsx").getKind());
        assertEquals(FileEntry.EMPTY, file(listing, "empty.txt").getKind());
        assertNull(file(listing, "keys.enc").getHead(), "no first words for what is not text");
        String digest = TextFormatterRegistry.format(file(listing, "minutes-2026-05-06.md"));
        assertTrue(digest.startsWith("minutes-2026-05-06.md (text, ") && digest.contains("KB): # Management meeting"), "one line a model reads: " + digest);
    }

    @Test
    void aReadGivesTheTextAndRefusesWhatCodeCannotReadWithTheReason() throws Exception {
        ListArtifact<FileEntry> listing = listing();
        Document minutes = read(file(listing, "minutes-2026-05-06.md"));
        assertTrue(minutes.getText().contains("go up by 20 percent from"), "the whole text");
        assertEquals("minutes-2026-05-06.md", minutes.getName());
        Document sheet = read(file(listing, "price-list.xlsx"));
        assertTrue(sheet.getText().contains("Kestrel"), "an Office document read without POI: " + sheet.getText());
        for (String refused : List.of("shop-sign.png", "invoice-0417.pdf", "keys.enc", "empty.txt")) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> read(file(listing, refused)), refused);
            Throwable cause = failure.getCause();
            assertInstanceOf(InvalidInputException.class, cause, refused + ": the refusal is the tool's own verdict on its input, which a decision thinker feeds back");
            assertTrue(((InvalidInputException) cause).getLLMMessage().contains(refused), ((InvalidInputException) cause).getLLMMessage());
        }
        String digest = TextFormatterRegistry.format(minutes);
        assertTrue(digest.startsWith("minutes-2026-05-06.md (") && digest.contains(" characters): # Management meeting"), digest);
    }

    @Test
    void aPathThatIsNotAFolderIsRefusedWithTheReason() {
        ListFolderTool list = new ListFolderTool(Job.workflow("test", "decide-tools"));
        list.setInput(new Folder(CORPUS.resolve("minutes-2026-05-06.md").toString()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(list).get());
        assertInstanceOf(InvalidInputException.class, failure.getCause(), "the tool's own verdict on its input");
        assertTrue(((InvalidInputException) failure.getCause()).getLLMMessage().contains("folder"), ((InvalidInputException) failure.getCause()).getLLMMessage());
    }

    @Test
    void aTextFileByExtensionThatDoesNotDecodeIsRefusedAsNotText() throws Exception {
        Path folder = Files.createTempDirectory("decide-tools");
        Path junk = folder.resolve("junk.txt");
        byte[] bytes = new byte[2000];
        new java.util.Random(7).nextBytes(bytes);
        Files.write(junk, bytes);
        try {
            FileEntry entry = ListFolderTool.entry(junk);
            assertEquals(FileEntry.TEXT, entry.getKind(), "the extension says text, so the listing lists it as text");
            assertNull(entry.getHead(), "but its first bytes gave no words");
            ExecutionException failure = assertThrows(ExecutionException.class, () -> read(entry));
            assertInstanceOf(InvalidInputException.class, failure.getCause());
            assertTrue(((InvalidInputException) failure.getCause()).getLLMMessage().contains("do not decode"), ((InvalidInputException) failure.getCause()).getLLMMessage());
        }
        finally {
            Files.deleteIfExists(junk);
            Files.deleteIfExists(folder);
        }
    }

    @Test
    void theDigestsReadAsAPersonWouldGlance() {
        assertEquals("the folder corpus", TextFormatterRegistry.format(new Folder("/tmp/corpus")), "its name, never where it sits on disk");
        assertEquals("the folder /", TextFormatterRegistry.format(new Folder("/")), "the root has no name and reads as itself");
        assertEquals(100, Digests.HEAD_CHARS);
        assertEquals(150, Digests.STATEMENT_CHARS);
        assertEquals(Digests.HEAD_CHARS + "...".length(), Digests.cut("x".repeat(400), Digests.HEAD_CHARS).length(), "cut with an ellipsis past the length");
        assertEquals("one line of words", Digests.cut("one\n  line   of\twords", 50), "on one line");
        Statement statement = new Statement("s".repeat(400), "minutes-2026-05-06.md", 3);
        assertEquals("minutes-2026-05-06.md #3: " + "s".repeat(Digests.STATEMENT_CHARS) + "...", TextFormatterRegistry.format(statement));
    }

    @Test
    void aSplitCutsADocumentIntoTheStatementsAPersonWouldHighlight() throws Exception {
        ListArtifact<FileEntry> listing = listing();
        Document minutes = read(file(listing, "minutes-2026-05-06.md"));
        SplitStatementsTool split = new SplitStatementsTool(Job.workflow("test", "decide-tools"));
        split.setInput(minutes);
        ListArtifact<Statement> statements = JobDispatcher.getInstance().submit(split).get();
        assertEquals("statement", statements.getIterandTypeAlias());
        List<String> texts = statements.getIterands().stream().map(Statement::getText).toList();
        assertTrue(texts.contains("Decision: Meridian 3 and Kestrel 1 gravel retail and dealer prices go up by 20 percent from 1 June 2026, on the prices in force in May."), texts.toString());
        assertTrue(texts.contains("The Comet 2 is not affected: it carries no aluminium from Tai Han."), "a paragraph's sentences are statements of their own: " + texts);
        assertTrue(texts.contains("Anke: new price list on the portal, effective 1 June."), "a bullet is a statement: " + texts);
        assertFalse(texts.stream().anyMatch(t -> t.startsWith("#")), "headings are structure: " + texts);
        Statement first = statements.getIterands().get(0);
        assertEquals(1, first.getOrdinal());
        assertEquals("minutes-2026-05-06.md", first.getSource());
        assertTrue(TextFormatterRegistry.format(first).startsWith("minutes-2026-05-06.md #1: "), TextFormatterRegistry.format(first));
        assertEquals(List.of("One sentence here.", "Another one follows it!", "A bullet of its own"), SplitStatementsTool.split("# Title\n\nOne sentence here. Another one\nfollows it! Tiny.\n\n- A bullet of its own\n"));
    }
}
