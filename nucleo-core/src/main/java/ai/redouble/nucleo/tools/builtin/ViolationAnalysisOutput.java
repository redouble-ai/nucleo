/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from analyzing guardrail violations.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
@LLMDescription("Analysis of whether a guardrail violation was caused by thinker error or user manipulation attempt")
public class ViolationAnalysisOutput  {
    @LLMRequired
    @LLMDescription("True if the violation appears to be the thinker's mistake in constructing the tool call")
    private Boolean isThinkerMistake;

    @LLMRequired
    @LLMDescription("True if the violation appears to be an attempt by the user to manipulate or hijack the system")
    private Boolean isHijackAttempt;

    @LLMRequired
    @LLMDescription("Confidence in this assessment (0.0 to 1.0)")
    private Double confidence;

    @LLMRequired
    @LLMDescription("Detailed reasoning explaining why this determination was made")
    private String reasoning;

    @LLMDescription("Severity level: LOW, MEDIUM, HIGH, CRITICAL")
    private String severity;

    public Boolean getIsThinkerMistake() {
        return isThinkerMistake;
    }

    public void setIsThinkerMistake(Boolean isThinkerMistake) {
        this.isThinkerMistake = isThinkerMistake;
    }

    public Boolean getIsHijackAttempt() {
        return isHijackAttempt;
    }

    public void setIsHijackAttempt(Boolean isHijackAttempt) {
        this.isHijackAttempt = isHijackAttempt;
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

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }
}
