/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;

import java.util.*;

/**
 * Response from a Thinker's LLM indicating next action.
 * <p>
 * Either contains tool calls to execute or a final answer.
 * The LLM decides when it has enough information to provide the final answer.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
@LLMDescription("Decide whether to call tools for more information or provide a final answer")
public class ThinkingResponse<O> extends StringReasonablePojo {
    @LLMDescription("Set to true when you have enough information to provide the final answer")
    @LLMRequired
    private boolean finalAnswer;

    @LLMDescription("The final answer text (required when finalAnswer is true, null otherwise)")
    private O answer;

    @LLMDescription("Tools to call for more information (empty list when finalAnswer is true)")
    private List<ToolCall> toolCalls;

    public ThinkingResponse() {
        // Default to needing more information
        this.finalAnswer = false;
        this.toolCalls = new ArrayList<>();
        // Set concrete SimpleReasoning type
        this.setReasoning(new SimpleReasoning());

    }

    public boolean isFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(boolean finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public O getAnswer() {
        return answer;
    }

    public void setAnswer(O answer) {
        this.answer = answer;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
}