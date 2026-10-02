/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * One decision call, recorded on the job like every other model call: the catalog id it was
 * issued against, the input tokens the endpoint billed, the latency, the verdict, and the
 * answers by question id. A decision call is no conversation exchange, so the message-shaped
 * parts of {@link LLMResponse} are null on it and its output token count is zero: a cost
 * ledger, a meter, a span or a usage table reads the same fields off it as off a chat call,
 * which is what puts decision spend under the same cap as everything else. What a decision
 * leaves behind that a chat call does not is the distributions themselves: with no
 * reasoning to read, the answers are the record of what the model thought.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class DecisionResponse extends LLMResponse<Void> {
    private final DecisionRequest decisionRequest;
    private Map<String, Answer> answers;

    public DecisionResponse(ModelSpec model, DecisionRequest request) {
        this.decisionRequest = request;
        setModel(model.getId());
        setProvider(model.getProviderKey());
    }

    /** The request this call answered. */
    public DecisionRequest getDecisionRequest() {
        return decisionRequest;
    }

    /** The answers by question id, in the order asked; null on a failed call. */
    public Map<String, Answer> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<String, Answer> answers) {
        this.answers = answers;
    }

    /** The answer to a question asked as a {@link Choice}; refuses an id that was asked another way or not at all. */
    public ChoiceAnswer choice(String id) {
        return answer(id, ChoiceAnswer.class);
    }

    /** The answer to a question asked as a {@link Noul}. */
    public NoulAnswer noul(String id) {
        return answer(id, NoulAnswer.class);
    }

    /** The answer to a question asked as a {@link Score}. */
    public ScoreAnswer score(String id) {
        return answer(id, ScoreAnswer.class);
    }

    private <A extends Answer> A answer(String id, Class<A> type) {
        if (answers == null) {
            throw new IllegalStateException("The call answered nothing" + (reasonForFailure != null ? ": " + reasonForFailure : ""));
        }
        Answer answer = answers.get(id);
        if (answer == null) {
            throw new IllegalArgumentException("No question was asked under '" + id + "'; asked: " + answers.keySet());
        }
        if (!type.isInstance(answer)) {
            throw new IllegalArgumentException("'" + id + "' was asked as a " + answer.getClass().getSimpleName().replace("Answer", "").toLowerCase()
                    + ", read it as that");
        }
        return type.cast(answer);
    }

    @Override
    public int getLastOutputTokens() {
        return 0;
    }
}
