/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.time.format.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@code get_current_time}'s one-zone contract: every field of the output renders the
 * same instant in the system timezone, so no field can name a different calendar date than
 * another. The trap this guards against is a UTC rendering next to a local one - for part
 * of every day those disagree on the date, and a model doing date arithmetic answers from
 * whichever field it happens to read.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-20)
 */
public class CurrentTimeToolTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void everyFieldRendersOneInstantInTheSystemZone() throws Exception {
        CurrentTimeTool tool = new CurrentTimeTool(Job.workflow("current-time-test", "current-time-test"));
        tool.setInput(new CurrentTimeInput());
        CurrentTimeOutput out = JobDispatcher.getInstance().submit(tool).get();
        OffsetDateTime iso = OffsetDateTime.parse(out.getIsoTimestamp());
        ZoneId zone = ZoneId.of(out.getTimezone());
        assertEquals(ZoneId.systemDefault(), zone, "the timezone field is the system zone");
        assertEquals(zone.getRules().getOffset(iso.toInstant()), iso.getOffset(),
                "the ISO timestamp carries the system zone's offset, never Z-for-UTC");
        assertEquals(out.getEpochMillis(), iso.toInstant().toEpochMilli(),
                "the ISO timestamp and epochMillis are the same instant");
        String sameDate = DateTimeFormatter.ofPattern("MMMM dd, yyyy").withZone(zone).format(iso.toInstant());
        assertTrue(out.getHumanReadable().startsWith(sameDate),
                "the human-readable field opens with the same calendar date the ISO field names");
    }
}
