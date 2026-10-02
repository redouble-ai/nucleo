/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for UnitRateCalculatorTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@LLMDescription("Input for unit rate calculation with optional quantity cap")
public class UnitRateCalculatorInput  {
    @LLMRequired
    @LLMDescription("Rate per unit (e.g., 10000 for $100.00 per unit, or 150 for 150 items per unit)")
    private Long unitRate;
    @LLMRequired
    @LLMDescription("Number of units")
    private Integer quantity;
    @LLMDescription("Maximum quantity allowed (cap). If null, no cap applies.")
    private Integer maxQuantity;

    public Long getUnitRate() {
        return unitRate;
    }

    public void setUnitRate(Long unitRate) {
        this.unitRate = unitRate;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getMaxQuantity() {
        return maxQuantity;
    }

    public void setMaxQuantity(Integer maxQuantity) {
        this.maxQuantity = maxQuantity;
    }
}
