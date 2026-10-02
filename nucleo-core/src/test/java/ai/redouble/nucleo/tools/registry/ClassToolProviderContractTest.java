/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The contracts of {@link ClassToolProvider} and {@link ToolRegistry} that every palette
 * rests on: a second registration under an existing name upserts when it is the same
 * provider identity and throws on a different provider type; {@code createTool} on an
 * unknown name, a nameless call included, is a correctable refusal naming the available
 * tools; a created
 * {@code ModelDependent} tool with no grade of its own inherits its parent's; a tool class
 * without the {@code (Identifiable)} constructor fails with a message naming the
 * constructor it must declare; a parse refusal names the offending field and the declared
 * type, never the value the model sent; and a declared {@link SchemaRefinedBy} refiner is
 * applied to the published schema, while a refiner that fails prevents the provider from
 * existing at all.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ClassToolProviderContractTest {
    private static final String CANARY = "zq7CanaryValue";

    private static Identifiable root() {
        return Job.workflow("provider-contract-test", "provider-contract-test");
    }

    public static class CountedInput {
        @LLMDescription("How many")
        private Integer count;

        public Integer getCount() {return count;}

        public void setCount(Integer count) {this.count = count;}
    }

    public static class Confirmation {
        private String note;

        public String getNote() {return note;}

        public void setNote(String note) {this.note = note;}
    }

    @ToolName("counted_probe")
    @ToolDescription(value = "Counts a thing.", readOnly = true)
    public static class CountedTool extends AbstractTool<CountedInput, Confirmation> {
        public CountedTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public Confirmation execute(JobResources resources, JobContext<Confirmation> context) {
            return new Confirmation();
        }
    }

    /** A model-dependent tool that learns its grade later - the shape the inheritance rule exists for. */
    @ToolName("graded_probe")
    @ToolDescription(value = "Answers at whatever grade it inherited.", readOnly = true)
    public static class GradedTool extends AbstractModelDependentTool<CountedInput, Confirmation> {
        public GradedTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public Confirmation execute(JobResources resources, JobContext<Confirmation> context) {
            throw new UnsupportedOperationException("never executed here");
        }
    }

    /** No (Identifiable) constructor - the contract violation the factory must name. */
    @ToolName("ctorless_probe")
    @ToolDescription("Cannot be constructed by the framework.")
    public static class CtorlessTool extends AbstractTool<CountedInput, Confirmation> {
        public CtorlessTool() {
            super(null);
        }

        @Override
        public Confirmation execute(JobResources resources, JobContext<Confirmation> context) {
            return null;
        }
    }

    /** Writes a runtime value set into the schema, the way a table's columns would be. */
    public static class ColumnsRefiner implements SchemaRefiner {
        @Override
        public void refine(Class<?> inputType, ObjectNode inputSchema) {
            ObjectNode count = (ObjectNode) inputSchema.path("properties").path("count");
            count.putArray("enum").add(1).add(2).add(3);
        }
    }

    @ToolName("refined_probe")
    @ToolDescription(value = "Accepts only the resolved values.", readOnly = true)
    @SchemaRefinedBy(ColumnsRefiner.class)
    public static class RefinedTool extends CountedTool {
        public RefinedTool(Identifiable parent) {
            super(parent);
        }
    }

    public static class BrokenRefiner implements SchemaRefiner {
        @Override
        public void refine(Class<?> inputType, ObjectNode inputSchema) {
            throw new IllegalStateException("the value set could not be resolved");
        }
    }

    @ToolName("broken_refined_probe")
    @ToolDescription("Declares a refiner that cannot do its work.")
    @SchemaRefinedBy(BrokenRefiner.class)
    public static class BrokenRefinedTool extends CountedTool {
        public BrokenRefinedTool(Identifiable parent) {
            super(parent);
        }
    }

    /** No no-arg constructor - the provider cannot even build this refiner. */
    public static class UnbuildableRefiner implements SchemaRefiner {
        public UnbuildableRefiner(String needsAnArgument) {
        }

        @Override
        public void refine(Class<?> inputType, ObjectNode inputSchema) {
        }
    }

    @ToolName("unbuildable_refined_probe")
    @ToolDescription("Declares a refiner the provider cannot construct.")
    @SchemaRefinedBy(UnbuildableRefiner.class)
    public static class UnbuildableRefinedTool extends CountedTool {
        public UnbuildableRefinedTool(Identifiable parent) {
            super(parent);
        }
    }

    @Test
    void sameProviderIdentityUpserts_aDifferentProviderTypeUnderTheSameNameThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ClassToolProvider.of(CountedTool.class));
        registry.register(ClassToolProvider.of(CountedTool.class));
        assertEquals(1, registry.getAllProviders().size(), "re-registering the same class is an upsert, not a duplicate");
        IllegalStateException collision = assertThrows(IllegalStateException.class,
                () -> registry.register(new NamedImposter("counted_probe")),
                "a different provider type claiming an existing name is a collision, surfaced early");
        assertTrue(collision.getMessage().contains("counted_probe"), collision.getMessage());
    }

    /** A second provider type wearing an existing name, for the collision check. */
    private static final class NamedImposter implements ToolProvider {
        private final String name;

        NamedImposter(String name) {
            this.name = name;
        }

        @Override
        public String name() {return name;}

        @Override
        public String description() {return "imposter";}

        @Override
        public String schemaJson() {return "{}";}

        @Override
        public ToolWeight weight() {return DefaultToolWeights.API_CALL_DEFAULT;}

        @Override
        public String displayName() {return name;}

        @Override
        public String actionVerb() {return "";}

        @Override
        public Class<?> inputType() {return CountedInput.class;}

        @Override
        public Class<? extends Tool> toolClass() {return CountedTool.class;}

        @Override
        public Object parseInput(com.fasterxml.jackson.databind.JsonNode raw) {
            return new CountedInput();
        }

        @Override
        public Tool<?, ?> create(Identifiable parent) {
            return new CountedTool(parent);
        }
    }

    @Test
    void anUnknownNameIsACorrectableRefusalNamingTheAvailableTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(CountedTool.class);
        CorrectableRuntimeLLMException refusal = assertThrows(CorrectableRuntimeLLMException.class,
                () -> registry.createTool("never_registered", root()),
                "the model picked a name off the palette and can pick another");
        assertTrue(refusal.getMessage().contains("never_registered"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("counted_probe"),
                "the refusal lists what IS available, so the correction is possible: " + refusal.getMessage());
    }

    @Test
    void aNullNameIsJustAnUnknownName_refusedCorrectably_neverANullKeyCrash() {
        // Names arrive from model output, and a model on a wire surface it barely speaks
        // can emit a call with no name at all. The registry's backing map throws on a null
        // key, so the lookup seam must answer null as unregistered before the map sees it.
        ToolRegistry registry = new ToolRegistry();
        registry.register(CountedTool.class);
        assertNull(registry.getProviderByName(null), "a null name is never registered");
        CorrectableRuntimeLLMException refusal = assertThrows(CorrectableRuntimeLLMException.class,
                () -> registry.createTool(null, root()),
                "a nameless call is refused the way an unknown name is, so the model can correct it");
        assertTrue(refusal.getMessage().contains("counted_probe"),
                "the refusal lists what IS available: " + refusal.getMessage());
    }

    @Test
    void aCreatedModelDependentToolInheritsTheParentsGradeWhenItHasNone() throws Exception {
        GradedTool parent = new GradedTool(root());
        parent.setGrade(Grade.MEDIUM);
        Tool<?, ?> child = ClassToolProvider.of(GradedTool.class).create(parent);
        assertEquals(Grade.MEDIUM, ((ModelDependent) child).getGrade(),
                "a seatless tool created under a graded parent works at the parent's rung");
        GradedTool opinionated = new GradedTool(root());
        opinionated.setGrade(Grade.XL);
        Tool<?, ?> kept = ClassToolProvider.of(GradedTool.class).create(opinionated);
        ((ModelDependent) kept).setGrade(Grade.SMALL);
        assertEquals(Grade.SMALL, ((ModelDependent) kept).getGrade(),
                "a grade set on the instance stands - inheritance only fills the empty seat");
    }

    @Test
    void aToolWithoutTheIdentifiableConstructorFailsNamingTheConstructor() {
        SystemException failure = assertThrows(SystemException.class,
                () -> ClassToolProvider.of(CtorlessTool.class).create(root()),
                "the constructor contract is the factory's to enforce");
        assertTrue(failure.getMessage().contains("CtorlessTool(Identifiable parent)"),
                "the failure names the constructor the tool must declare: " + failure.getMessage());
    }

    @Test
    void aParseRefusalNamesTheFieldAndTheDeclaredType_neverTheValue() throws Exception {
        ClassToolProvider provider = ClassToolProvider.of(CountedTool.class);
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.put("count", CANARY);
        InvalidInputException refusal = assertThrows(InvalidInputException.class, () -> provider.parseInput(raw));
        assertEquals("count", refusal.getParameterName(),
                "the offending field comes from the mapping failure's own reference chain");
        assertFalse(refusal.getMessage().contains(CANARY),
                "the refusal is composed from our field and our type, never from what the model sent: "
                        + refusal.getMessage());
        assertFalse(refusal.getLLMMessage().contains(CANARY),
                "the model-facing text is composed the same way: " + refusal.getLLMMessage());
        assertNotNull(refusal.getCause(),
                "the decoder's complaint survives on the cause, where only a stack trace carries it");
        assertThrows(InvalidInputException.class, () -> provider.parseInput(null), "null input is a correctable refusal");
    }

    @Test
    void aDeclaredRefinerNarrowsThePublishedSchema() {
        String schema = ClassToolProvider.of(RefinedTool.class).schemaJson();
        assertTrue(schema.contains("enum"), "the refiner's value set is written into the one published schema: " + schema);
        String unrefined = ClassToolProvider.of(CountedTool.class).schemaJson();
        assertFalse(unrefined.contains("enum"), "a tool without a refiner publishes the generated schema as is");
    }

    @Test
    void aRefinerThatFailsPreventsTheProvider() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ClassToolProvider.of(BrokenRefinedTool.class),
                "a tool must not publish the schema that invites the guessing its refiner exists to end");
        assertTrue(failure.getMessage().contains("the value set could not be resolved"),
                "the refiner's own failure propagates as itself, never degrading to the unrefined schema: "
                        + failure.getMessage());
    }

    @Test
    void aRefinerTheProviderCannotConstructPreventsTheProvider_namingTheRefiner() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ClassToolProvider.of(UnbuildableRefinedTool.class),
                "a refiner needs the public no-argument constructor the provider builds it through");
        assertTrue(failure.getMessage().contains("UnbuildableRefiner"),
                "the failure names the refiner the tool declared: " + failure.getMessage());
    }
}
