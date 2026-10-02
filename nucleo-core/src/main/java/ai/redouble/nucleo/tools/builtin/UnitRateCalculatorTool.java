/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * Multiplies a unit rate by a quantity with an optional cap on quantity.
 * <p>
 * Use this tool for any rate-times-quantity calculation where exact arithmetic
 * matters. Returns the total, the applied quantity (after cap), and a
 * human-readable formula for verification.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@DisplayName(value = "Unit Rate Calculator", action = "Calculating unit rate")
@ToolName("calculate_unit_rate")
@ToolDescription(value = "Multiplies a unit rate by a quantity with an optional quantity cap. Returns exact total with formula. USE THIS TOOL for all rate * quantity calculations to ensure accuracy.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class UnitRateCalculatorTool extends AbstractTool<UnitRateCalculatorInput, UnitRateCalculatorOutput> {
    public UnitRateCalculatorTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(5));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        return req;
    }

    @Override
    public UnitRateCalculatorOutput execute(JobResources resources, JobContext<UnitRateCalculatorOutput> context) throws LLMReadableCheckedException {
        context.publish("Calculating", 10);
        Long unitRate = input.getUnitRate();
        Integer quantity = input.getQuantity();
        Integer maxQuantity = input.getMaxQuantity();
        if (unitRate == null || unitRate < 0) {
            throw new InvalidInputException("unitRate", input.getUnitRate(), "must be a non-negative number");
        }
        if (quantity == null || quantity < 0) {
            throw new InvalidInputException("quantity", input.getQuantity(), "must be a non-negative number");
        }
        context.publish("Applying cap if applicable", 50);

        // Apply cap
        int appliedQuantity = quantity;
        String capNote = "";
        if (maxQuantity != null && maxQuantity > 0 && quantity > maxQuantity) {
            appliedQuantity = maxQuantity;
            capNote = " (capped from " + quantity + " to " + maxQuantity + ")";
        }

        // Calculate
        long total = unitRate * appliedQuantity;

        // Format formula
        String formula = unitRate + " * " + appliedQuantity + " = " + total + capNote;
        context.publish("Complete", 100);
        UnitRateCalculatorOutput output = new UnitRateCalculatorOutput();
        output.setTotal(total);
        output.setAppliedQuantity(appliedQuantity);
        output.setFormula(formula);
        return output;
    }
}
