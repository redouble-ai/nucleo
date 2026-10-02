/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for quick LLM question. Carries the grade: this tool is the way anyone,
 * code or model, asks one question without paying for a thinker, and only the
 * asker knows how much model that question deserves.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-20)
 */
@LLMDescription("Parameters for asking a focused question about specific text")
public class QuickLLMQuestionInput  {
    @LLMRequired
    @LLMDescription("The specific question to answer about the provided context")
    private String question;

    @LLMRequired
    @LLMDescription("The text to analyze and answer questions about")
    private String context;

    @LLMRequired
    @LLMDescription("How much model this one question needs. "
                    + "SMALL: a lookup, a classification, or a fact stated plainly in the context. "
                    + "MEDIUM: reasoning over the context - comparison, inference, summarization with judgment. "
                    + "XL: only when the answer turns on deep judgment or subtle reading. "
                    + "MEGA: the strongest model the deployment serves; reserve for questions where a wrong answer is expensive. "
                    + "Pick the lowest rung that can answer well - this tool exists to be cheap.")
    private Grade grade;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public Grade getGrade() {
        return grade;
    }

    public void setGrade(Grade grade) {
        this.grade = grade;
    }
}
