/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.mcp.server.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.guardrails.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a tool, its input and its output tell a model when a deployment's
 * {@link AnnotationResolver} holds something for an annotation, and when it holds nothing.
 * A stored {@code value} replaces the code's text, stored attributes replace the code's
 * attributes one by one, whatever is not stored is the code's, and what the annotation
 * cannot take is refused by key. The store restates only what the code carries: an
 * annotation the code does not have is not asked for and is not added. A map stands where a
 * deployment's store would.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
public class AnnotationsTest {
    private static final String TOOL = "ai.redouble.nucleo.harness.annotations.AnnotationsTest$CaseSearchTool";
    private static final String INPUT = "ai.redouble.nucleo.harness.annotations.AnnotationsTest$CaseSearchInput";
    private static final String BASE = "ai.redouble.nucleo.harness.annotations.AnnotationsTest$PagedInput";
    private static final String OUTPUT = "ai.redouble.nucleo.harness.annotations.AnnotationsTest$CaseSearchResult";
    private static final Identifiable TEST_ROOT = Job.workflow("u1", "annotations-test");

    /** What every paged search takes; its field is inherited by each search's input. */
    public static class PagedInput {
        @LLMDescription("The largest number of rows to return")
        private Long limit;

        public Long getLimit() {
            return limit;
        }

        public void setLimit(Long limit) {
            this.limit = limit;
        }
    }

    @LLMDescription("Which cases to find")
    public static class CaseSearchInput extends PagedInput {
        @LLMDescription("Words to look for in the case title")
        @LLMExample({"refund", "late delivery"})
        private String title;
        @ai.redouble.nucleo.harness.DisplayName("Case status")
        private String status;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class CaseSearchResult {
        @LLMDescription("How many cases matched")
        private Long total;

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }
    }

    @MCP
    @ToolName("search_cases")
    @LLMDescription("Search the support cases")
    @ReadOnly
    @ai.redouble.nucleo.harness.DisplayName(value = "Case Search", action = "Searching cases")
    @ToolWeight(type = ToolType.API_CALL, min = 5, max = 15)
    public static class CaseSearchTool extends AbstractTool<CaseSearchInput, CaseSearchResult> {
        public CaseSearchTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public CaseSearchResult execute(JobResources resources, JobContext<CaseSearchResult> context) {
            return new CaseSearchResult();
        }
    }

    /** A tool still written with the deprecated annotation. */
    @SuppressWarnings("removal")
    @ToolName("legacy_tool")
    @ToolDescription(value = "Search the old way", readOnly = true)
    public static class LegacyTool extends BareTool {
        public LegacyTool(Identifiable parent) {
            super(parent);
        }
    }

    /** A tool that carries both descriptions, the deprecated one not read-only. */
    @SuppressWarnings("removal")
    @ToolName("both_tool")
    @ToolDescription("Described the old way")
    @LLMDescription("Described the new way")
    public static class BothTool extends BareTool {
        public BothTool(Identifiable parent) {
            super(parent);
        }
    }

    /** A tool that declares nothing but its name. */
    @ToolName("bare_tool")
    public static class BareTool extends AbstractTool<CaseSearchInput, CaseSearchResult> {
        public BareTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public CaseSearchResult execute(JobResources resources, JobContext<CaseSearchResult> context) {
            return new CaseSearchResult();
        }
    }

    /** A deployment's store: what it holds by key, and what it was asked. */
    private static final class Store implements AnnotationResolver {
        private final Map<String, AnnotationOverride> rows = new HashMap<>();
        private final List<Collection<String>> asked = new ArrayList<>();

        private void text(String key, String value) {
            AnnotationOverride override = new AnnotationOverride();
            override.setValue(value);
            rows.put(key, override);
        }

        private void attributes(String key, String json) throws Exception {
            AnnotationOverride override = rows.computeIfAbsent(key, k -> new AnnotationOverride());
            override.setAttributes(NucleoJsonSerializer.readTree(json));
        }

        @Override
        public Map<String, AnnotationOverride> resolve(Collection<String> keys) {
            asked.add(new ArrayList<>(keys));
            Map<String, AnnotationOverride> answer = new HashMap<>();
            for (String key : keys) {
                if (rows.containsKey(key)) {
                    answer.put(key, rows.get(key));
                }
            }
            return answer;
        }
    }

