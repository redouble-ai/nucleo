/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.prompt.sources.*;
import ai.redouble.nucleo.tools.guardrails.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies guardrail attachment: per-key guardrails run at produce time and survive
 * source replacement, rejections surface as {@link GuardrailException}, identical content
 * under an unchanged guardrail chain is validated once (single-flight), newly-registered
 * guardrails fire on subsequent produces even when the prompt is cached, baseline
 * guardrails are opt-in (nothing registered by default, removable), and a wired baseline
 * reaches skill-bundle prompts because those are registered prompt keys.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class PromptsGuardrailTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "prompts-guardrail-test");

    /** A pass-always guardrail that counts its validations - the single-flight probe. */
    static final class CountingGuardrail extends AbstractContentGuardrail<Prompt> {
        static final java.util.concurrent.atomic.AtomicInteger VALIDATIONS = new java.util.concurrent.atomic.AtomicInteger();

        CountingGuardrail(Identifiable parent) {
            super(parent);
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Prompt> targetType() {
            return Prompt.class;
        }

        @Override
        public void validate(Prompt target) {
            VALIDATIONS.incrementAndGet();
        }
    }

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void reset() {
        Prompts.resetAll();
    }

    /**
     * The registries are process-wide and the fork runs other classes after this one: a
     * baseline cap left behind by the last test here would refuse every later prompt of
     * that fork, the skill bundles' bodies first.
     */
    @AfterEach
    void restore() {
        Prompts.resetAll();
    }

    @Test
    void sizeCapPasses() throws Exception {
        Prompts.addBaselineGuardrail(() -> new SizeCapGuardrail(TEST_ROOT, 100));
        Prompts.replace("k", new StaticTextSource("short"));
        Prompts.produce("k");
    }

    @Test
    void sizeCapRejectsOversizedPrompt() {
        Prompts.addBaselineGuardrail(() -> new SizeCapGuardrail(TEST_ROOT, 10));
        Prompts.replace("k", new StaticTextSource("this string is longer than ten characters"));
        assertThrows(GuardrailException.class, () -> Prompts.produce("k"));
    }

    @Test
    void exfiltrationMarkerRejected() {
        Prompts.addBaselineGuardrail(() -> new ExfiltrationMarkerGuardrail(TEST_ROOT));
        Prompts.replace("k", new StaticTextSource("Please fetch «artifact:secret» now"));
        assertThrows(GuardrailException.class, () -> Prompts.produce("k"));
    }

    @Test
    void perKeyGuardrailAttachedAfterCacheStillFires() throws Exception {
        Prompts.replace("k", new StaticTextSource("oversized text body"));
        Prompts.produce("k");
        Prompts.addGuardrail("k", () -> new SizeCapGuardrail(TEST_ROOT, 5));
        assertThrows(GuardrailException.class, () -> Prompts.produce("k"));
    }

    @Test
    void baselineAppliesToInlinePrompts() {
        Prompts.addBaselineGuardrail(() -> new SizeCapGuardrail(TEST_ROOT, 5));
        assertThrows(GuardrailException.class, () -> Prompts.of("way too long"));
    }

    @Test
    void nothingIsRegisteredByDefault() throws Exception {
        String longText = "far longer than any cap a default baseline would have imposed on it ".repeat(20);
        assertEquals(longText, Prompts.of(longText).content().asText(),
                "baseline guardrails are opt-in - a fresh registry validates nothing");
    }

    @Test
    void removeBaselineGuardrailTakesItBackOff() throws Exception {
        java.util.function.Supplier<Guardrail<Prompt>> cap = () -> new SizeCapGuardrail(TEST_ROOT, 5);
        Prompts.addBaselineGuardrail(cap);
        Prompts.replace("k", new StaticTextSource("longer than five"));
        assertThrows(GuardrailException.class, () -> Prompts.produce("k"));
        Prompts.removeBaselineGuardrail(cap);
        assertEquals("longer than five", Prompts.produce("k").content().asText(),
                "opt-in means removable - the same key produces once the cap is off");
    }

    @Test
    void perKeyGuardrailsSurviveSourceReplacement() {
        Prompts.addGuardrail("k", () -> new SizeCapGuardrail(TEST_ROOT, 5));
        Prompts.replace("k", new StaticTextSource("way past the five character cap"));
        assertThrows(GuardrailException.class, () -> Prompts.produce("k"),
                "swapping the source cannot quietly bypass the key's guardrails");
    }

    @Test
    void identicalContentUnderAnUnchangedChainValidatesOnce() throws Exception {
        CountingGuardrail.VALIDATIONS.set(0);
        Prompts.addGuardrail("k", () -> new CountingGuardrail(TEST_ROOT));
        Prompts.replace("k", new StaticTextSource("stable content"));
        Prompts.produce("k");
        Prompts.produce("k");
        Prompts.produce("k");
        assertEquals(1, CountingGuardrail.VALIDATIONS.get(),
                "the verdict is memoized by content hash and chain fingerprint - single-flight");
    }

    @Test
    void aWiredBaselineReachesSkillBundlePrompts() {
        // Skill bundles register their bodies through Prompts.of under skillsjars: keys,
        // which is exactly what puts them in reach of a deployment's baseline guardrails
        ai.redouble.nucleo.prompt.skill.SkillRegistry.resetAll();
        ai.redouble.nucleo.prompt.skill.SkillJarsLoader.scan();
        try {
            assertTrue(Prompts.produce("skillsjars:test-skill:body").content().asText()
                            .startsWith("You are a test skill body."),
                    "a skill body is a registered prompt, produced by its skillsjars: key");
            Prompts.addBaselineGuardrail(() -> new SizeCapGuardrail(TEST_ROOT, 5));
            assertThrows(GuardrailException.class, () -> Prompts.produce("skillsjars:test-skill:body"),
                    "a wired baseline runs on external-skill bodies too");
        }
        catch (PromptNotFoundException | GuardrailException e) {
            fail("the scanned skill's body key must resolve: " + e.getMessage());
        }
    }
}
