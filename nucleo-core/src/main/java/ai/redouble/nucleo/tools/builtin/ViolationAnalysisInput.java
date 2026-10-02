/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for analyzing guardrail violations to determine intent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
@LLMDescription("Parameters for analyzing whether a guardrail violation was a thinker mistake or user hijack attempt")
public class ViolationAnalysisInput  {
    @LLMRequired
    @LLMDescription("The original input from the user that led to the violation")
    private String originalUserInput;

    @LLMRequired
    @LLMDescription("The full guardrail violation message including details about what was blocked")
    private String violationText;

    @LLMDescription("The name of the tool that was blocked")
    private String blockedToolName;

    public String getOriginalUserInput() {
        return originalUserInput;
    }

    public void setOriginalUserInput(String originalUserInput) {
        this.originalUserInput = originalUserInput;
    }

    public String getViolationText() {
        return violationText;
    }

    public void setViolationText(String violationText) {
        this.violationText = violationText;
    }

    public String getBlockedToolName() {
        return blockedToolName;
    }

    public void setBlockedToolName(String blockedToolName) {
        this.blockedToolName = blockedToolName;
    }
}
