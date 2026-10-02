/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

/**
 * Human-readable renderings of counts and durations for log lines and LLM-facing summaries.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public final class Formats {

    private Formats() {
    }

    /**
     * A number the way a person skims it: below a thousand as is, thousands rounded to the
     * nearest K below ten thousand and to the nearest ten K from there, millions rounded to the
     * nearest M with grouping past a thousand M. A count that rounds to a thousand K is a million,
     * so the shortest rendering always wins: {@code 4K}, never {@code 0K}; {@code 1M}, never
     * {@code 1000K}.
     */
    public static String compactNumber(Number number) {
        long n = number.longValue();
        if (n < 1000) {
            return String.valueOf(n);
        }
        if (n < 10_000) {
            return Math.round(n / 1000.0) + "K";
        }
        long tenK = Math.round(n / 10_000.0) * 10;
        if (tenK < 1000) {
            return tenK + "K";
        }
        long m = Math.round(n / 1_000_000.0);
        if (m >= 1000) {
            return String.format("%,dM", m);
        }
        return m + "M";
    }

    /**
     * A duration in its two largest units, compact enough for a status line: {@code 850ms},
     * {@code 45s}, {@code 2m 15s}, {@code 1h 5m}.
     */
    public static String compactDuration(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        }
        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) {
            return minutes + "m " + seconds + "s";
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }

    /** The time elapsed since a {@link System#currentTimeMillis()} reading, rendered by {@link #elapsed(long)}. */
    public static String elapsedSince(long startMillis) {
        return elapsed(System.currentTimeMillis() - startMillis);
    }

    /**
     * A duration in the largest units that matter: days hide seconds and milliseconds, hours
     * hide milliseconds, more than five minutes hides milliseconds, otherwise every unit shows
     * and a unit with a zero count is left out. Zero is {@code 0 ms}.
     */
    public static String elapsed(long milliseconds) {
        if (milliseconds == 0) {
            return "0 ms";
        }
        StringBuilder sb = new StringBuilder();
        long days = milliseconds / (1000 * 60 * 60 * 24);
        boolean skipSecs = false, skipMs = false;
        if (days > 0) {
            sb.append(days).append(" days ");
            skipSecs = true;
            skipMs = true;
        }
        long rem = milliseconds % (1000 * 60 * 60 * 24);
        long hours = rem / (1000 * 60 * 60);
        if (hours > 0) {
            sb.append(hours).append(" hours ");
            rem = rem % (1000 * 60 * 60);
            skipMs = true;
        }
        long minutes = rem / (1000 * 60);
        if (minutes > 0) {
            sb.append(minutes).append(" min ");
            rem = rem % (1000 * 60);
            if (minutes > 5) {
                skipMs = true;
            }
        }
        if (!skipSecs) {
            long seconds = rem / 1000;
            if (seconds > 0) {
                sb.append(seconds).append(" s ");
                rem = rem % 1000;
            }
            if (!skipMs && rem > 0) {
                sb.append(rem).append(" ms");
            }
        }
        return sb.toString().trim();
    }
}
