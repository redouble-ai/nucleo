/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decision's answers are read by the shape they were asked in: {@code choice}, {@code noul}
 * and {@code score} each answer their own kind, an id asked another way or not at all is
 * refused by name, a call that answered nothing refuses with its reason, and the response
 * counts zero output tokens because a decision generates nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
class DecisionResponseTest {

    private static StandardModelSpec decider() {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("kev-test");
        spec.setIdentity("kev-test");
        spec.setProviderKey("systemone-decision");
        spec.setWireModelId("kev-latest");
        spec.setMaxContextTokens(4096);
        spec.setMaxConcurrent(1);
        return spec;
    }

    private static DecisionRequest request() {
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        questions.put("department", Choice.of("Which team?", "billing", "technical"));
        questions.put("is_urgent", Noul.of("Is it urgent?"));
        questions.put("mood", Score.of("How is the customer?", "calm", "angry"));
        return new DecisionRequest("a ticket", questions);
    }

    @Test
    void answersAreReadByTheShapeAskedAndRefusedOtherwiseByName() {
        DecisionResponse response = new DecisionResponse(decider(), request());
        LinkedHashMap<String, Answer> answers = new LinkedHashMap<>();
        answers.put("department", new ChoiceAnswer("billing", Map.of("billing", 0.8, "technical", 0.2), 0.7));
        answers.put("is_urgent", new NoulAnswer(0.95));
        answers.put("mood", new ScoreAnswer(0.9, List.of("calm", "angry"), List.of(0.1, 0.9), 0.8));
        response.setAnswers(answers);
        assertEquals("kev-test", response.getModel(), "the catalog id the call was issued against");
        assertEquals("systemone-decision", response.getProvider());
        assertSame(answers.get("department"), response.choice("department"));
        assertEquals(0.95, response.noul("is_urgent").probability(), 1e-9);
        assertEquals(1, response.score("mood").topLevel());
        IllegalArgumentException wrongShape = assertThrows(IllegalArgumentException.class, () -> response.score("department"));
        assertTrue(wrongShape.getMessage().contains("department") && wrongShape.getMessage().contains("choice"),
                "names the id and the shape it was asked in: " + wrongShape.getMessage());
        IllegalArgumentException unasked = assertThrows(IllegalArgumentException.class, () -> response.noul("priority"));
        assertTrue(unasked.getMessage().contains("priority") && unasked.getMessage().contains("department"),
                "the refusal names the id and what was asked: " + unasked.getMessage());
        assertEquals(0, response.getLastOutputTokens(), "a decision generates nothing");
        assertSame(answers, response.getAnswers());
    }

    @Test
    void aCallThatAnsweredNothingRefusesWithItsReason() {
        DecisionResponse failed = new DecisionResponse(decider(), request());
        failed.setSuccessful(false);
        failed.setReasonForFailure("System One endpoint could not be reached for kev-test");
        assertNull(failed.getAnswers(), "null on a failed call");
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> failed.noul("is_urgent"));
        assertTrue(refusal.getMessage().contains("could not be reached"), refusal.getMessage());
    }
}
