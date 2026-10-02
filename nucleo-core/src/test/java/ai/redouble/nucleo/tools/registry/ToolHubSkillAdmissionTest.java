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
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The skill door of {@link ToolHub}, pinned to the same promises {@code request_tools} keeps:
 * only the reconciled catalog is admissible, admission guardrails decide with a reason the
 * model can read, a guardrail's own fault is reported as such and never as a denial, an
 * admitted skill rides the conversation from then on, and asking twice attaches once.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class ToolHubSkillAdmissionTest {
    private static final Skill CITE = SkillFixtures.skill("hub.cite", "How to cite.", "ai.example.hub");
    private static final Skill BRIEF = SkillFixtures.skill("hub.brief", "Be brief.", "ai.example.hub");
    private static final Skill SECRET = SkillFixtures.skill("hub.secret", "Restricted.", "ai.example.restricted");

    @BeforeAll
    static void startDispatcher() {
        // admission guardrails are jobs; nothing here ever calls a model
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void freshHub() {
        ToolHub.getInstance().resetAll();
        SkillRegistry.register(CITE);
        SkillRegistry.register(BRIEF);
        SkillRegistry.register(SECRET);
    }

    private static Identifiable root(String user) {
        return Job.workflow(user, "skill-admission-test");
    }

    /** A thinker whose catalog is the three fixtures; nothing else about it matters here. */
    static class CatalogThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        CatalogThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected SkillSelector declareCompatibleSkills() {
            return new SkillSelector("hub.cite", "hub.brief", "hub.secret");
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

    /** The same catalog, but the thinker's own reconcile withholds the restricted one. */
    static class NarrowingThinker extends CatalogThinker {
        NarrowingThinker(Identifiable parent) {
            super(parent);
        }

        @Override
        protected Set<Skill> reconcileSkillCatalog(Set<Skill> compatible) {
            Set<Skill> narrowed = new LinkedHashSet<>();
            for (Skill skill : compatible) {
                if (!skill.name().equals("hub.secret")) {
                    narrowed.add(skill);
                }
            }
            return narrowed;
        }
    }

    /** A thinker that declares no skills at all. */
    static class SkilllessThinker extends CatalogThinker {
        SkilllessThinker(Identifiable parent) {
            super(parent);
        }

        @Override
        protected SkillSelector declareCompatibleSkills() {
            return null;
        }
    }

    static class DenyingGuard extends AbstractAdmissionGuardrail {
        DenyingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        protected void checkAdmission() throws GuardrailException {
            throw new GuardrailException("restricted skills need clearance " + getAdmissionContext().principal() + " does not hold");
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
    void aNameOffTheCatalogIsRejectedAsNotFound() throws LLMReadableCheckedException {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.unknown"), conversation);
        assertTrue(result.getAdmitted().isEmpty());
        assertEquals(1, result.getRejected().size());
        assertEquals("hub.unknown", result.getRejected().get(0).getName());
        assertTrue(result.getRejected().get(0).getReason().contains("not found in compatible skills catalog"));
        assertTrue(conversation.getLoadedSkills().isEmpty(), "nothing was attached");
    }

    @Test
    void aCatalogSkillIsAdmittedAndRidesTheConversation() throws LLMReadableCheckedException {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.cite", "hub.brief"), conversation);
        assertEquals(List.of("hub.cite", "hub.brief"), result.getAdmitted());
        assertTrue(result.getRejected().isEmpty());
        List<String> loaded = conversation.getLoadedSkills().stream().map(Skill::name).toList();
        assertEquals(List.of("hub.cite", "hub.brief"), loaded, "admission attaches the skill to the conversation, in request order");
        assertTrue(Skill.renderForSystemPrompt(conversation.getLoadedSkills().get(0)).startsWith("[Skill: hub.cite]"),
                "what rides the preamble is the skill's own rendering");
    }

    @Test
    void askingTwiceAttachesOnce_andIsStillReportedAdmitted() throws LLMReadableCheckedException {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ConversationContext conversation = new ConversationContext();
        ToolHub.getInstance().requestSkills(thinker, List.of("hub.cite"), conversation);
        RequestSkillResult again = ToolHub.getInstance().requestSkills(thinker, List.of("hub.cite", "hub.cite"), conversation);
        assertEquals(List.of("hub.cite", "hub.cite"), again.getAdmitted(), "already loaded reads as admitted, not as an error");
        assertEquals(1, conversation.getLoadedSkills().size(), "the conversation holds it once");
    }

    @Test
    void aDenyingGuardrailRejectsWithItsOwnReason_andAttachesNothing() throws LLMReadableCheckedException {
        ToolHub.getInstance().registerSkillAdmission(null, "hub.secret", DenyingGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("visitor"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.secret", "hub.cite"), conversation);
        assertEquals(List.of("hub.cite"), result.getAdmitted(), "the rule matched one name; the other passed untouched");
        assertEquals(1, result.getRejected().size());
        String reason = result.getRejected().get(0).getReason();
        assertTrue(reason.startsWith("Access denied: "), reason);
        assertTrue(reason.contains("visitor"), "the guardrail judged the principal the admission context carried: " + reason);
        assertEquals(List.of("hub.cite"), conversation.getLoadedSkills().stream().map(Skill::name).toList());
    }

    @Test
    void aGuardrailsOwnFaultIsAnInternalError_neverADenial() throws LLMReadableCheckedException {
        ToolHub.getInstance().registerSkillAdmission(null, "hub.*", BrokenGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.cite"), conversation);
        assertTrue(result.getAdmitted().isEmpty());
        String reason = result.getRejected().get(0).getReason();
        assertTrue(reason.contains("internal error"), reason);
        assertFalse(reason.contains("Access denied"), "a crash must not read as a verdict: " + reason);
        assertTrue(conversation.getLoadedSkills().isEmpty());
    }

    @Test
    void aRuleForAnotherThinkerClassDoesNotApply_andAPassingGuardSeesTheContext() throws LLMReadableCheckedException {
        PassingGuard.seen.clear();
        ToolHub.getInstance().registerSkillAdmission(NarrowingThinker.class, "hub.*", DenyingGuard::new);
        ToolHub.getInstance().registerSkillAdmission(CatalogThinker.class, "hub.cite", PassingGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("alice"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.cite"), conversation);
        assertEquals(List.of("hub.cite"), result.getAdmitted(),
                "the denying rule is keyed to a different thinker class and must not fire here");
        assertEquals(1, PassingGuard.seen.size(), "the rule keyed to this class ran once");
        AdmissionContext context = PassingGuard.seen.get(0);
        assertEquals("alice", context.principal());
        assertEquals(CatalogThinker.class.getName(), context.callerClassName());
        assertNull(context.toolClass(), "a skill is content; it has no tool class to name");
    }

    @Test
    void aPredicateRuleOverTheSkillItselfApplies() throws LLMReadableCheckedException {
        ToolHub.getInstance().registerSkillAdmission(null,
                skill -> "ai.example.restricted".equals(skill.metadata().getBundleId()), DenyingGuard::new);
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.secret", "hub.brief"), conversation);
        assertEquals(List.of("hub.brief"), result.getAdmitted());
        assertEquals("hub.secret", result.getRejected().get(0).getName());
    }

    @Test
    void theThinkersReconcileHookHasTheFinalSayOverTheCatalog() throws LLMReadableCheckedException {
        NarrowingThinker thinker = new NarrowingThinker(root("u1"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, List.of("hub.secret"), conversation);
        assertTrue(result.getAdmitted().isEmpty());
        assertTrue(result.getRejected().get(0).getReason().contains("not found in compatible skills catalog"),
                "a skill the hook withheld is not in the catalog at all, so no guardrail is even consulted");
    }

    @Test
    void theCompatibleSetIsCachedPerClass_andWidenedByHotRegistration() {
        CatalogThinker thinker = new CatalogThinker(root("u1"));
        Set<Skill> first = ToolHub.getInstance().resolveCompatibleSkills(thinker);
        assertEquals(3, first.size());
        Skill late = SkillFixtures.skill("hub.late", "Arrived later.", "ai.example.hub");
        ToolHub.getInstance().registerCompatibleSkills(CatalogThinker.class, late);
        Set<Skill> widened = ToolHub.getInstance().resolveCompatibleSkills(new CatalogThinker(root("u2")));
        assertTrue(widened.contains(late), "hot registration widens the cached set for every instance of the class");
        assertEquals(4, widened.size());
    }

    @Test
    void aThinkerDeclaringNoSkillsHasAnEmptyCatalog() {
        assertTrue(ToolHub.getInstance().resolveCompatibleSkills(new SkilllessThinker(root("u1"))).isEmpty());
    }
}
