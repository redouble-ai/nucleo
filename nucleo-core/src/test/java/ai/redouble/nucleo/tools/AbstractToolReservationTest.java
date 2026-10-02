/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AbstractModelDependentTool#buildInputPrompt} and the prompt-form
 * {@link ModelBinding} a tool reserves through. The two exist so
 * that a tool's {@code getRequirements()} can size its rate-limiter
 * reservation against the exact same prompt string that
 * {@code wireConversation} puts on the wire - which is the invariant that
 * keeps local bucket accounting in sync with what the upstream provider
 * pre-debits as {@code max_tokens}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class AbstractToolReservationTest {

    /** The output the harness tool declares on its prompt-form requirement. */
    private static final int TEST_DEFAULT_OUTPUT = 16_000;

    private static final ModelSpec MODEL = TestModels.small();

    @Test
    void buildInputPrompt_withInput_includesInputDataHeaderAndSerializedInput() {
        TestTool tool = new TestTool();
        TestInput in = new TestInput();
        in.name = "alpha";
        in.count = 42;
        tool.setInput(in);

        String prompt = tool.exposedBuildPrompt("please analyze");

        assertTrue(prompt.startsWith("Input data:\n"),
                "input section is prefixed with 'Input data:' header");
        assertTrue(prompt.contains("alpha"), "serialized input value appears in prompt");
        assertTrue(prompt.contains("42"), "serialized input value appears in prompt");
        assertTrue(prompt.contains("please analyze"),
                "additional instructions are appended after the input");
        assertTrue(prompt.indexOf("please analyze") > prompt.indexOf("alpha"),
                "instructions come after the input section");
    }

    @Test
    void buildInputPrompt_withoutInput_omitsInputHeader() {
        TestTool tool = new TestTool();
        // No setInput() - input stays null.

        String prompt = tool.exposedBuildPrompt("just do it");

        assertFalse(prompt.contains("Input data:"),
                "no input means no 'Input data:' header");
        assertEquals("just do it", prompt,
                "prompt is exactly the instructions when input is null");
    }

    @Test
    void buildInputPrompt_withInputButNoInstructions_returnsJustInputSection() {
        TestTool tool = new TestTool();
        TestInput in = new TestInput();
        in.name = "alpha";
        tool.setInput(in);

        String prompt = tool.exposedBuildPrompt(null);

        assertTrue(prompt.startsWith("Input data:\n"));
        assertTrue(prompt.contains("alpha"));
    }

    @Test
    void buildInputPrompt_emptyInstructions_sameAsNullInstructions() {
        TestTool tool = new TestTool();
        TestInput in = new TestInput();
        in.name = "alpha";
        tool.setInput(in);

        String withEmpty = tool.exposedBuildPrompt("");
        String withNull = tool.exposedBuildPrompt(null);

        assertEquals(withNull, withEmpty,
                "empty string and null instructions produce identical prompts");
    }

    @Test
    void estimateLLMReservation_includesPromptTokensPlusOutputBudget() {
        TestTool tool = new TestTool();
        TestInput in = new TestInput();
        in.name = "alpha";
        in.count = 42;
        tool.setInput(in);

        int reservation = tool.exposedEstimate(MODEL, "please analyze");
        int promptTokens = TokenizerFactory.get().forModel(MODEL).countTokens(tool.exposedBuildPrompt("please analyze"));

        assertEquals(promptTokens + TEST_DEFAULT_OUTPUT, reservation,
                "reservation = prompt tokens + the declared output (no thinking at IMMEDIATE)");
    }

    @Test
    void estimateLLMReservation_scalesWithInputSize() {
        TestTool smallTool = new TestTool();
        TestInput small = new TestInput();
        small.name = "x";
        smallTool.setInput(small);

        TestTool bigTool = new TestTool();
        TestInput big = new TestInput();
        big.name = "x".repeat(5000);
        bigTool.setInput(big);

        int smallReservation = smallTool.exposedEstimate(MODEL, null);
        int bigReservation = bigTool.exposedEstimate(MODEL, null);

        assertTrue(bigReservation > smallReservation,
                "a 5000-char input must reserve more tokens than a 1-char input");
    }

    // ======================== Test harness ========================

    public static class TestInput {
        public String name;
        public int count;
    }

    public static class TestOutput {
        public String result;
    }

    /**
     * Minimal concrete AbstractModelDependentTool that exposes the protected helpers as
     * public methods so tests can call them without reflection. Never
     * executed as a real job - we only drive the prompt builder and
     * reservation estimator.
     */
    private static class TestTool extends AbstractModelDependentTool<TestInput, TestOutput> {
        TestTool() {
            super(Job.workflow("test-user", "reservation-test"), Grade.SMALL);
        }

        String exposedBuildPrompt(String instructions) {
            return buildInputPrompt(instructions);
        }

        int exposedEstimate(ModelSpec model, String instructions) {
            // The prompt-form binding: counted prompt under the resolved spec's tokenizer + the
            // declared output; IMMEDIATE so no thinking term joins the arithmetic
            ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE, buildInputPrompt(instructions), OutputDeclaration.of(TEST_DEFAULT_OUTPUT));
            binding.resolve(model);
            return binding.price();
        }

        @Override
        public TestOutput execute(JobResources resources, JobContext<TestOutput> context) {
            throw new UnsupportedOperationException("not called in these tests");
        }
    }
}
