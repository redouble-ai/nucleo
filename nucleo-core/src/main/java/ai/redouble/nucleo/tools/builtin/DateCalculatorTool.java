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
import java.time.format.*;
import java.time.temporal.*;

/**
 * Compares two dates and calculates the difference between them.
 * <p>
 * Use this for date comparisons, calculating days/months between dates,
 * and checking timing rules (e.g., "is date A within 9 months of date B?").
 * <p>
 * Returns signed values:
 * - Positive = dateA is AFTER dateB
 * - Negative = dateA is BEFORE dateB
 * - Zero = same date
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@DisplayName(value = "Date Calculator", action = "Calculating date difference")
@ToolName("calculate_dates")
@ToolDescription(value = "Compares two dates and calculates the difference. Use this for date comparisons, calculating days/months between dates, and checking timing rules.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class DateCalculatorTool extends AbstractTool<DateCalculatorInput, DateCalculatorOutput> {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public DateCalculatorTool(Identifiable parent) {
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
    public DateCalculatorOutput execute(JobResources resources, JobContext<DateCalculatorOutput> context) throws LLMReadableCheckedException {
        context.publish("Parsing dates", 10);
        LocalDate dateA = parseDate("dateA", input.getDateA());
        LocalDate dateB = parseDate("dateB", input.getDateB());
        DateCalculatorOutput output = new DateCalculatorOutput();

        // Calculate differences (from dateB to dateA)
        long daysDiff = ChronoUnit.DAYS.between(dateB, dateA);
        long monthsDiff = ChronoUnit.MONTHS.between(dateB, dateA);
        output.setDaysDifference(daysDiff);
        output.setMonthsDifference(monthsDiff);
        context.publish("Days difference: " + daysDiff + ", months: " + monthsDiff, 50);

        // Check withinMonths if specified
        Integer withinMonths = input.getWithinMonths();
        if (withinMonths != null) {
            // isWithinMonths = true if dateA is after dateB AND within N months
            boolean isWithin = daysDiff > 0 && monthsDiff <= withinMonths;
            output.setIsWithinMonths(isWithin);
        }

        // Build explanation
        StringBuilder explanation = new StringBuilder();
        explanation.append("dateA (").append(input.getDateA()).append(") is ");
        if (daysDiff == 0) {
            explanation.append("the same date as");
        }
        else {
            long absDays = Math.abs(daysDiff);
            long absMonths = Math.abs(monthsDiff);
            // Calculate remaining days after full months
            LocalDate earlier = daysDiff > 0 ? dateB : dateA;
            LocalDate later = daysDiff > 0 ? dateA : dateB;
            LocalDate afterMonths = earlier.plusMonths(absMonths);
            long remainingDays = ChronoUnit.DAYS.between(afterMonths, later);
            if (absMonths > 0) {
                explanation.append(absMonths).append(" month").append(absMonths != 1 ? "s" : "");
                if (remainingDays > 0) {
                    explanation.append(" and ").append(remainingDays).append(" day").append(remainingDays != 1 ? "s" : "");
                }
            }
            else {
                explanation.append(absDays).append(" day").append(absDays != 1 ? "s" : "");
            }
            explanation.append(daysDiff > 0 ? " after" : " before");
        }
        explanation.append(" dateB (").append(input.getDateB()).append(")");

        // Add withinMonths result to explanation
        if (withinMonths != null) {
            explanation.append(". This ");
            if (daysDiff <= 0) {
                explanation.append("is BEFORE dateB, so not applicable for 'within N months after' check");
            }
            else {
                explanation.append(output.getIsWithinMonths() ? "IS" : "is NOT");
                explanation.append(" within ").append(withinMonths).append(" months");
            }
        }
        explanation.append(".");
        output.setExplanation(explanation.toString());
        context.publish("Calculation complete", 100);
        return output;
    }

    /** A malformed date came from the model - correctable, so it can fix the format and retry. */
    private static LocalDate parseDate(String param, String value) throws InvalidInputException {
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        }
        catch (DateTimeParseException e) {
            throw new InvalidInputException(param, value, "must be a yyyy-MM-dd date: " + e.getMessage());
        }
    }
}
