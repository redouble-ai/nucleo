/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.harness.schema.*;

/**
 * What the judge answers, in the rubric's two parts: accuracy out of 7 and shown work out of
 * 3, each in half points, the tool fault it found if any, and the reason. The score out of 10
 * is the two parts added, by the benchmark, never by the judge.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class JudgeVerdict {
    @LLMRequired
    @LLMDescription("Accuracy, 0 to 7 in half points, by the rules: 7 matches the reference in substance, 6 or 6.5 a defensible different reading, 3 to 5 partly right, 0 to 2 wrong; minus at least 3 for a tool called wrongly, skipped or misread")
    private Double accuracy;
    @LLMRequired
    @LLMDescription("Shown work, 0 to 3 in half points: 3 a clear, complete account resting on the tool results, 0 a bare answer or work that contradicts it")
    private Double reasoning;
    @LLMDescription("Only when there is a tool fault: which tool was called wrongly, skipped or misread, and how. When the tools were used right, leave this field out entirely - never write none, n/a or an empty string")
    private String toolFault;
    @LLMRequired
    @LLMDescription("Two or three sentences naming what was compared against the reference and where the points went")
    private String reason;

    public Double getAccuracy() {return accuracy;}

    public void setAccuracy(Double accuracy) {this.accuracy = accuracy;}

    public Double getReasoning() {return reasoning;}

    public void setReasoning(Double reasoning) {this.reasoning = reasoning;}

    public String getToolFault() {return toolFault;}

    public void setToolFault(String toolFault) {this.toolFault = toolFault;}

    public String getReason() {return reason;}

    public void setReason(String reason) {this.reason = reason;}
}
