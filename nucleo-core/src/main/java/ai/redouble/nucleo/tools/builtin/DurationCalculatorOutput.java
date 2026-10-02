/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from DurationCalculatorTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@LLMDescription("Result of duration calculation between two date-times")
public class DurationCalculatorOutput  {
    @LLMDescription("Total hours between start and end (rounded down)")
    private Integer totalHours;
    @LLMDescription("Total minutes between start and end")
    private Integer totalMinutes;
    @LLMDescription("Whether the duration meets the minimum hours requirement")
    private Boolean meetsMinimum;
    @LLMDescription("The minimum hours that was checked against")
    private Integer minimumRequired;
    @LLMDescription("Human-readable explanation of the calculation")
    private String explanation;

    public Integer getTotalHours() {
        return totalHours;
    }

    public void setTotalHours(Integer totalHours) {
        this.totalHours = totalHours;
    }

    public Integer getTotalMinutes() {
        return totalMinutes;
    }

    public void setTotalMinutes(Integer totalMinutes) {
        this.totalMinutes = totalMinutes;
    }

    public Boolean getMeetsMinimum() {
        return meetsMinimum;
    }

    public void setMeetsMinimum(Boolean meetsMinimum) {
        this.meetsMinimum = meetsMinimum;
    }

    public Integer getMinimumRequired() {
        return minimumRequired;
    }

    public void setMinimumRequired(Integer minimumRequired) {
        this.minimumRequired = minimumRequired;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }
}
