/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Represents a single tool invocation request from the LLM.
 * <p>
 * The input is stored as Object because at parse time we don't know
 * the specific type each tool expects. Type conversion happens in
 * ThinkingResponseHandler using the ToolRegistry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public class ToolCall  {
    @LLMDescription("Name of the tool to invoke (must match a registered tool name)")
    @LLMRequired
    private String toolName;

    @LLMDescription("Input parameters for the tool")
    @LLMRequired
    private Object input; // Will be Map after parsing, converted to POJO by ThinkingResponseHandler

    // The id its result is recorded against: the provider's on a native tool channel, minted from
    // the tool name and the call's place in the turn when the call was parsed out of text
    @JsonIgnore
    private String toolUseId;

    // Set when the LLM's tool input could not be parsed into the tool's typed input (e.g. an invalid
    // enum value). Correctable: it carries the actionable reason (valid options / redirect) so
    // submitToolCall can surface it as the tool_result and the model can fix its call and retry,
    // instead of the raw node being type-rejected as an opaque SystemException.
    @JsonIgnore
    private CorrectableLLMException parseError;

    public ToolCall() {
    }

    public ToolCall(String toolName, Object input) {
        this.toolName = toolName;
        this.input = input;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public CorrectableLLMException getParseError() {
        return parseError;
    }

    public void setParseError(CorrectableLLMException parseError) {
        this.parseError = parseError;
    }
}