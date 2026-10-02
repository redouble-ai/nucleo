/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The renderings {@link Formats} promises for log lines and LLM-facing summaries: a count the way
 * a person skims it (below a thousand as is, thousands to the nearest K below ten thousand and to
 * the nearest ten K from there, millions to the nearest M with grouping past a thousand M, the
 * shortest rendering always winning), a duration in its two largest units, and a duration
 * in the largest units that matter (days hide seconds and milliseconds, hours hide milliseconds,
 * more than five minutes hides milliseconds, zero is "0 ms").
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class FormatsTest {

    @Test
    void belowAThousandACountRendersAsItIs() {
        assertEquals("0", Formats.compactNumber(0));
        assertEquals("999", Formats.compactNumber(999), "no suffix below a thousand");
    }

    @Test
    void thousandsBelowTenThousandRoundToTheNearestK() {
        assertEquals("1K", Formats.compactNumber(1_000), "a thousand is 1K, never 0K");
        assertEquals("5K", Formats.compactNumber(4_900));
        assertEquals("10K", Formats.compactNumber(9_500), "half way rounds up into the ten-K range");
    }

    @Test
    void thousandsFromTenThousandRoundToTheNearestTenK() {
        assertEquals("20K", Formats.compactNumber(15_000), "half way rounds up");
        assertEquals("120K", Formats.compactNumber(123_456));
        assertEquals("120K", Formats.compactNumber(123_456.9), "a fractional count is read as its whole part");
        assertEquals("990K", Formats.compactNumber(994_999));
    }

    @Test
    void millionsRoundToTheNearestMAndGroupPastAThousandM() {
        assertEquals("1M", Formats.compactNumber(999_999), "a count that rounds to a thousand K is a million, never 1000K");
        assertEquals("1M", Formats.compactNumber(1_000_000));
        assertEquals("3M", Formats.compactNumber(2_500_000), "half way rounds up");
        assertEquals(String.format("%,dM", 1_500L), Formats.compactNumber(1_500_000_000L), "grouping separators past a thousand M");
    }

    @Test
    void aCompactDurationShowsItsTwoLargestUnits() {
        assertEquals("850ms", Formats.compactDuration(850));
        assertEquals("45s", Formats.compactDuration(45_000), "whole seconds, milliseconds dropped");
        assertEquals("2m 15s", Formats.compactDuration(135_000));
        assertEquals("1h 5m", Formats.compactDuration(3_900_000), "hours drop the seconds");
    }

    @Test
    void elapsedShowsEveryUnitUpToFiveMinutes() {
        assertEquals("0 ms", Formats.elapsed(0), "zero is spelled out, never an empty string");
        assertEquals("850 ms", Formats.elapsed(850));
        assertEquals("2 s 500 ms", Formats.elapsed(2_500));
        assertEquals("1 min 1 s", Formats.elapsed(61_000), "a unit with a zero count is left out");
        assertEquals("5 min 1 s 500 ms", Formats.elapsed(301_500), "five minutes still shows milliseconds");
    }

    @Test
    void elapsedHidesTheUnitsThatStopMattering() {
        assertEquals("6 min 1 s", Formats.elapsed(361_500), "more than five minutes hides milliseconds");
        assertEquals("1 hours 5 min 30 s", Formats.elapsed(3_930_250), "hours hide milliseconds");
        assertEquals("1 days 2 hours 3 min", Formats.elapsed(93_784_000), "days hide seconds and milliseconds");
    }

    @Test
    void elapsedSinceMeasuresFromAClockReading() {
        String rendered = Formats.elapsedSince(System.currentTimeMillis() - 2_500);
        assertTrue(rendered.startsWith("2 s"), "the reading is treated as a System.currentTimeMillis() start: " + rendered);
    }
}