    private Store store;

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void freshStore() {
        store = new Store();
        Annotations.setResolver(store);
    }

    @AfterEach
    void noStore() {
        Annotations.clearResolver();
    }

    private static JsonNode inputSchema(ToolProvider provider) throws Exception {
        return NucleoJsonSerializer.readTree(provider.schemaJson());
    }

    private static String fieldText(ToolProvider provider, String field) throws Exception {
        return inputSchema(provider).get("properties").get(field).get("description").asText();
    }

    private static List<String> examples(ToolProvider provider, String field) throws Exception {
        List<String> examples = new ArrayList<>();
        for (JsonNode example : inputSchema(provider).get("properties").get(field).get("examples")) {
            examples.add(example.asText());
        }
        return examples;
    }

    @Test
    void withNoResolverAToolIsDescribedByItsCode() throws Exception {
        Annotations.clearResolver();
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Search the support cases", provider.description());
        assertTrue(provider.readOnly());
        assertEquals("Case Search", provider.displayName());
        assertEquals("Searching cases", provider.actionVerb());
        assertEquals("Which cases to find", inputSchema(provider).get("description").asText());
        assertTrue(fieldText(provider, "title").startsWith("Words to look for in the case title"));
        assertEquals(List.of("refund", "late delivery"), examples(provider, "title"));
    }

    @Test
    void aKeyTheStoreDoesNotHoldReadsAsTheCode() throws Exception {
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Search the support cases", provider.description());
        assertTrue(provider.readOnly());
        assertTrue(fieldText(provider, "title").startsWith("Words to look for in the case title"));
        assertFalse(store.asked.isEmpty(), "the store was asked");
    }

    @Test
    void storedTextReplacesTheDescriptionAndLeavesTheAttributes() {
        store.text(TOOL + "@LLMDescription", "Search the support cases, open and closed");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Search the support cases, open and closed", provider.description());
        assertTrue(provider.readOnly(), "readOnly is not stored, so it is the code's");
    }

    @Test
    void storedAttributesReplaceTheAttributesAndLeaveTheText() throws Exception {
        store.attributes(TOOL + "@DisplayName", "{\"action\": \"Looking up cases\"}");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Search the support cases", provider.description());
        assertTrue(provider.readOnly());
        assertEquals("Case Search", provider.displayName());
        assertEquals("Looking up cases", provider.actionVerb());
    }

    @Test
    void storedTextAndAttributesBothApply() throws Exception {
        store.text(TOOL + "@DisplayName", "Support Cases");
        store.attributes(TOOL + "@DisplayName", "{\"action\": \"Reading cases\"}");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Support Cases", provider.displayName());
        assertEquals("Reading cases", provider.actionVerb());
        assertEquals("Support Cases", JobSnapshot.extractDisplayName(CaseSearchTool.class));
        assertEquals("Reading cases", JobSnapshot.extractAction(CaseSearchTool.class));
    }

    @Test
    void anAnnotationTheCodeDoesNotCarryIsNotAddedByTheStore() throws Exception {
        String bare = BareTool.class.getName();
        store.text(bare + "@ToolDescription", "Does the bare thing");
        store.attributes(bare + "@ToolDescription", "{\"readOnly\": true}");
        store.text(bare + "@DisplayName", "Bare");
        store.attributes(bare + "@ToolWeight", "{\"type\": \"THINKER\", \"min\": 30, \"max\": 60}");
        ClassToolProvider provider = ClassToolProvider.of(BareTool.class);
        assertEquals("Tool: bare_tool", provider.description());
        assertFalse(provider.readOnly(), "the tool carries no @ToolDescription, so the stored one is not there");
        assertEquals("BareTool", provider.displayName());
        assertSame(DefaultToolWeights.API_CALL_DEFAULT, provider.weight());
        for (Collection<String> keys : store.asked) {
            assertFalse(keys.stream().anyMatch(key -> key.startsWith(bare + "@")), "the store is not asked for what the code does not carry: " + keys);
        }
    }

