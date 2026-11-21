/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the drill-down half of the artifact system - the tools the LLM is told about
 * every time a field renders as {@code [SUMMARY: N chars]}: {@code get_artifact_field}
 * returns the FULL text of a summarized field (snake_case echo of a camelCase field
 * included, chunked by offset). Failures are typed refusals: an invented reference is a
 * closed, correctable {@code ResourceNotFoundException} - never somebody else's artifact -
 * and a name that is no field of the artifact is an {@code InvalidInputException} listing
 * the addressable fields. {@code search_artifact_content} finds a needle across all
 * registered artifacts' string fields and reports where; its floors (maxResults under one,
 * negative contextChars) are refused rather than clamped. {@code read_rows} returns a run
 * of the rows a held list already has, counted from 1 and ten by default, after which the
 * model is shown them in full; a run past the end returns the rows there are, and a
 * reference that is no list, a floor under one or a first row past the end is refused. Any
 * of the three dispatched without its registry is a {@code SystemException} - the caller's
 * wiring fault, never a result the model reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class ArtifactAccessToolsTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("artifact-access-test", "artifact-access-test");
    }

    private static ArtifactRegistry registryWith(WebPageArtifact... artifacts) {
        ArtifactRegistry registry = TestModels.conversation(TestModels.small()).getArtifactRegistry();
        for (WebPageArtifact artifact : artifacts) {
            registry.register(artifact);
        }
        return registry;
    }

    private static WebPageArtifact page(String title, String content) {
        WebPageArtifact artifact = new WebPageArtifact();
        artifact.setTitle(title);
        artifact.setUrl("https://example.com/" + title);
        artifact.setContent(content);
        return artifact;
    }

    @Test
    void fullFieldContentIsRetrievable_bySnakeCaseEcho_withChunking() throws Exception {
        WebPageArtifact artifact = page("long", "NEEDLE-" + "x".repeat(30_000));
        ArtifactRegistry registry = registryWith(artifact);

        GetArtifactFieldTool tool = new GetArtifactFieldTool(root());
        tool.setArtifactRegistry(registry);
        GetArtifactFieldInput input = new GetArtifactFieldInput();
        input.setArtifactRef(artifact.getArtifactRef());
        input.setFieldName("content");
        tool.setInput(input);
        GetArtifactFieldOutput out = JobDispatcher.getInstance().submit(tool).get();
        assertEquals(30_007, out.getLength(), "the length names the FULL field, not the chunk");
        assertTrue(out.isTruncated(), "a 30K field does not fit the default 10K chunk");
        assertTrue(out.getContent().startsWith("NEEDLE-"), "the first chunk starts at the beginning");

        GetArtifactFieldTool offsetTool = new GetArtifactFieldTool(root());
        offsetTool.setArtifactRegistry(registry);
        GetArtifactFieldInput offsetInput = new GetArtifactFieldInput();
        offsetInput.setArtifactRef(artifact.getArtifactRef());
        offsetInput.setFieldName("content");
        offsetInput.setOffset(30_000);
        offsetTool.setInput(offsetInput);
        GetArtifactFieldOutput tail = JobDispatcher.getInstance().submit(offsetTool).get();
        assertEquals("xxxxxxx", tail.getContent(), "offset chunking reaches the tail exactly");
        assertFalse(tail.isTruncated());
    }

    @Test
    void anInventedReferenceIsAClosedTypedRefusal() {
        ArtifactRegistry registry = registryWith(page("real", "content"));
        GetArtifactFieldTool tool = new GetArtifactFieldTool(root());
        tool.setArtifactRegistry(registry);
        GetArtifactFieldInput input = new GetArtifactFieldInput();
        input.setArtifactRef("«artifact:link:web~ffffff»");
        input.setFieldName("content");
        tool.setInput(input);
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get(),
                "a hallucinated ref must never resolve to some other artifact's content");
        assertInstanceOf(ai.redouble.nucleo.harness.errors.ResourceNotFoundException.class, failure.getCause(),
                "a fetch by an id that does not exist is not-found, which the model can act on");
    }

    @Test
    void anUnknownFieldIsRefusedNamingTheAddressableFields() {
        WebPageArtifact artifact = page("real", "content");
        ArtifactRegistry registry = registryWith(artifact);
        GetArtifactFieldTool tool = new GetArtifactFieldTool(root());
        tool.setArtifactRegistry(registry);
        GetArtifactFieldInput input = new GetArtifactFieldInput();
        input.setArtifactRef(artifact.getArtifactRef());
        input.setFieldName("no_such_field");
        tool.setInput(input);
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get());
        assertInstanceOf(ai.redouble.nucleo.harness.errors.InvalidInputException.class, failure.getCause(),
                "the model named the field and can pick another");
        assertTrue(failure.getCause().getMessage().contains("content"),
                "the refusal lists the fields the artifact actually has: " + failure.getCause().getMessage());
    }

    @Test
    void aFieldThatExistsAndHoldsNullIsContentlessOutput_notARefusal() throws Exception {
        WebPageArtifact artifact = page("nulled", "body");
        artifact.setDescription(null);
        ArtifactRegistry registry = registryWith(artifact);
        GetArtifactFieldTool tool = new GetArtifactFieldTool(root());
        tool.setArtifactRegistry(registry);
        GetArtifactFieldInput input = new GetArtifactFieldInput();
        input.setArtifactRef(artifact.getArtifactRef());
        input.setFieldName("description");
        tool.setInput(input);
        GetArtifactFieldOutput out = JobDispatcher.getInstance().submit(tool).get();
        assertNull(out.getContent(), "null is what the data is - never substituted, never refused");
        assertEquals(0, out.getLength());
    }

    @Test
    void searchFindsTheNeedleAcrossArtifacts_andNamesWhereItLives() throws Exception {
        WebPageArtifact hit = page("hit", "prelude UNIQUE-NEEDLE postlude");
        WebPageArtifact miss = page("miss", "nothing to see here");
        ArtifactRegistry registry = registryWith(hit, miss);

        SearchArtifactContentTool tool = new SearchArtifactContentTool(root());
        tool.setArtifactRegistry(registry);
        SearchArtifactContentInput input = new SearchArtifactContentInput();
        input.setQuery("UNIQUE-NEEDLE");
        tool.setInput(input);
        SearchArtifactContentOutput out = JobDispatcher.getInstance().submit(tool).get();
        assertNotNull(out.getMatches());
        assertEquals(1, out.getMatches().size(), "exactly the artifact that carries the needle matches");
        SearchMatch match = out.getMatches().get(0);
        assertEquals(hit.getArtifactRef(), match.getArtifactRef(), "the match names its artifact by ref");
        assertTrue(match.getSnippet().contains("UNIQUE-NEEDLE"), "the snippet shows the needle in context");
    }

    @Test
    void searchWithNoHitsIsAValidEmptyResult() throws Exception {
        ArtifactRegistry registry = registryWith(page("only", "nothing relevant"));
        SearchArtifactContentTool tool = new SearchArtifactContentTool(root());
        tool.setArtifactRegistry(registry);
        SearchArtifactContentInput input = new SearchArtifactContentInput();
        input.setQuery("ABSENT-TERM");
        tool.setInput(input);
        SearchArtifactContentOutput out = JobDispatcher.getInstance().submit(tool).get();
        assertTrue(out.getMatches() == null || out.getMatches().isEmpty(),
                "search finding nothing is a valid result, never an error");
    }

    @Test
    void theFloorsAreRefusedRatherThanClamped() {
        ArtifactRegistry registry = registryWith(page("only", "content"));
        SearchArtifactContentInput zeroResults = new SearchArtifactContentInput();
        zeroResults.setQuery("content");
        zeroResults.setMaxResults(0);
        assertSearchRefused(registry, zeroResults, "maxResults",
                "a maxResults under one would end the walk before it starts and lie 'found nothing'");
        SearchArtifactContentInput negativeContext = new SearchArtifactContentInput();
        negativeContext.setQuery("content");
        negativeContext.setContextChars(-1);
        assertSearchRefused(registry, negativeContext, "contextChars",
                "a negative contextChars would invert the snippet window");
    }

    private static void assertSearchRefused(ArtifactRegistry registry, SearchArtifactContentInput input, String parameter, String why) {
        SearchArtifactContentTool tool = new SearchArtifactContentTool(root());
        tool.setArtifactRegistry(registry);
        tool.setInput(input);
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get(), why);
        assertInstanceOf(ai.redouble.nucleo.harness.errors.InvalidInputException.class, failure.getCause(), why);
        assertTrue(failure.getCause().getMessage().contains(parameter),
                "the refusal names the parameter the model can fix: " + failure.getCause().getMessage());
    }

    private static ListArtifact<WebPageArtifact> registeredList(ArtifactRegistry registry, int rows) {
        java.util.List<WebPageArtifact> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            pages.add(page("row-" + i, "content of row " + i));
        }
        ListArtifact<WebPageArtifact> list = new ListArtifact<>();
        list.setIterands(pages);
        list.setIterandTypeAlias("link:page");
        registry.register(list);
        return list;
    }

    private static ReadRowsOutput readRows(ArtifactRegistry registry, String list, Long fromRow, Long howMany) throws Exception {
        ReadRowsTool tool = new ReadRowsTool(root());
        tool.setArtifactRegistry(registry);
        ReadRowsInput input = new ReadRowsInput();
        input.setList(list);
        input.setFromRow(fromRow);
        input.setHowMany(howMany);
        tool.setInput(input);
        return JobDispatcher.getInstance().submit(tool).get();
    }

    private static void assertReadRowsRefused(ArtifactRegistry registry, String list, Long fromRow, Long howMany,
                                              Class<? extends Exception> refusal, String named, String why) {
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> readRows(registry, list, fromRow, howMany), why);
        assertInstanceOf(refusal, failure.getCause(), why);
        assertTrue(failure.getCause().getMessage().contains(named),
                "the refusal names what the model can fix: " + failure.getCause().getMessage());
    }

    @Test
    void readRowsReturnsTheRowsNamed_andTheModelIsThenShownThemInFull() throws Exception {
        ArtifactRegistry registry = registryWith();
        ListArtifact<WebPageArtifact> list = registeredList(registry, 20);
        ReadRowsOutput out = readRows(registry, list.getArtifactRef(), 5L, 3L);
        assertEquals(20L, out.getRowsInList(), "the output says how many rows the list holds");
        assertEquals(5L, out.getFromRow());
        assertEquals(3, out.getRows().size());
        assertSame(list.getIterands().get(4), out.getRows().get(0), "row 5 is the list's own fifth row, counted from 1");
        assertSame(list.getIterands().get(6), out.getRows().get(2));
        // What a thinker does with a tool's result: the rows become top-level entries, which is
        // what puts them before the model in full instead of behind the list's digest
        assertFalse(registry.getAllArtifacts().containsValue(list.getIterands().get(4)), "before the read, row 5 is held behind its list");
        NucleoJsonSerializer.writeSummarizedWithRefs(out, registry);
        assertTrue(registry.getAllArtifacts().containsValue(list.getIterands().get(4)), "after the read, row 5 is a top-level entry");
        assertEquals(list.getIterands().get(4).getArtifactRef(), out.getRows().get(0).getArtifactRef(), "a row keeps the reference it had in the list");
        assertFalse(registry.getAllArtifacts().containsValue(list.getIterands().get(7)), "a row that was not read stays behind the list");
    }

    @Test
    void readRowsDefaultsToTheFirstTen_andARunPastTheEndReturnsTheRowsThereAre() throws Exception {
        ArtifactRegistry registry = registryWith();
        ListArtifact<WebPageArtifact> list = registeredList(registry, 20);
        ReadRowsOutput first = readRows(registry, list.getArtifactRef(), null, null);
        assertEquals(1L, first.getFromRow());
        assertEquals(ReadRowsTool.DEFAULT_HOW_MANY, first.getRows().size(), "with no count named, ten rows are read");
        assertSame(list.getIterands().get(0), first.getRows().get(0));
        ReadRowsOutput tail = readRows(registry, list.getArtifactRef(), 16L, 10L);
        assertEquals(5, tail.getRows().size(), "the next ten at the tail of a list are the five rows left");
        assertSame(list.getIterands().get(19), tail.getRows().get(4));
    }

    @Test
    void readRowsRefusesWhatNamesNoRows() {
        WebPageArtifact single = page("single", "content");
        ArtifactRegistry registry = registryWith(single);
        ListArtifact<WebPageArtifact> list = registeredList(registry, 20);
        assertReadRowsRefused(registry, "«artifact:list~ffffff»", null, null, ai.redouble.nucleo.harness.errors.ResourceNotFoundException.class,
                "ffffff", "an invented reference is not-found");
        assertReadRowsRefused(registry, single.getArtifactRef(), null, null, ai.redouble.nucleo.harness.errors.InvalidInputException.class,
                "list", "a single artifact has no rows");
        assertReadRowsRefused(registry, list.getArtifactRef(), 0L, null, ai.redouble.nucleo.harness.errors.InvalidInputException.class,
                "fromRow", "rows are counted from 1");
        assertReadRowsRefused(registry, list.getArtifactRef(), null, 0L, ai.redouble.nucleo.harness.errors.InvalidInputException.class,
                "howMany", "a count under one reads nothing");
        assertReadRowsRefused(registry, list.getArtifactRef(), 21L, null, ai.redouble.nucleo.harness.errors.InvalidInputException.class,
                "20", "a first row past the end is refused naming how many rows the list holds");
    }

    @Test
    void aToolDispatchedWithoutTheRegistryIsAWiringFault_notAModelReadableResult() {
        java.util.concurrent.ExecutionException rowsFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> readRows(null, "«artifact:list~ffffff»", null, null));
        assertInstanceOf(ai.redouble.nucleo.harness.errors.SystemException.class, rowsFailure.getCause(),
                "read_rows fails the same way without its registry");
        GetArtifactFieldTool get = new GetArtifactFieldTool(root());
        GetArtifactFieldInput getInput = new GetArtifactFieldInput();
        getInput.setArtifactRef("«artifact:link:web~ffffff»");
        getInput.setFieldName("content");
        get.setInput(getInput);
        java.util.concurrent.ExecutionException getFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(get).get());
        assertInstanceOf(ai.redouble.nucleo.harness.errors.SystemException.class, getFailure.getCause(),
                "no thinker injected the registry: a fault of the caller, not of the model");

        SearchArtifactContentTool search = new SearchArtifactContentTool(root());
        SearchArtifactContentInput searchInput = new SearchArtifactContentInput();
        searchInput.setQuery("anything");
        search.setInput(searchInput);
        java.util.concurrent.ExecutionException searchFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(search).get());
        assertInstanceOf(ai.redouble.nucleo.harness.errors.SystemException.class, searchFailure.getCause(),
                "search fails the same way - never an empty result that hides the missing wiring");
    }
}
