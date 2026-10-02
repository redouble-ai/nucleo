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

/**
 * Returns the current date and time in multiple formats, every one of them rendering the
 * same instant in the system timezone. One zone everywhere is the contract: a UTC rendering
 * next to a local one names two different calendar dates for part of every day, and a model
 * doing date arithmetic picks whichever field it happens to read. The ISO field carries the
 * zone's UTC offset, so the exact instant stays derivable.
 * This is a lightweight tool that requires no resources and executes instantly.
 * Useful for thinkers that need to know the current time for time-sensitive operations,
 * scheduling, or temporal reasoning.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-16)
 */
@DisplayName(value = "Get Current Time", action = "Retrieving Current Time")
@ToolName("get_current_time")
@ToolDescription(value = "Get the current date and time. Call this FIRST and WAIT for result before making any time-dependent searches.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class CurrentTimeTool extends AbstractTool<CurrentTimeInput, CurrentTimeOutput> {
    public CurrentTimeTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(1));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public CurrentTimeOutput execute(JobResources resources, JobContext<CurrentTimeOutput> context) throws LLMReadableCheckedException {
        Instant now = Instant.now();
        ZoneId systemZone = ZoneId.systemDefault();
        CurrentTimeOutput output = new CurrentTimeOutput();
        output.setIsoTimestamp(now.atZone(systemZone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        output.setEpochMillis(now.toEpochMilli());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy 'at' h:mm:ss a z").withZone(systemZone);
        output.setHumanReadable(formatter.format(now));
        output.setTimezone(systemZone.getId());
        return output;
    }
}