    @Test
    void aStoredWeightReplacesTheCodesAttributeByAttribute() throws Exception {
        store.attributes(TOOL + "@ToolWeight", "{\"type\": \"THINKER\", \"min\": 30}");
        ToolWeight weight = ClassToolProvider.of(CaseSearchTool.class).weight();
        assertEquals(ToolType.THINKER, weight.type());
        assertEquals(30, weight.min());
        assertEquals(15, weight.max(), "max is not stored, so it is the code's");
        assertEquals(0, weight.level());
    }

    @Test
    void storedTextDescribesAFieldAndAType() throws Exception {
        store.text(INPUT + "@LLMDescription", "Which support cases to find");
        store.text(INPUT + ".title@LLMDescription", "Words in the title. Customers call a case a ticket");
        store.text(OUTPUT + ".total@LLMDescription", "The number of matching cases in the whole store");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Which support cases to find", inputSchema(provider).get("description").asText());
        assertTrue(fieldText(provider, "title").startsWith("Words in the title. Customers call a case a ticket"));
        assertEquals(List.of("refund", "late delivery"), examples(provider, "title"), "the examples are not stored, so they are the code's");
        JsonNode output = NucleoJsonSerializer.readTree(provider.outputSchemaJson());
        assertEquals("The number of matching cases in the whole store", output.get("properties").get("total").get("description").asText());
    }

    @Test
    void aFieldOrClassTheCodeDoesNotDescribeIsNotDescribedByTheStore() throws Exception {
        store.text(INPUT + ".status@LLMDescription", "The status the agent last set");
        store.text(INPUT + ".status@LLMExample", "open\nclosed");
        store.text(OUTPUT + "@LLMDescription", "What a search of cases found");
        store.text("some.random.Clazz@LLMDescription", "Not a class of this process");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Field status", fieldText(provider, "status"));
        assertNull(inputSchema(provider).get("properties").get("status").get("examples"));
        assertEquals("Schema of CaseSearchResult", NucleoJsonSerializer.readTree(provider.outputSchemaJson()).get("description").asText());
    }

    @Test
    void storedExamplesAreOnePerLine() throws Exception {
        store.text(INPUT + ".title@LLMExample", "reopened\n\n  double charge  \n");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals(List.of("reopened", "double charge"), examples(provider, "title"));
    }

    @Test
    void anInheritedFieldIsDescribedOnceForEveryClassThatInheritsIt() throws Exception {
        store.text(BASE + ".limit@LLMDescription", "How many rows at most");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("How many rows at most", fieldText(provider, "limit"));
    }

    @Test
    void anInheritedFieldIsRewordedForOneClassAheadOfTheSharedWording() throws Exception {
        store.text(BASE + ".limit@LLMDescription", "How many rows at most");
        store.text(INPUT + ".limit@LLMDescription", "How many cases at most");
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("How many cases at most", fieldText(provider, "limit"));
        assertEquals("How many rows at most", PojoResponseHandler.generateSchema(PagedInput.class).getFields().get("limit").getDescription());
    }

    @Test
    void anAttributeTheAnnotationDoesNotHaveIsRefusedByKey() throws Exception {
        store.attributes(TOOL + "@DisplayName", "{\"Action\": \"Looking\"}");
        AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
        assertEquals(TOOL + "@DisplayName", refused.getKey());
        assertTrue(refused.getMessage().contains("[action, value]"), refused.getMessage());
    }

    @Test
    void anAttributeOfTheWrongTypeIsRefusedWithoutRepeatingIt() throws Exception {
        store.attributes(TOOL + "@ToolWeight", "{\"min\": \"canary-yes\"}");
        AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
        assertTrue(refused.getMessage().contains("a whole number"), refused.getMessage());
        assertFalse(refused.getMessage().contains("canary-yes"), refused.getMessage());
    }

    @Test
    void aToolWrittenWithTheDeprecatedAnnotationIsReadAsBefore() {
        ClassToolProvider provider = ClassToolProvider.of(LegacyTool.class);
        assertEquals("Search the old way", provider.description());
        assertTrue(provider.readOnly());
        assertEquals("Search the old way", JobSnapshot.extractDescription(LegacyTool.class));
        store.text(LegacyTool.class.getName() + "@ToolDescription", "Search the old way, reworded");
        Annotations.changed();
        assertEquals("Search the old way, reworded", provider.description());
    }

