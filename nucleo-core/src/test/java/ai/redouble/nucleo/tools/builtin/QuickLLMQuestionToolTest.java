/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@code quick_llm_question}'s one rule of its own: the grade travels IN THE INPUT,
 * per question. A code caller that leaves it unset is refused at requirements time with a
 * message naming the rungs, because only the asker knows how much model its question
 * deserves; a grade that is set becomes the seat the requirements book.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class QuickLLMQuestionToolTest {

    private static QuickLLMQuestionTool tool(Grade grade) {
        QuickLLMQuestionTool tool = new QuickLLMQuestionTool(Job.workflow("quick-question-test", "quick-question-test"));
        QuickLLMQuestionInput input = new QuickLLMQuestionInput();
        input.setQuestion("is it so");
        input.setContext("it is so");
        input.setGrade(grade);
        tool.setInput(input);
        return tool;
    }

    @Test
    void aMissingGradeIsRefusedAtRequirementsTime_namingTheRungs() {
        CorrectableRuntimeLLMException refusal = assertThrows(CorrectableRuntimeLLMException.class,
                () -> tool(null).getRequirements(),
                "only the asker knows how much model the question deserves, so the tool never defaults it");
        assertTrue(refusal.getMessage().contains("grade"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("SMALL") && refusal.getMessage().contains("MEGA"),
                "the refusal names the rungs so the caller can choose one: " + refusal.getMessage());
    }

    @Test
    void theGradeInTheInputIsTheSeatTheRequirementsBook() {
        JobRequirements req = tool(Grade.MEDIUM).getRequirements();
        assertEquals(1, req.getModelBindings().size(), "one question is one seat");
        assertEquals(Grade.MEDIUM, req.getModelBindings().get(0).getGrade(),
                "the asker's rung is the one the binding asks the picker for");
        assertFalse(req.requiresTransaction(), "a question needs no database work");
    }
}
