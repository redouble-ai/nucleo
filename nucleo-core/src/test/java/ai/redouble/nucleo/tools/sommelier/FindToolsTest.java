/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.tools.thinking.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a thinker is shown of its catalog, and what {@code find_tools} answers. A catalog
 * of {@link SommelierSettings#listedTools} tools or fewer is listed in {@code request_tools}
 * and the thinker has no {@code find_tools}; a longer one is not shown at all, and the
 * thinker asks the deployment's {@link Sommelier}. A scripted sommelier stands where the
 * model-backed one would, so no test calls a model.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class FindToolsTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void freshHub() {
        ToolHub.getInstance().resetAll();
    }

    @AfterAll
    static void restoreHub() {
        ToolHub.getInstance().resetAll();
    }

    private static Identifiable root() {
        return Job.workflow("u1", "find-tools-test");
    }

    /** A thinker whose catalog is whatever the test hands it. */
    static class CatalogThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        Set<ToolProvider> catalog;

        CatalogThinker(Set<ToolProvider> catalog, boolean readOnly) {
            super(root(), new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), readOnly);
            this.catalog = catalog;
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected ToolSelector declareCompatibleTools() {
            return new ToolSelector();
        }

        @Override
        protected Set<ToolProvider> reconcileCatalog(Set<ToolProvider> compatible) {
            return catalog;
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }

        ToolRegistry registry() {
            return toolRegistry;
        }

        Map<String, ToolDefinitionBlock> offered() {
            Map<String, ToolDefinitionBlock> blocks = new LinkedHashMap<>();
            for (ToolDefinitionBlock block : buildToolDefinitionBlocks()) {
                blocks.put(block.name(), block);
            }
            return blocks;
        }
    }

    /** Names the tools it was scripted to name, and keeps what it was asked. */
    static class ScriptedSommelier implements Sommelier {
        final List<String> names;
        String need;
        Collection<ToolProvider> catalog;

        ScriptedSommelier(String... names) {
            this.names = List.of(names);
        }

        @Override
        public Job<SommelierAnswer> pair(Identifiable parent, String need, Collection<ToolProvider> catalog) {
            this.need = need;
            this.catalog = catalog;
            return new SommelierFixtures.ScriptedCall(parent, new SommelierQuestion(), question -> names);
        }
    }

    static class DenyingGuard extends AbstractAdmissionGuardrail {
        DenyingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        protected void checkAdmission() throws GuardrailException {
            throw new GuardrailException("not for this user");
        }
    }

    private static FindToolsResult find(CatalogThinker thinker, String need) throws Exception {
        FindToolsInput input = new FindToolsInput();
        input.setNeed(need);
        FindToolsTool tool = new FindToolsTool(thinker);
        tool.setToolRegistry(thinker.registry());
        tool.setThinker(thinker);
        tool.setInput(input);
        return JobDispatcher.getInstance().submit(tool).get();
    }

    private static JsonNode nameItems(ToolDefinitionBlock requestTools) throws Exception {
        return NucleoJsonSerializer.readTree(requestTools.schemaJson()).path("properties").path(RequestToolsProvider.TOOL_NAMES).path("items");
    }

    @Test
    void theShippedLimitIsThirtyTools() {
        assertEquals(30, Settings.get(SommelierSettings.class).listedTools);
        assertEquals(50_000, Settings.get(SommelierSettings.class).partTokens);
    }

    @Test
    void aCatalogOfThirtyToolsIsListedInFull_andTheThinkerHasNoFindTools() throws Exception {
        Map<String, ToolDefinitionBlock> offered = new CatalogThinker(SommelierFixtures.catalog(30), false).offered();
        assertFalse(offered.containsKey(FindToolsTool.NAME), "a catalog the thinker can see needs no lookup");
        JsonNode oneOf = nameItems(offered.get(RequestToolsProvider.NAME)).path("oneOf");
        assertEquals(30, oneOf.size(), "every tool of the catalog, by name");
        assertEquals("tool_7", oneOf.get(6).path("const").asText());
        assertEquals("Describes entry number 7", oneOf.get(6).path("description").asText(), "each with its description");
    }

    @Test
    void aCatalogOfThirtyOneToolsIsNotShownAtAll_andTheThinkerHasFindTools() throws Exception {
        Map<String, ToolDefinitionBlock> offered = new CatalogThinker(SommelierFixtures.catalog(31), false).offered();
        assertTrue(offered.containsKey(FindToolsTool.NAME), "the lookup is how this thinker learns what exists");
        ToolDefinitionBlock requestTools = offered.get(RequestToolsProvider.NAME);
        JsonNode items = nameItems(requestTools);
        assertEquals("string", items.path("type").asText(), "a name is a plain string, checked against the catalog when it is requested");
        assertTrue(items.path("oneOf").isMissingNode(), "nothing is enumerated");
        for (ToolDefinitionBlock block : offered.values()) {
            // the catalog's names are tool_1 to tool_31; the field tool_names is not one of them
            assertFalse(java.util.regex.Pattern.compile("tool_\\d").matcher(block.schemaJson() + block.description()).find(),
                    "no definition names a single tool of the catalog, not the first thirty either: " + block.name());
            assertFalse(block.schemaJson().contains("Describes entry") || block.description().contains("Describes entry"),
                    "and none carries a catalog description: " + block.name());
        }
        assertTrue(requestTools.description().contains(FindToolsTool.NAME), "request_tools says where its names come from");
    }

    @Test
    void theLimitIsTheDeploymentsSetting() {
        SommelierSettings settings = Settings.get(SommelierSettings.class);
        int before = settings.listedTools;
        settings.listedTools = 2;
        try {
            assertFalse(new CatalogThinker(SommelierFixtures.catalog(2), false).offered().containsKey(FindToolsTool.NAME));
            assertTrue(new CatalogThinker(SommelierFixtures.catalog(3), false).offered().containsKey(FindToolsTool.NAME));
        }
        finally {
            settings.listedTools = before;
        }
    }

    @Test
    void aCatalogThatShrinksBackUnderTheLimitIsListedAgain() {
        CatalogThinker thinker = new CatalogThinker(SommelierFixtures.catalog(31), false);
        assertTrue(thinker.offered().containsKey(FindToolsTool.NAME));
        thinker.catalog = SommelierFixtures.catalog(5);
        assertFalse(thinker.offered().containsKey(FindToolsTool.NAME), "the palette follows the catalog turn by turn");
        thinker.catalog = Set.of();
        Map<String, ToolDefinitionBlock> offered = thinker.offered();
        assertFalse(offered.containsKey(FindToolsTool.NAME) || offered.containsKey(RequestToolsProvider.NAME), "an empty catalog offers neither tool");
    }

    @Test
    void aReadOnlyThinkerCannotActivateATool_soItIsGivenNoLookupEither() {
        Map<String, ToolDefinitionBlock> offered = new CatalogThinker(SommelierFixtures.catalog(31), true).offered();
        assertFalse(offered.containsKey(RequestToolsProvider.NAME), "request_tools changes the palette and is withheld from a read-only thinker");
        assertFalse(offered.containsKey(FindToolsTool.NAME), "a lookup whose result cannot be activated is not offered");
    }

    @Test
    void findToolsPutsTheNeedAndTheWholeCatalogToTheSommelier_andReturnsWhatItNamed() throws Exception {
        ScriptedSommelier sommelier = new ScriptedSommelier("tool_9", "tool_4");
        ToolHub.getInstance().setSommelier(sommelier);
        CatalogThinker thinker = new CatalogThinker(SommelierFixtures.catalog(31), false);
        FindToolsResult result = find(thinker, "cases reopened after closing");
        assertEquals("cases reopened after closing", sommelier.need, "the need reaches the sommelier in the model's own words");
        assertEquals(31, sommelier.catalog.size(), "with every tool on offer to this thinker");
        assertEquals(31, result.getCatalogSize(), "the answer says how many tools were read");
        assertEquals(List.of("tool_4", "tool_9"), result.getTools().stream().map(FindToolsResult.FoundTool::getName).toList());
        assertEquals("Describes entry number 4", result.getTools().get(0).getDescription(), "each tool comes with its description");
        assertFalse(result.getTools().get(0).isLoaded(), "and with whether the thinker already holds it");
    }

    @Test
    void aToolTheThinkerAlreadyHoldsIsMarkedLoaded() throws Exception {
        ToolHub.getInstance().setSommelier(new ScriptedSommelier("tool_4", "tool_9"));
        CatalogThinker thinker = new CatalogThinker(SommelierFixtures.catalog(31), false);
        ToolHub.getInstance().requestTools(thinker, List.of("tool_9"), thinker.registry());
        FindToolsResult result = find(thinker, "anything");
        assertFalse(result.getTools().get(0).isLoaded());
        assertTrue(result.getTools().get(1).isLoaded(), "tool_9 was activated before the lookup");
    }

    @Test
    void whenNoToolFitsTheAnswerIsAnEmptyListAndTheCatalogSize() throws Exception {
        ToolHub.getInstance().setSommelier(new ScriptedSommelier());
        FindToolsResult result = find(new CatalogThinker(SommelierFixtures.catalog(31), false), "something no tool does");
        assertEquals(List.of(), result.getTools(), "0 of 31 is a result, not an error");
        assertEquals(31, result.getCatalogSize());
    }

    @Test
    void aNameOutsideTheThinkersCatalogNeverReachesTheThinker() throws Exception {
        ToolHub.getInstance().setSommelier(new ScriptedSommelier("tool_4", "tool_99", "invented"));
        FindToolsResult result = find(new CatalogThinker(SommelierFixtures.catalog(31), false), "anything");
        assertEquals(List.of("tool_4"), result.getTools().stream().map(FindToolsResult.FoundTool::getName).toList(),
                "whatever a sommelier answers, only this thinker's catalog is named");
    }

    @Test
    void aLookupThatCannotBeCompletedIsAnErrorTheModelReads_neverAnEmptyAnswer() {
        ToolHub.getInstance().setSommelier((parent, need, catalog) -> new SommelierFixtures.ScriptedCall(parent, new SommelierQuestion(), question -> {
            throw new IllegalStateException("the model is down");
        }));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> find(new CatalogThinker(SommelierFixtures.catalog(31), false), "anything"));
        assertInstanceOf(LLMReadableCheckedException.class, failure.getCause());
    }

    @Test
    void aBlankNeedIsRefusedBeforeAnySommelierIsAsked() {
        ScriptedSommelier sommelier = new ScriptedSommelier("tool_4");
        ToolHub.getInstance().setSommelier(sommelier);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> find(new CatalogThinker(SommelierFixtures.catalog(31), false), "  "));
        assertInstanceOf(InvalidInputException.class, failure.getCause(), "a correctable refusal: the model states its need and calls again");
        assertNull(sommelier.need, "nothing was looked up");
    }

    @Test
    void findToolsNamesAToolAndAdmitsNone_admissionStillDecidesAtRequestTools() throws Exception {
        ToolHub.getInstance().setSommelier(new ScriptedSommelier("tool_4"));
        ToolHub.getInstance().registerAdmission(null, CurrentTimeTool.class, DenyingGuard::new);
        CatalogThinker thinker = new CatalogThinker(SommelierFixtures.catalog(31), false);
        assertEquals("tool_4", find(thinker, "anything").getTools().get(0).getName(), "the lookup names the tool");
        assertFalse(thinker.registry().hasTool("tool_4"), "and puts nothing in the palette");
        RequestToolsResult requested = ToolHub.getInstance().requestTools(thinker, List.of("tool_4"), thinker.registry());
        assertEquals("tool_4", requested.getRejected().get(0).getName(), "the admission rule refuses it as it would a listed tool");
        assertFalse(thinker.registry().hasTool("tool_4"));
    }

    @Test
    void aNameTheCatalogDoesNotHoldIsRejectedByRequestToolsInTheUnlistedFormToo() throws Exception {
        CatalogThinker thinker = new CatalogThinker(SommelierFixtures.catalog(31), false);
        RequestToolsProvider unlisted = (RequestToolsProvider) thinkerProvider(thinker);
        assertFalse(unlisted.listed());
        RequestToolsInput parsed = (RequestToolsInput) unlisted.parseInput(
                NucleoJsonSerializer.readTree("{\"" + RequestToolsProvider.TOOL_NAMES + "\": [\"tool_4\", \"invented\"]}"));
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, parsed.getToolNames(), thinker.registry());
        assertEquals(List.of("tool_4"), result.getAdmitted());
        assertEquals("invented", result.getRejected().get(0).getName());
        assertTrue(result.getRejected().get(0).getReason().contains("not found in compatible tools catalog"));
    }

    private static ToolProvider thinkerProvider(CatalogThinker thinker) {
        thinker.offered();
        return thinker.registry().getProviderByName(RequestToolsProvider.NAME);
    }

    @Test
    void theHubStartsWithTheModelSommelier_andADeploymentReplacesIt() {
        assertInstanceOf(ModelSommelier.class, ToolHub.getInstance().getSommelier());
        ScriptedSommelier own = new ScriptedSommelier();
        ToolHub.getInstance().setSommelier(own);
        assertSame(own, ToolHub.getInstance().getSommelier());
    }
}
