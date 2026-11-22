/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.registry.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How {@link ModelDrivenSommelier} reads a catalog: what a model is shown of a tool, how a long
 * catalog is cut into parts, which calls a lookup makes and what it answers from them, and
 * which model the calls ask for. A scripted job stands where each model call would, so the
 * tests pin the lookup's own behaviour and call no model.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class SommelierLookupTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("u1", "sommelier-lookup-test");
    }

    /** A lookup whose calls are scripted, and which keeps every question it put. */
    static class ScriptedLookup extends SommelierLookup {
        final List<SommelierQuestion> asked = Collections.synchronizedList(new ArrayList<>());
        private final Function<SommelierQuestion, List<String>> script;

        ScriptedLookup(Map<String, String> pages, Function<SommelierQuestion, List<String>> script) {
            super(root());
            this.script = script;
            SommelierQuestion question = new SommelierQuestion();
            question.setNeed("the need");
            question.setTools(pages);
            setInput(question);
        }

        @Override
        protected Job<SommelierAnswer> call(String need, Map<String, String> tools) {
            SommelierQuestion question = new SommelierQuestion();
            question.setNeed(need);
            question.setTools(tools);
            asked.add(question);
            return new SommelierFixtures.ScriptedCall(this, question, script);
        }
    }

    /** Pages of 400 characters, 100 tokens each as the lookup counts them. */
    private static Map<String, String> pages(String... names) {
        Map<String, String> pages = new LinkedHashMap<>();
        for (String name : names) {
            pages.put(name, name + " ".repeat(400 - name.length()));
        }
        return pages;
    }

    /** Names every tool it is shown whose name starts with the prefix. */
    private static Function<SommelierQuestion, List<String>> naming(String prefix) {
        return question -> question.getTools().keySet().stream().filter(name -> name.startsWith(prefix)).toList();
    }

    private static List<String> run(SommelierLookup lookup) throws Exception {
        return JobDispatcher.getInstance().submit(lookup).get().getFitting();
    }

    private static <T> T withPartTokens(int partTokens, Callable<T> body) throws Exception {
        SommelierSettings settings = Settings.get(SommelierSettings.class);
        int before = settings.partTokens;
        settings.partTokens = partTokens;
        try {
            return body.call();
        }
        finally {
            settings.partTokens = before;
        }
    }

    @Test
    void aToolPageHoldsTheNameTheDescriptionAndTheWordsOfBothSchemas_andNoSchemaStructure() throws IOException {
        String page = ToolPage.render("search_cases", "Search the support cases",
                "{\"type\":\"object\",\"required\":[\"status\"],\"properties\":{\"status\":{\"type\":\"string\",\"description\":\"Case status\",\"enum\":[\"open\",\"closed\"]}}}",
                "{\"type\":\"object\",\"properties\":{\"reopened_at\":{\"type\":\"string\",\"title\":\"When it was reopened\"}}}");
        assertEquals("search_cases\nSearch the support cases\nInput:\nstatus\nCase status\nopen\nclosed\nOutput:\nreopened_at\nWhen it was reopened\n", page,
                "field names, descriptions, titles and enumerated values, one per line; type and required are structure and stay out");
    }

    @Test
    void aToolWithNoOutputSchemaHasNoOutputSection() throws IOException {
        String page = ToolPage.render("ping", "Pings", "{\"type\":\"object\",\"properties\":{}}", null);
        assertEquals("ping\nPings\nInput:\n", page);
    }

    @Test
    void aCatalogThatFitsOnePartIsNotCut() {
        List<Map<String, String>> parts = SommelierLookup.cut(pages("a", "b", "c"), 300);
        assertEquals(1, parts.size(), "300 tokens of pages fit a part of 300");
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(parts.get(0).keySet()));
    }

    @Test
    void aLongerCatalogIsCutIntoTheFewestPartsOfEvenSize_inTheCatalogsOrder() {
        List<Map<String, String>> parts = SommelierLookup.cut(pages("a", "b", "c", "d", "e", "f"), 250);
        assertEquals(3, parts.size(), "600 tokens at 250 a part need three parts");
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e", "f")),
                parts.stream().map(part -> List.copyOf(part.keySet())).toList(),
                "three parts of two pages each, never two full parts and a remainder");
    }

    @Test
    void aPageLongerThanAPartIsAPartByItself() {
        Map<String, String> pages = pages("a", "b");
        pages.put("huge", "x".repeat(4000));
        pages.putAll(pages("c"));
        List<Map<String, String>> parts = SommelierLookup.cut(pages, 300);
        assertTrue(parts.contains(Map.of("huge", "x".repeat(4000))), "the oversized page is not dropped and shares its part with nothing: " + parts.stream().map(Map::keySet).toList());
        assertEquals(4, parts.stream().mapToInt(Map::size).sum(), "every page is in exactly one part");
    }

    @Test
    void aCatalogInOnePartIsAnsweredByOneCallThatSeesEveryTool() throws Exception {
        ScriptedLookup lookup = new ScriptedLookup(pages("fit_a", "other_b", "fit_c"), naming("fit_"));
        assertEquals(List.of("fit_a", "fit_c"), withPartTokens(50_000, () -> run(lookup)));
        assertEquals(1, lookup.asked.size(), "one part, one call, and no second reading of what it named");
        assertEquals(Set.of("fit_a", "other_b", "fit_c"), lookup.asked.get(0).getTools().keySet());
        assertEquals("the need", lookup.asked.get(0).getNeed());
    }

    @Test
    void aCatalogInSeveralPartsIsReadPartByPart_thenOneCallReadsWhatThePartsNamed() throws Exception {
        // every part names its "maybe_" and "fit_" tools; the last call, shown them side by side, keeps "fit_"
        Function<SommelierQuestion, List<String>> script = question -> question.getTools().keySet().stream()
                .filter(name -> question.getTools().size() == 2 ? !name.startsWith("other_") : name.startsWith("fit_"))
                .toList();
        ScriptedLookup lookup = new ScriptedLookup(pages("fit_a", "other_b", "maybe_c", "other_d", "maybe_e", "fit_f"), script);
        // the last call is told apart from a part by what it holds: four named tools, where a part holds two
        assertEquals(List.of("fit_a", "fit_f"), withPartTokens(250, () -> run(lookup)));
        assertEquals(4, lookup.asked.size(), "three parts and the last call");
        assertEquals(Set.of("fit_a", "maybe_c", "maybe_e", "fit_f"), lookup.asked.get(3).getTools().keySet(),
                "the last call reads exactly the tools the parts named, with their pages");
        Set<String> readInParts = new HashSet<>();
        for (SommelierQuestion part : lookup.asked.subList(0, 3)) {
            readInParts.addAll(part.getTools().keySet());
        }
        assertEquals(6, readInParts.size(), "every tool of the catalog was read by a part");
    }

    @Test
    void whenThePartsNameOneToolThereIsNothingToCompare_andNoLastCall() throws Exception {
        ScriptedLookup lookup = new ScriptedLookup(pages("other_a", "other_b", "fit_c", "other_d"), naming("fit_"));
        assertEquals(List.of("fit_c"), withPartTokens(250, () -> run(lookup)));
        assertEquals(2, lookup.asked.size(), "two parts and nothing after them");
    }

    @Test
    void whenNoToolFitsTheAnswerIsAnEmptyList() throws Exception {
        ScriptedLookup lookup = new ScriptedLookup(pages("other_a", "other_b"), naming("fit_"));
        assertEquals(List.of(), withPartTokens(50_000, () -> run(lookup)), "read, and nothing fits: an empty list, which is an answer");
    }

    @Test
    void aNameTheCatalogDoesNotHoldIsDropped() throws Exception {
        ScriptedLookup lookup = new ScriptedLookup(pages("fit_a", "other_b"), question -> List.of("fit_a", "invented_tool"));
        assertEquals(List.of("fit_a"), withPartTokens(50_000, () -> run(lookup)));
    }

    @Test
    void aCallThatFailsFailsTheLookup_itNeverAnswersFromThePartsThatWorked() {
        Function<SommelierQuestion, List<String>> script = question -> {
            if (question.getTools().containsKey("other_d")) {
                throw new IllegalStateException("the model is down");
            }
            return List.copyOf(question.getTools().keySet());
        };
        ScriptedLookup lookup = new ScriptedLookup(pages("fit_a", "fit_b", "fit_c", "other_d"), script);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> withPartTokens(250, () -> run(lookup)));
        assertInstanceOf(LLMReadableCheckedException.class, failure.getCause(), "the failure reaches the asking model as one it can read");
    }

    @Test
    void aCallAsksForTheSmallGrade_andForTheRoleWhenTheDeploymentNamesOne() {
        SommelierQuestion question = new SommelierQuestion();
        question.setNeed("the need");
        question.setTools(pages("a"));
        SommelierCall graded = new SommelierCall(root());
        graded.setInput(question);
        ModelBinding gradedSeat = graded.getRequirements().getModelBindings().getFirst();
        assertEquals(Grade.SMALL, gradedSeat.getGrade());
        assertNull(gradedSeat.getRole(), "no role unless the deployment names one");
        ModelRole role = new ModelRole("tool-lookup", "some-catalog-entry", Grade.MEDIUM);
        SommelierCall inRole = new SommelierCall(root());
        inRole.setInput(question);
        inRole.setRole(role);
        assertEquals(role, inRole.getRequirements().getModelBindings().getFirst().getRole(),
                "the seat is the role's: its entry serves the call and its grade stands in");
    }

    @Test
    void theModelDrivenSommelierPutsEveryToolsPageToItsLookup_andHandsItsRoleOn() throws Exception {
        ModelRole role = new ModelRole("tool-lookup", "some-catalog-entry", Grade.SMALL);
        ModelDrivenSommelier sommelier = new ModelDrivenSommelier();
        sommelier.setRole(role);
        Set<ToolProvider> catalog = SommelierFixtures.catalog(3);
        SommelierLookup lookup = (SommelierLookup) sommelier.pair(root(), "the need", catalog);
        assertEquals(role, lookup.getRole());
        assertEquals("the need", lookup.getInput().getNeed());
        assertEquals(List.of("tool_1", "tool_2", "tool_3"), List.copyOf(lookup.getInput().getTools().keySet()));
        assertEquals("tool_2\nDescribes entry number 2\nInput:\nquery_of_tool_2\nWhat to look for\n", lookup.getInput().getTools().get("tool_2"),
                "the page is the tool in full, as ToolPage writes it");
        assertNull(((SommelierLookup) new ModelDrivenSommelier().pair(root(), "the need", catalog)).getRole(), "no role by default");
    }
}
