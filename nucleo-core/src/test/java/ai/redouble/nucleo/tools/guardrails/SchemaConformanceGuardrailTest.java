/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The structural gate in front of a Skill, which is the only check the framework performs on
 * a bundle it did not author.
 *
 * <p>The line it draws is the one {@code ai.redouble.nucleo.prompt.skill}'s documentation draws:
 * structure is refused, semantics are not claimed. A Skill without a name or without a body
 * cannot be rendered into a system prompt, so it is refused. A Skill whose suggested tools
 * this process has never heard of is admitted with a warning, because a bundle may ship
 * ahead of the tools it expects and refusing it would make publication order a hard
 * dependency. Nothing here reads the body: prompt-injection resistance is not a promise this
 * guardrail makes, and a test that pretended otherwise would document a protection that
 * does not exist.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class SchemaConformanceGuardrailTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "schema-conformance-test");

    /** {@code Prompts.buildPrompt} is package-private, and the gate reads only presence, so the record serves. */
    private static Prompt text(String key, String content) {
        return new TextPrompt(key, TextNode.valueOf(content));
    }

    private static Skill skill(String name, Prompt body, List<String> suggestedTools) {
        return new TextSkill(name, text("desc", "what it is for"), body, Map.of(), suggestedTools,
                new SkillMetadata());
    }

    private static Skill wellFormed() {
        return skill("test.well-formed", text("body", "the instructions themselves"), List.of());
    }

    private GuardrailException refusalFor(Skill target) {
        SchemaConformanceGuardrail guard = new SchemaConformanceGuardrail(TEST_ROOT);
        return assertThrows(GuardrailException.class, () -> guard.validate(target),
                "a Skill that cannot be rendered must be refused rather than admitted");
    }

    @Test
    void aNullSkillIsRefused() {
        assertTrue(refusalFor(null).getMessage().contains("null"),
                "nothing to validate is a refusal, never a silent pass");
    }

    @Test
    void aSkillWithoutAUsableNameIsRefused() {
        assertTrue(refusalFor(skill(null, text("body", "x"), List.of())).getMessage().contains("name"),
                "the name is the registry key, so a missing one is refused by naming it");
        assertTrue(refusalFor(skill("", text("body", "x"), List.of())).getMessage().contains("name"),
                "an empty name is refused the same way as a missing one");
        assertTrue(refusalFor(skill("   ", text("body", "x"), List.of())).getMessage().contains("name"),
                "a blank name is refused the same way as a missing one");
    }

    @Test
    void aSkillWithoutABodyIsRefused() {
        GuardrailException refusal = refusalFor(skill("test.bodyless", null, List.of()));
        assertTrue(refusal.getMessage().contains("body"),
                "the body is what the model reads, so its absence is refused by naming it");
        assertTrue(refusal.getMessage().contains("test.bodyless"),
                "the refusal names which bundle failed - an operator with twenty skilljars on the "
                        + "classpath cannot act on a refusal that does not say which one");
    }

    @Test
    void aWellFormedSkillIsAdmitted() {
        SchemaConformanceGuardrail guard = new SchemaConformanceGuardrail(TEST_ROOT);
        assertDoesNotThrow(() -> guard.validate(wellFormed()),
                "a name and a body are the whole structural contract - meeting it is enough to pass");
    }

    @Test
    void anUnknownSuggestedToolWarnsAndStillAdmits() {
        SchemaConformanceGuardrail guard = new SchemaConformanceGuardrail(TEST_ROOT);
        Skill ahead = skill("test.ahead-of-its-tools", text("body", "x"),
                List.of("a_tool_this_process_has_never_registered"));
        assertDoesNotThrow(() -> guard.validate(ahead),
                "a bundle may be published before its tools are admitted, so an unresolved name warns "
                        + "and does not refuse");
    }

    @Test
    void anEmptyBodyIsStructurallyValidBecauseOnlyAbsenceIsRefused() {
        SchemaConformanceGuardrail guard = new SchemaConformanceGuardrail(TEST_ROOT);
        assertDoesNotThrow(() -> guard.validate(skill("test.empty-body", text("body", ""), List.of())),
                "the rule is body non-null, so an empty body passes - the guardrail judges structure "
                        + "and never content");
    }

    @Test
    void theGuardrailIsAnInputSideCheckOverSkills() {
        SchemaConformanceGuardrail guard = new SchemaConformanceGuardrail(TEST_ROOT);
        assertEquals(ContentGuardrail.Direction.INPUT, guard.direction(),
                "a Skill is judged before it enters the conversation, never after the model has read it");
        assertEquals(Skill.class, guard.targetType(),
                "the gate applies to Skills, which is what makes it the skilljars check");
    }
}
