/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import org.junit.jupiter.api.*;

import java.time.*;
import java.time.temporal.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Temporals#parseLenient}: a fixed sequence of formats is tried in order, the first that
 * fits wins, and the answer is the temporal type that format produces (a {@link LocalDate}, a
 * {@link LocalDateTime} or a {@link ZonedDateTime}), or null when no format fits. One case per
 * shape in the sequence: offset ISO, the US date shapes, plain ISO dates, RFC 822, local
 * date-times, the LDAP and dotted forms.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class TemporalsTest {

    private static final LocalDate NEW_YEARS_EVE = LocalDate.of(2024, 12, 31);
    private static final LocalDateTime MORNING = LocalDateTime.of(2024, 12, 31, 10, 15, 30);

    @Test
    void offsetIsoShapesProduceAZonedDateTime() {
        TemporalAccessor withMillis = Temporals.parseLenient("2024-12-31T10:15:30.500+02:00");
        assertInstanceOf(ZonedDateTime.class, withMillis, "an offset ISO instant keeps its offset");
        assertEquals(ZonedDateTime.of(MORNING.withNano(500_000_000), ZoneOffset.ofHours(2)), withMillis);
        assertEquals(ZonedDateTime.of(MORNING, ZoneOffset.ofHours(2)), Temporals.parseLenient("2024-12-31T10:15:30+02:00"));
    }

    @Test
    void usDateShapesProduceALocalDate() {
        assertEquals(NEW_YEARS_EVE, Temporals.parseLenient("12/31/24"), "a two-digit year is read in this century");
        assertEquals(LocalDate.of(2024, 1, 2), Temporals.parseLenient("1/2/24"), "single-digit month and day");
        assertEquals(NEW_YEARS_EVE, Temporals.parseLenient("12/31/2024"));
    }

    @Test
    void plainAndDottedDatesProduceALocalDate() {
        assertEquals(NEW_YEARS_EVE, Temporals.parseLenient("2024-12-31"));
        assertEquals(NEW_YEARS_EVE, Temporals.parseLenient("31.12.2024"));
    }

    @Test
    void rfc822ProducesAZonedDateTime() {
        TemporalAccessor parsed = Temporals.parseLenient("Tue, 31 Dec 2024 10:15:30 +0000");
        assertInstanceOf(ZonedDateTime.class, parsed);
        assertEquals(MORNING.toInstant(ZoneOffset.UTC), ((ZonedDateTime) parsed).toInstant());
    }

    @Test
    void localDateTimeShapesProduceALocalDateTime() {
        assertEquals(MORNING, Temporals.parseLenient("2024-12-31T10:15:30"), "ISO local date-time");
        assertEquals(MORNING, Temporals.parseLenient("31-Dec-2024 10:15:30"), "the day-month-name form");
        assertEquals(MORNING, Temporals.parseLenient("20241231101530.0Z"), "the LDAP generalized-time form drops its zone");
        assertEquals(MORNING, Temporals.parseLenient("2024-12-31 10.15.30"), "the dotted time form");
    }

    @Test
    void monthNamesAreReadInEnglishWhateverTheDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.FRANCE);
        try {
            assertEquals(MORNING, Temporals.parseLenient("31-Dec-2024 10:15:30"), "an English month abbreviation under a French default locale");
            assertEquals(MORNING.toInstant(ZoneOffset.UTC), ((ZonedDateTime) Temporals.parseLenient("Tue, 31 Dec 2024 10:15:30 +0000")).toInstant(),
                    "an English day and month name in the RFC 822 form under a French default locale");
        }
        finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void textNoFormatFitsIsNull() {
        assertNull(Temporals.parseLenient("the day after tomorrow"), "no format fits, so no guess: null");
        assertNull(Temporals.parseLenient(""), "empty text fits nothing");
    }
}
