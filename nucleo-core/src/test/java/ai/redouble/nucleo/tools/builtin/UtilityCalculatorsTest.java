/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the utility calculators' arithmetic - their whole reason to exist is that an LLM
 * delegates date math, duration math, and capped multiplication to deterministic code
 * instead of doing them in its head: signed differences, the within-months judgment,
 * hours rounded down, the default minimum, quantity capping visible in the formula, and
 * unparseable input surfacing as a correctable error the model can fix.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class UtilityCalculatorsTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("utility-calculators-test", "utility-calculators-test");
    }

    private static <O> O run(ai.redouble.nucleo.tools.Tool<?, O> tool) throws Exception {
        return JobDispatcher.getInstance().submit(tool).get();
    }

    @Test
    void dateDifferenceIsSignedFromBToA_andWithinMonthsJudges() throws Exception {
        DateCalculatorTool tool = new DateCalculatorTool(root());
        DateCalculatorInput input = new DateCalculatorInput();
        input.setDateA("2025-10-15");
        input.setDateB("2025-01-01");
        input.setWithinMonths(12);
        tool.setInput(input);
        DateCalculatorOutput out = run(tool);
        assertEquals(287, out.getDaysDifference(), "days from B to A, positive when A is later");
        assertEquals(9, out.getMonthsDifference());
        assertTrue(out.getIsWithinMonths(), "287 days later is within 12 months");
        assertNotNull(out.getExplanation());
    }

    @Test
    void reversedOrderFlipsTheSign_andFailsTheWithinJudgment() throws Exception {
        DateCalculatorTool tool = new DateCalculatorTool(root());
        DateCalculatorInput input = new DateCalculatorInput();
        input.setDateA("2025-01-01");
        input.setDateB("2025-10-15");
        input.setWithinMonths(12);
        tool.setInput(input);
        DateCalculatorOutput out = run(tool);
        assertEquals(-287, out.getDaysDifference(), "an earlier A is a negative difference, not an absolute one");
        assertFalse(out.getIsWithinMonths(), "within-months means AFTER and within, so a negative diff fails");
    }

    @Test
    void unparseableDateIsACorrectableError() {
        DateCalculatorTool tool = new DateCalculatorTool(root());
        DateCalculatorInput input = new DateCalculatorInput();
        input.setDateA("2025-13-45");
        input.setDateB("2025-01-01");
        tool.setInput(input);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> run(tool));
        boolean correctable = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InvalidInputException) {
                correctable = true;
                break;
            }
        }
        assertTrue(correctable, "bad input surfaces as InvalidInputException so the model can fix and retry: " + failure);
    }

    @Test
    void durationRoundsHoursDown_andJudgesTheDefaultMinimum() throws Exception {
        DurationCalculatorTool tool = new DurationCalculatorTool(root());
        DurationCalculatorInput input = new DurationCalculatorInput();
        input.setStartDateTime("2025-03-01 08:00");
        input.setEndDateTime("2025-03-02 03:30");
        tool.setInput(input);
        DurationCalculatorOutput out = run(tool);
        assertEquals(19, out.getTotalHours(), "19h30m reports 19 whole hours - rounded down, never up");
        assertEquals(1170, out.getTotalMinutes());
        assertEquals(20, out.getMinimumRequired(), "the default minimum applies when none is given");
        assertFalse(out.getMeetsMinimum(), "19h30m does not meet a 20h minimum - the half hour must not round it over");
    }

    @Test
    void exactMinimumMeetsTheThreshold() throws Exception {
        DurationCalculatorTool tool = new DurationCalculatorTool(root());
        DurationCalculatorInput input = new DurationCalculatorInput();
        input.setStartDateTime("2025-03-01 08:00");
        input.setEndDateTime("2025-03-02 04:00");
        input.setMinimumHours(20);
        tool.setInput(input);
        DurationCalculatorOutput out = run(tool);
        assertEquals(20, out.getTotalHours());
        assertTrue(out.getMeetsMinimum(), "exactly the minimum is enough");
    }

    @Test
    void quantityCapApplies_andTheFormulaAdmitsIt() throws Exception {
        UnitRateCalculatorTool tool = new UnitRateCalculatorTool(root());
        UnitRateCalculatorInput input = new UnitRateCalculatorInput();
        input.setUnitRate(250L);
        input.setQuantity(12);
        input.setMaxQuantity(10);
        tool.setInput(input);
        UnitRateCalculatorOutput out = run(tool);
        assertEquals(10, out.getAppliedQuantity(), "the cap wins over the requested quantity");
        assertEquals(2_500L, out.getTotal(), "the total is computed from the CAPPED quantity");
        assertTrue(out.getFormula().toLowerCase().contains("capp"),
                "the formula discloses that capping happened, so the model cannot misreport: " + out.getFormula());
    }

    @Test
    void uncappedQuantityMultipliesPlainly() throws Exception {
        UnitRateCalculatorTool tool = new UnitRateCalculatorTool(root());
        UnitRateCalculatorInput input = new UnitRateCalculatorInput();
        input.setUnitRate(250L);
        input.setQuantity(4);
        tool.setInput(input);
        UnitRateCalculatorOutput out = run(tool);
        assertEquals(4, out.getAppliedQuantity());
        assertEquals(1_000L, out.getTotal());
    }
}
