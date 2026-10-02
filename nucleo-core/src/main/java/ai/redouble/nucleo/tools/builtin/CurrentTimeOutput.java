/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output containing current time information in multiple formats, all rendering one
 * instant in the system timezone, so every field names the same calendar date.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-16)
 */
@LLMDescription("Current date and time in various formats, all in the system timezone")
public class CurrentTimeOutput  {
    @LLMRequired
    @LLMDescription("ISO 8601 timestamp in the system timezone with its UTC offset (e.g., 2025-01-15T14:30:45.123-07:00)")
    private String isoTimestamp;
    @LLMRequired
    @LLMDescription("Unix epoch timestamp in milliseconds")
    private Long epochMillis;
    @LLMRequired
    @LLMDescription("Human-readable timestamp in the system timezone (e.g., 'January 15, 2025 at 2:30:45 PM PST')")
    private String humanReadable;
    @LLMRequired
    @LLMDescription("System timezone identifier")
    private String timezone;

    public String getIsoTimestamp() {
        return isoTimestamp;
    }

    public void setIsoTimestamp(String isoTimestamp) {
        this.isoTimestamp = isoTimestamp;
    }

    public Long getEpochMillis() {
        return epochMillis;
    }

    public void setEpochMillis(Long epochMillis) {
        this.epochMillis = epochMillis;
    }

    public String getHumanReadable() {
        return humanReadable;
    }

    public void setHumanReadable(String humanReadable) {
        this.humanReadable = humanReadable;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