    @Test
    void aToolWithBothDescriptionsIsDescribedByTheNewOne() {
        ClassToolProvider provider = ClassToolProvider.of(BothTool.class);
        assertEquals("Described the new way", provider.description());
        assertFalse(provider.readOnly(), "neither annotation says read-only");
        assertEquals("Described the new way", JobSnapshot.extractDescription(BothTool.class));
    }

    @Test
    void readOnlyIsDecidedInTheCodeAndAStoredOneIsRefused() throws Exception {
        assertTrue(ClassToolProvider.of(CaseSearchTool.class).readOnly(), "@ReadOnly marks it");
        assertFalse(ClassToolProvider.of(BareTool.class).readOnly());
        String key = LegacyTool.class.getName() + "@ToolDescription";
        store.attributes(key, "{\"readOnly\": false}");
        AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(LegacyTool.class));
        assertEquals(key, refused.getKey());
        assertTrue(refused.getMessage().contains("decided in the code"), refused.getMessage());
    }

    @Test
    void aFieldsDisplayNameIsTheTitleOfItsSchema() throws Exception {
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        JsonNode properties = inputSchema(provider).get("properties");
        assertEquals("Case status", properties.get("status").get("title").asText());
        assertNull(properties.get("title").get("title"), "a field without a display name has no title");
        store.text(INPUT + ".status@DisplayName", "Ticket status");
        Annotations.changed();
        assertEquals("Ticket status", inputSchema(provider).get("properties").get("status").get("title").asText());
    }

    @Test
    void aValueAmongTheAttributesIsRefused() throws Exception {
        store.attributes(TOOL + "@LLMDescription", "{\"value\": \"Search\"}");
        AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
        assertTrue(refused.getMessage().contains("apart from the attributes"), refused.getMessage());
    }

    @Test
    void attributesThatAreNotAnObjectAreRefused() throws Exception {
        store.attributes(TOOL + "@LLMDescription", "[true]");
        assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
    }

    @Test
    void aStoredValueForAnAnnotationWithoutOneIsRefused() {
        store.text(TOOL + "@ToolWeight", "heavy");
        AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
        assertTrue(refused.getMessage().contains("no text value"), refused.getMessage());
    }

    @Test
    void aRefusedFieldOverrideIsNotDegradedToAToolWithoutASchema() throws Exception {
        store.attributes(INPUT + ".title@LLMDescription", "{\"length\": 5}");
        AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
        assertEquals(INPUT + ".title@LLMDescription", refused.getKey());
    }

    @Test
    void storedTextAGuardrailRefusesIsRefusedByKey() {
        java.util.function.Supplier<Guardrail<Prompt>> cap = () -> new SizeCapGuardrail(TEST_ROOT, 10);
        Prompts.addBaselineGuardrail(cap);
        try {
            store.text(TOOL + "@LLMDescription", "A description far longer than ten characters");
            AnnotationOverrideException refused = assertThrows(AnnotationOverrideException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
            assertEquals(TOOL + "@LLMDescription", refused.getKey());
            assertInstanceOf(GuardrailException.class, refused.getCause());
        }
        finally {
            // the registry is process-wide: a cap left behind would refuse every later prompt of this fork
            Prompts.removeBaselineGuardrail(cap);
        }
    }

    @Test
    void aResolverThatAnswersNullIsADefect() {
        Annotations.setResolver(keys -> null);
        assertThrows(IllegalStateException.class, () -> ClassToolProvider.of(CaseSearchTool.class));
    }

    @Test
    void theStoreIsAskedOncePerClassWithEveryKeyOfIt() {
        ClassToolProvider.of(CaseSearchTool.class);
        int afterFirst = store.asked.size();
        ClassToolProvider.of(CaseSearchTool.class);
        assertEquals(afterFirst, store.asked.size(), "a second provider asks nothing: what was answered is held");
        Collection<String> forInput = store.asked.stream().filter(keys -> keys.contains(INPUT + ".title@LLMDescription")).findFirst().orElseThrow();
        assertTrue(forInput.contains(INPUT + ".title@LLMExample"));
        assertFalse(forInput.contains(INPUT + ".status@LLMDescription"), "status carries no description in the code, so none is asked for");
        assertTrue(forInput.contains(INPUT + ".limit@LLMDescription"));
        assertTrue(forInput.contains(BASE + ".limit@LLMDescription"));
        assertTrue(forInput.contains(INPUT + "@LLMDescription"));
    }

    @Test
    void aChangedStoreReachesAProviderThatAlreadyExists() {
        ClassToolProvider provider = ClassToolProvider.of(CaseSearchTool.class);
        assertEquals("Search the support cases", provider.description());
        store.text(TOOL + "@LLMDescription", "Search every support case");
        assertEquals("Search the support cases", provider.description(), "held until the deployment says the store changed");
        long before = Annotations.generation();
        Annotations.changed();
        assertTrue(Annotations.generation() > before);
        assertEquals("Search every support case", provider.description());
        Annotations.clearResolver();
        assertEquals("Search the support cases", provider.description());
    }

    @Test
    void theKeysOfAToolListWhatTheCodeDeclares() {
        Map<String, AnnotationKey> keys = new LinkedHashMap<>();
        for (AnnotationKey key : ClassToolProvider.of(CaseSearchTool.class).annotationKeys()) {
            assertNull(keys.put(key.key(), key), "listed twice: " + key.key());
        }
        AnnotationKey tool = keys.get(TOOL + "@LLMDescription");
        assertEquals("Search the support cases", tool.value());
        assertNull(tool.attributes());
        assertEquals("Case status", keys.get(INPUT + ".status@DisplayName").value());
        assertFalse(keys.keySet().stream().anyMatch(key -> key.endsWith("@ReadOnly")), "a marker has nothing to store");
        assertEquals("Searching cases", keys.get(TOOL + "@DisplayName").attributes().get("action").asText());
        assertEquals("Which cases to find", keys.get(INPUT + "@LLMDescription").value());
        assertEquals("Words to look for in the case title", keys.get(INPUT + ".title@LLMDescription").value());
        assertEquals("refund\nlate delivery", keys.get(INPUT + ".title@LLMExample").value());
        assertNull(keys.get(INPUT + ".title@LLMDescription").attributes());
        assertFalse(keys.containsKey(INPUT + ".status@LLMDescription"), "what the code does not carry cannot be overridden, so it is not listed");
        assertEquals("The largest number of rows to return", keys.get(BASE + ".limit@LLMDescription").value());
        assertFalse(keys.containsKey(INPUT + ".limit@LLMDescription"), "an inherited field is listed under the class that declares it");
        assertEquals("How many cases matched", keys.get(OUTPUT + ".total@LLMDescription").value());
        assertFalse(keys.containsKey(OUTPUT + "@LLMDescription"));
        assertEquals("API_CALL", keys.get(TOOL + "@ToolWeight").attributes().get("type").asText());
        assertEquals(15, keys.get(TOOL + "@ToolWeight").attributes().get("max").intValue());
        assertNull(keys.get(TOOL + "@ToolWeight").value());
    }

    @Test
    void aToolServedOverMcpCarriesTheStoredText() throws Exception {
        store.text(TOOL + "@LLMDescription", "Search the support cases, open and closed");
        store.text(INPUT + ".title@LLMDescription", "Words in the title");
        McpToolCatalog catalog = new McpToolCatalog(List.of(), List.of(ClassToolProvider.of(CaseSearchTool.class)));
        ToolProvider served = catalog.byName("search_cases");
        assertEquals("Search the support cases, open and closed", served.description());
        assertTrue(fieldText(served, "title").startsWith("Words in the title"));
        assertEquals("Case status", inputSchema(served).get("properties").get("status").get("title").asText());
        McpSchema.Tool published = McpSchemaPublisher.publish(served, SchemaDialect.CANONICAL);
        assertEquals("Case Search", published.title());
        assertEquals("Search the support cases, open and closed", published.description());
        assertTrue(published.annotations().readOnlyHint());
    }
}
