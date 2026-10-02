/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for DateCalculatorTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@LLMDescription("Input for date calculations and comparisons")
public class DateCalculatorInput  {
    @LLMRequired
    @LLMDescription("First date (format: YYYY-MM-DD)")
    private String dateA;
    @LLMRequired
    @LLMDescription("Second date (format: YYYY-MM-DD)")
    private String dateB;
    @LLMDescription("Optional: Check if dateA is within this many months after dateB")
    private Integer withinMonths;

    public String getDateA() {
        return dateA;
    }

    public void setDateA(String dateA) {
        this.dateA = dateA;
    }

    public String getDateB() {
        return dateB;
    }

    public void setDateB(String dateB) {
        this.dateB = dateB;
    }

    public Integer getWithinMonths() {
        return withinMonths;
    }

    public void setWithinMonths(Integer withinMonths) {
        this.withinMonths = withinMonths;
    }
}
