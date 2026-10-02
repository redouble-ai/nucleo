/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for DurationCalculatorTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
@LLMDescription("Input for calculating duration between two date-times")
public class DurationCalculatorInput  {
    @LLMRequired
    @LLMDescription("Start date and time (format: YYYY-MM-DD HH:mm, e.g., '2025-10-20 14:30')")
    private String startDateTime;
    @LLMRequired
    @LLMDescription("End date and time (format: YYYY-MM-DD HH:mm, e.g., '2025-10-21 10:00')")
    private String endDateTime;
    @LLMDescription("Minimum hours required (default 20). Tool will check if duration meets this minimum.")
    private Integer minimumHours;

    public String getStartDateTime() {
        return startDateTime;
    }

    public void setStartDateTime(String startDateTime) {
        this.startDateTime = startDateTime;
    }

    public String getEndDateTime() {
        return endDateTime;
    }

    public void setEndDateTime(String endDateTime) {
        this.endDateTime = endDateTime;
    }

    public Integer getMinimumHours() {
        return minimumHours;
    }

    public void setMinimumHours(Integer minimumHours) {
        this.minimumHours = minimumHours;
    }
}
