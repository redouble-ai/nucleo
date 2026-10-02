/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import java.util.*;

/**
 * One request to a decision model: a state and the questions to answer about it, each under
 * an id the caller chose. The state is text, or a structure the wire renders as text (a map,
 * a list, a plain object), and it is read once for every question in the request; the
 * questions are answered independently of one another and the answers come back under the
 * same ids. An id is the caller's handle: it keys the wire request and its answers, and the
 * model is asked the question's instructions, nothing about the id.
 *
 * @param state     what the questions are about; text, or a structure rendered as text
 * @param questions the questions by id, at least one, in the order asked
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record DecisionRequest(Object state, Map<String, Question> questions) {

    public DecisionRequest {
        if (state == null) {
            throw new IllegalArgumentException("A decision request needs a state to judge");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("A decision request asks at least one question");
        }
        LinkedHashMap<String, Question> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Question> question : questions.entrySet()) {
            if (question.getKey() == null || question.getKey().isBlank() || question.getValue() == null) {
                throw new IllegalArgumentException("Every question is asked under a non-blank id");
            }
            kept.put(question.getKey(), question.getValue());
        }
        questions = Collections.unmodifiableMap(kept);
    }
}
