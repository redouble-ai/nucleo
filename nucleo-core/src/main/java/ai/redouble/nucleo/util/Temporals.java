/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;

/**
 * Lenient date parsing for values that arrive as prose or from a model: a fixed sequence of
 * formats is tried in order and the first that fits wins.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public final class Temporals {

    private record Format(DateTimeFormatter formatter, TemporalQuery<? extends TemporalAccessor> query) {
        TemporalAccessor parse(String text) {
            return formatter.parse(text, query);
        }
    }

    /**
     * Tried in this order. The order only matters where two formats accept one text, and then
     * the earlier one's type wins. Every format with a day or month name reads it in English,
     * whatever the default locale.
     */
    private static final List<Format> FORMATS = List.of(
            new Format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US), ZonedDateTime::from),
            new Format(DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US), LocalDate::from),
            new Format(DateTimeFormatter.ofPattern("M/d/yy", Locale.US), LocalDate::from),
            new Format(DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US), LocalDate::from),
            new Format(DateTimeFormatter.ISO_LOCAL_DATE, LocalDate::from),
            new Format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US), ZonedDateTime::from),
            new Format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US), ZonedDateTime::from),
            new Format(DateTimeFormatter.ISO_LOCAL_DATE_TIME, LocalDateTime::from),
            new Format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.US), LocalDateTime::from),
            new Format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SX", Locale.US), LocalDateTime::from),
            new Format(DateTimeFormatter.ofPattern("dd.MM.yyyy"), LocalDate::from),
            new Format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.US), LocalDateTime::from));

    private Temporals() {
    }

    /**
     * The first format that parses the text, as the temporal type that format produces
     * (a {@link LocalDate}, {@link LocalDateTime} or {@link ZonedDateTime}), or null when none does.
     */
    public static TemporalAccessor parseLenient(String text) {
        for (Format format : FORMATS) {
            try {
                return format.parse(text);
            }
            catch (DateTimeException ignored) {
                // the next format gets its turn
            }
        }
        return null;
    }
}
