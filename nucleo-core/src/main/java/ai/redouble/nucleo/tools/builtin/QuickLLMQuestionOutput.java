/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from quick LLM question.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-20)
 */
@LLMDescription("Answer to a focused question about specific text")
public class QuickLLMQuestionOutput  {
    @LLMRequired
    @LLMDescription("Direct answer to the question")
    private String answer;

    @LLMDescription("Confidence in the answer (0.0 to 1.0)")
    private Double confidence;

    @LLMDescription("Brief explanation or reasoning if needed")
    private String reasoning;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }
}