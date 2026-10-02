/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.decision;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.tools.deciding.*;

import java.util.*;

/**
 * One decision. Much of what an application asks a model is a verdict among options the
 * code already knows: which team should take this ticket, is it urgent, how upset is the
 * customer. A decision model is built for exactly that: given a state and typed questions,
 * it writes no text and answers each question with a probability for every option, so every
 * answer is one of your options, the numbers show how clear-cut the call was, and code
 * branches on it directly. Output costs nothing, every question is answered in one round
 * trip, and a small one runs on your own machine.
 *
 * <p>Three shapes of question: {@code Choice} picks one of named options, {@code Noul} is a
 * statement that is true or false, {@code Score} places the state on an ordered scale. The
 * ids the questions go in under are the code's own and never reach the model, and the
 * questions ride a {@code LinkedHashMap} because option order can move the answer, so a
 * fixed order makes a run repeatable. {@link DecisionCall} is a job like any model call:
 * priced, admitted against the model's limits, recorded.
 *
 * <p>Run against Kev-9B on 2026-09-27, the ticket below came out billing 0.49 against
 * shipping 0.44 with technical far behind, urgent 0.97, mood "very angry": the double
 * charge and the late delivery both show, and code decides what to do with the near tie.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class TriageTicket {
    public static void main(String[] args) throws Exception {
        // region decide
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            String ticket = "I was charged twice for order A-1002 and it still has not arrived. Fix this today.";
            LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
            questions.put("team", Choice.of("Which team should handle this ticket?", "billing", "shipping", "technical"));
            questions.put("urgent", Noul.of("Does the ticket convey urgency?"));
            questions.put("mood", Score.of("How is the customer?", "calm", "frustrated", "very angry"));
            DecisionCall call = new DecisionCall(Job.workflow("you", "triage"), new DecisionRequest(ticket, questions));
            DecisionResponse response = dispatcher.submit(call).get();
            ChoiceAnswer team = response.choice("team");
            System.out.println("team: " + team.choice() + " " + team.probabilities());
            System.out.println("urgent: " + response.noul("urgent").probability());
            ScoreAnswer mood = response.score("mood");
            System.out.println("mood: " + mood.levels().get(mood.topLevel()) + " " + mood.probabilities());
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
