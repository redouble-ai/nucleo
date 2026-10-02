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
 * Calculates hours and minutes between two date-times with optional minimum threshold.
 * <p>
 * Use this tool to compute elapsed duration between two timestamps and verify
 * whether the duration meets a configurable minimum (default 20 hours).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@DisplayName(value = "Duration Calculator", action = "Calculating duration")
@ToolName("calculate_duration")
@ToolDescription(value = "Calculates hours and minutes between two date-times. Use this to compute elapsed duration and verify minimum duration requirements.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class DurationCalculatorTool extends AbstractTool<DurationCalculatorInput, DurationCalculatorOutput> {
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DEFAULT_MINIMUM_HOURS = 20;

    public DurationCalculatorTool(Identifiable parent) {
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
    public DurationCalculatorOutput execute(JobResources resources, JobContext<DurationCalculatorOutput> context) throws LLMReadableCheckedException {
        context.publish("Parsing date-times", 10);
        LocalDateTime start = parseDateTime("startDateTime", input.getStartDateTime());
        LocalDateTime end = parseDateTime("endDateTime", input.getEndDateTime());
        DurationCalculatorOutput output = new DurationCalculatorOutput();

        // Calculate duration
        long totalMinutes = ChronoUnit.MINUTES.between(start, end);
        int totalHours = (int) (totalMinutes / 60);
        output.setTotalMinutes((int) totalMinutes);
        output.setTotalHours(totalHours);
        context.publish("Total hours: " + totalHours, 50);

        // Check minimum
        int minimumRequired = input.getMinimumHours() != null ? input.getMinimumHours() : DEFAULT_MINIMUM_HOURS;
        output.setMinimumRequired(minimumRequired);
        output.setMeetsMinimum(totalHours >= minimumRequired);

        // Build explanation
        StringBuilder explanation = new StringBuilder();
        explanation.append("Start: ").append(input.getStartDateTime());
        explanation.append(" -> End: ").append(input.getEndDateTime());
        explanation.append(" = ").append(totalHours).append(" hours");
        if (totalMinutes % 60 != 0) {
            explanation.append(" and ").append(totalMinutes % 60).append(" minutes");
        }
        explanation.append(". ");
        if (output.getMeetsMinimum()) {
            explanation.append("MEETS minimum of ").append(minimumRequired).append(" hours.");
        }
        else {
            explanation.append("DOES NOT MEET minimum of ").append(minimumRequired).append(" hours.");
        }
        output.setExplanation(explanation.toString());
        context.publish("Calculation complete", 100);
        return output;
    }

    /** A malformed date-time came from the model - correctable, so it can fix the format and retry. */
    private static LocalDateTime parseDateTime(String param, String value) throws InvalidInputException {
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMAT);
        }
        catch (DateTimeParseException e) {
            throw new InvalidInputException(param, value, "must be a yyyy-MM-dd HH:mm date-time: " + e.getMessage());
        }
    }
}
