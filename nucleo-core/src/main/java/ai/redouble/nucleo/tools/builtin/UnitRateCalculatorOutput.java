/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from UnitRateCalculatorTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@LLMDescription("Result of unit rate calculation")
public class UnitRateCalculatorOutput  {
    @LLMRequired
    @LLMDescription("Calculated total (unitRate * appliedQuantity)")
    private Long total;
    @LLMRequired
    @LLMDescription("Quantity actually applied (after applying cap if any)")
    private Integer appliedQuantity;
    @LLMRequired
    @LLMDescription("Math formula used, e.g., '150 * 3 = 450' or '150 * 3 = 450 (capped from 5 to 3)'")
    private String formula;

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Integer getAppliedQuantity() {
        return appliedQuantity;
    }

    public void setAppliedQuantity(Integer appliedQuantity) {
        this.appliedQuantity = appliedQuantity;
    }

    public String getFormula() {
        return formula;
    }

    public void setFormula(String formula) {
        this.formula = formula;
    }
}
