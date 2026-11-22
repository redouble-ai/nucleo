/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the serializer writes dates and times, as the package documentation states it: every
 * {@code java.time} value is ISO-8601 text, and the text says which kind of value it is. A
 * plain date has no time and no zone; a timestamp always carries its offset; a date and time
 * with no zone, and a time of day, gain none. Each written value is read back: a date and a
 * zoneless value to an equal one, a timestamp to the same moment. A value written in the
 * numeric forms of earlier releases is still read.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
class NucleoJsonSerializerTemporalTest {

    public static class Dated {
        private LocalDate day;
        private LocalDateTime local;
        private LocalTime time;
        private Instant instant;
        private OffsetDateTime offset;
        private ZonedDateTime zoned;

        public LocalDate getDay() { return day; }
        public void setDay(LocalDate day) { this.day = day; }
        public LocalDateTime getLocal() { return local; }
        public void setLocal(LocalDateTime local) { this.local = local; }
        public LocalTime getTime() { return time; }
        public void setTime(LocalTime time) { this.time = time; }
        public Instant getInstant() { return instant; }
        public void setInstant(Instant instant) { this.instant = instant; }
        public OffsetDateTime getOffset() { return offset; }
        public void setOffset(OffsetDateTime offset) { this.offset = offset; }
        public ZonedDateTime getZoned() { return zoned; }
        public void setZoned(ZonedDateTime zoned) { this.zoned = zoned; }
    }

    private static Dated sample() {
        Dated dated = new Dated();
        dated.setDay(LocalDate.of(2021, 7, 28));
        dated.setLocal(LocalDateTime.of(2021, 7, 28, 16, 50, 29));
        dated.setTime(LocalTime.of(16, 50, 29));
        dated.setInstant(Instant.parse("2026-05-28T16:50:29.597415Z"));
        dated.setOffset(OffsetDateTime.parse("2026-05-28T18:50:29.597415+02:00"));
        dated.setZoned(ZonedDateTime.of(LocalDateTime.of(2026, 5, 28, 18, 50, 29, 597_415_000), ZoneId.of("Europe/Paris")));
        return dated;
    }

    @Test
    @DisplayName("a plain date is written as a date alone, with no time and no zone")
    void plainDateIsWrittenAsADateAlone() throws IOException {
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(sample()));
        assertTrue(written.get("day").isTextual(), "a date is text, not an array of numbers");
        assertEquals("2021-07-28", written.get("day").asText());
    }

    @Test
    @DisplayName("a timestamp is written as text that carries its offset")
    void timestampIsWrittenWithItsOffset() throws IOException {
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(sample()));
        assertEquals("2026-05-28T16:50:29.597415Z", written.get("instant").asText());
        assertEquals("2026-05-28T18:50:29.597415+02:00", written.get("offset").asText());
        assertEquals("2026-05-28T18:50:29.597415+02:00", written.get("zoned").asText(), "the offset is written, the zone's name is not");
    }

    @Test
    @DisplayName("a date and time with no zone, and a time of day, gain no offset")
    void zonelessValuesGainNoOffset() throws IOException {
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(sample()));
        assertEquals("2021-07-28T16:50:29", written.get("local").asText());
        assertEquals("16:50:29", written.get("time").asText());
    }

    @Test
    @DisplayName("the form written for a model is the same text")
    void summarizedFormIsTheSameText() throws IOException {
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(sample()));
        assertEquals("2021-07-28", written.get("day").asText());
        assertEquals("2026-05-28T16:50:29.597415Z", written.get("instant").asText());
    }

    @Test
    @DisplayName("every written value is read back: zoneless values equal, timestamps the same moment")
    void writtenValuesAreReadBack() throws IOException {
        Dated original = sample();
        Dated read = NucleoJsonSerializer.parse(NucleoJsonSerializer.write(original), Dated.class);
        assertEquals(original.getDay(), read.getDay());
        assertEquals(original.getLocal(), read.getLocal());
        assertEquals(original.getTime(), read.getTime());
        assertEquals(original.getInstant(), read.getInstant());
        assertEquals(original.getOffset().toInstant(), read.getOffset().toInstant());
        assertEquals(original.getZoned().toInstant(), read.getZoned().toInstant());
    }

    @Test
    @DisplayName("a value written in the numeric forms of earlier releases is still read")
    void numericFormsAreStillRead() throws IOException {
        Dated read = NucleoJsonSerializer.parse("{\"day\":[2021,7,28],\"local\":[2021,7,28,16,50,29],\"instant\":1779987029.597415000}", Dated.class);
        assertEquals(LocalDate.of(2021, 7, 28), read.getDay());
        assertEquals(LocalDateTime.of(2021, 7, 28, 16, 50, 29), read.getLocal());
        assertEquals(Instant.parse("2026-05-28T16:50:29.597415Z"), read.getInstant());
    }
}
