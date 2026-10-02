/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tool door of {@link ToolHub}, the counterpart of the skill door: only the
 * reconciled catalog is admissible, hub admission rules judge with a reason the model can
 * read, the tool's OWN declared admission guardrails are consulted at the palette, a
 * guardrail's crash is reported as an internal error and never as a denial, an admitted
 * tool lands in the target registry, a name the registry already holds is admitted without
 * a second admission run, and a rule keyed to another thinker class does not fire.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public class ToolHubToolAdmissionTest {

    @BeforeAll
    static void startDispatcher() {
        // admission guardrails are jobs; nothing here ever calls a model
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void freshHub() {
        ToolHub.getInstance().resetAll();
        SelfGuardedTool.denials = 0;
    }

    private static Identifiable root(String user) {
        return Job.workflow(user, "tool-admission-test");
    }

    public static class ProbeInput {
        private String value;

        public String getValue() {return value;}

        public void setValue(String value) {this.value = value;}
    }

    /** A tool that carries its own admission verdict, consulted through a throwaway probe instance. */
    @ToolName("self_guarded")
    @ToolDescription(value = "Admits nobody, by its own declaration.", readOnly = true)
    public static class SelfGuardedTool extends AbstractTool<ProbeInput, String> {
        static int denials;

        public SelfGuardedTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public List<AdmissionGuardrail> declareAdmissionGuardrails() {
            return List.of(new AbstractAdmissionGuardrail(this) {
                @Override
                protected void checkAdmission() throws GuardrailException {
                    denials++;
                    throw new GuardrailException("this tool admits nobody");
                }
            });
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "never";
        }
    }

    /** A thinker whose catalog holds the clock, the date calculator and the self-guarded tool. */
    static class CatalogThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        CatalogThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected ToolSelector declareCompatibleTools() {
            return new ToolSelector(CurrentTimeTool.class, DateCalculatorTool.class, SelfGuardedTool.class);
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
    }

    /** The same catalog, narrowed by the thinker's own reconcile hook. */
    static class NarrowingThinker extends CatalogThinker {
        NarrowingThinker(Identifiable parent) {
            super(parent);
        }

        @Override
        protected Set<ToolProvider> reconcileCatalog(Set<ToolProvider> compatible) {
            Set<ToolProvider> narrowed = new LinkedHashSet<>();
            for (ToolProvider provider : compatible) {
                if (!provider.name().equals("calculate_dates")) {
                    narrowed.add(provider);
                }
            }
            return narrowed;
        }
    }

    static class DenyingGuard extends AbstractAdmissionGuardrail {
        DenyingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        protected void checkAdmission() throws GuardrailException {
            throw new GuardrailException("clearance " + getAdmissionContext().principal() + " does not hold");
        }
    }

    static class PassingGuard extends AbstractAdmissionGuardrail {
        static final List<AdmissionContext> seen = Collections.synchronizedList(new ArrayList<>());

        PassingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        protected void checkAdmission() {
            seen.add(getAdmissionContext());
        }
    }

    static class BrokenGuard extends AbstractAdmissionGuardrail {
        BrokenGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        protected void checkAdmission() {
            throw new IllegalStateException("guard is broken");
        }
    }

    @Test
    void aNameOffTheCatalogIsRejectedAsNotFound_andNothingIsRegistered() throws LLMReadableCheckedException {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, List.of("no_such_tool"), registry);
        assertTrue(result.getAdmitted().isEmpty());
        assertEquals("no_such_tool", result.getRejected().get(0).getName());
        assertTrue(result.getRejected().get(0).getReason().contains("not found in compatible tools catalog"));
        assertTrue(registry.getRegisteredToolNames().isEmpty(), "a rejected name registers nothing");
    }

    @Test
    void aCatalogToolIsAdmittedAndLandsInTheTargetRegistry() throws LLMReadableCheckedException {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(
                thinker, List.of("get_current_time", "calculate_dates"), registry);
        assertEquals(List.of("get_current_time", "calculate_dates"), result.getAdmitted());
        assertTrue(result.getRejected().isEmpty());
        assertTrue(registry.hasTool("get_current_time"), "admission registers the provider, so the schema rides the next turn");
        assertTrue(registry.hasTool("calculate_dates"));
    }

    @Test
    void aNameTheRegistryAlreadyHoldsIsAdmittedWithoutASecondAdmissionRun() throws LLMReadableCheckedException {
        PassingGuard.seen.clear();
        ToolHub.getInstance().registerAdmission(null, CurrentTimeTool.class, PassingGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ToolRegistry registry = new ToolRegistry();
        ToolHub.getInstance().requestTools(thinker, List.of("get_current_time"), registry);
        assertEquals(1, PassingGuard.seen.size(), "first admission runs the guard");
        RequestToolsResult again = ToolHub.getInstance().requestTools(thinker, List.of("get_current_time"), registry);
        assertEquals(List.of("get_current_time"), again.getAdmitted(), "already registered reads as admitted, not as an error");
        assertEquals(1, PassingGuard.seen.size(), "the guard does not run again for a tool already on the palette");
    }

    @Test
    void aDenyingHubRuleRejectsWithItsOwnReason_andOtherNamesPassUntouched() throws LLMReadableCheckedException {
        ToolHub.getInstance().registerAdmission(null, DateCalculatorTool.class, DenyingGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("visitor"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(
                thinker, List.of("calculate_dates", "get_current_time"), registry);
        assertEquals(List.of("get_current_time"), result.getAdmitted());
        String reason = result.getRejected().get(0).getReason();
        assertTrue(reason.startsWith("Access denied: "), reason);
        assertTrue(reason.contains("visitor"), "the guardrail judged the principal the admission context carried: " + reason);
        assertFalse(registry.hasTool("calculate_dates"), "a denied tool never reaches the registry");
    }

    @Test
    void theToolsOwnDeclaredAdmissionGuardrailIsConsultedAtThePalette() throws LLMReadableCheckedException {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, List.of("self_guarded"), registry);
        assertTrue(result.getAdmitted().isEmpty());
        assertTrue(result.getRejected().get(0).getReason().contains("admits nobody"),
                "the tool's own declaration denied it, without any hub rule: " + result.getRejected().get(0).getReason());
        assertTrue(SelfGuardedTool.denials >= 1, "the declaration was read off a throwaway probe instance");
        assertFalse(registry.hasTool("self_guarded"));
    }

    @Test
    void aGuardrailsOwnFaultIsAnInternalError_neverADenial() throws LLMReadableCheckedException {
        ToolHub.getInstance().registerAdmission(null, "get_current_*", BrokenGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, List.of("get_current_time"), registry);
        assertTrue(result.getAdmitted().isEmpty());
        String reason = result.getRejected().get(0).getReason();
        assertTrue(reason.contains("internal error"), reason);
        assertFalse(reason.contains("Access denied"), "a crash must not read as a verdict: " + reason);
    }

    @Test
    void aRuleKeyedToAnotherThinkerClassDoesNotFire_andTheContextCarriesTheToolClass() throws LLMReadableCheckedException {
        PassingGuard.seen.clear();
        ToolHub.getInstance().registerAdmission(NarrowingThinker.class, CurrentTimeTool.class, DenyingGuard::new);
        ToolHub.getInstance().registerAdmission(CatalogThinker.class, CurrentTimeTool.class, PassingGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("alice"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, List.of("get_current_time"), registry);
        assertEquals(List.of("get_current_time"), result.getAdmitted(),
                "the denying rule is keyed to a subclass this thinker is not");
        assertEquals(1, PassingGuard.seen.size());
        AdmissionContext context = PassingGuard.seen.get(0);
        assertEquals("alice", context.principal());
        assertEquals(CatalogThinker.class.getName(), context.callerClassName());
        assertEquals(CurrentTimeTool.class, context.toolClass(), "a tool has a class to name, unlike a skill");
    }

    @Test
    void theThinkersReconcileHookHasTheFinalSayOverTheCatalog() throws LLMReadableCheckedException {
        NarrowingThinker thinker = new NarrowingThinker(root("u1"));
        ToolRegistry registry = new ToolRegistry();
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, List.of("calculate_dates"), registry);
        assertTrue(result.getAdmitted().isEmpty());
        assertTrue(result.getRejected().get(0).getReason().contains("not found in compatible tools catalog"),
                "a tool the hook withheld is not in the catalog at all, so no guardrail is even consulted");
    }
}
