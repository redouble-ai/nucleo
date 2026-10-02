/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from DateCalculatorTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@LLMDescription("Result of date calculation")
public class DateCalculatorOutput  {
    @LLMDescription("Days from dateB to dateA. Positive = dateA is AFTER dateB. Negative = dateA is BEFORE dateB. Zero = same date.")
    private Long daysDifference;
    @LLMDescription("Full months from dateB to dateA. Positive = dateA is AFTER dateB. Negative = dateA is BEFORE dateB.")
    private Long monthsDifference;
    @LLMDescription("If withinMonths was specified: true if dateA is within N months after dateB (dateA > dateB and monthsDifference <= N)")
    private Boolean isWithinMonths;
    @LLMDescription("Human-readable explanation of the calculation")
    private String explanation;

    public Long getDaysDifference() {
        return daysDifference;
    }

    public void setDaysDifference(Long daysDifference) {
        this.daysDifference = daysDifference;
    }

    public Long getMonthsDifference() {
        return monthsDifference;
    }

    public void setMonthsDifference(Long monthsDifference) {
        this.monthsDifference = monthsDifference;
    }

    public Boolean getIsWithinMonths() {
        return isWithinMonths;
    }

    public void setIsWithinMonths(Boolean isWithinMonths) {
        this.isWithinMonths = isWithinMonths;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }
}
