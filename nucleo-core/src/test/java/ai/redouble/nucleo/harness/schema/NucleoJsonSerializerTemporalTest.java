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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the serializer writes dates and times, as the package documentation states it: every
 * {@code java.time} value is ISO-8601 text, and the text says which kind of value it is. A
 * plain date has no time and no zone; a timestamp always carries its offset and never the
 * name of a zone; a date and time with no zone, and a time of day, gain none. Every written
 * value reads back to an equal value, at any precision, alone, in a list, as a map's value
 * and nested in another object: a timestamp at the offset it was written with, a
 * {@code ZonedDateTime} of a named zone as the same date, time and offset. A value written
 * in the numeric forms of earlier releases is still read, and a duration is still written as
 * a number.
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
        private Date legacy;
        private Duration lasted;
        private List<LocalDate> days;
        private Map<String, Instant> moments;
        private Dated inner;

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
        public Date getLegacy() { return legacy; }
        public void setLegacy(Date legacy) { this.legacy = legacy; }
        public Duration getLasted() { return lasted; }
        public void setLasted(Duration lasted) { this.lasted = lasted; }
        public List<LocalDate> getDays() { return days; }
        public void setDays(List<LocalDate> days) { this.days = days; }
        public Map<String, Instant> getMoments() { return moments; }
        public void setMoments(Map<String, Instant> moments) { this.moments = moments; }
        public Dated getInner() { return inner; }
        public void setInner(Dated inner) { this.inner = inner; }
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

    private static JsonNode written(Dated dated) throws IOException {
        return NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(dated));
    }

    private static Dated roundTrip(Dated dated) throws IOException {
        return NucleoJsonSerializer.parse(NucleoJsonSerializer.write(dated), Dated.class);
    }

    @Test
    @DisplayName("a plain date is written as a date alone, with no time and no zone")
    void plainDateIsWrittenAsADateAlone() throws IOException {
        JsonNode written = written(sample());
        assertTrue(written.get("day").isTextual(), "a date is text, not an array of numbers");
        assertEquals("2021-07-28", written.get("day").asText());
    }

    @Test
    @DisplayName("a timestamp is written as text that carries its offset, and no zone's name")
    void timestampIsWrittenWithItsOffset() throws IOException {
        JsonNode written = written(sample());
        assertEquals("2026-05-28T16:50:29.597415Z", written.get("instant").asText());
        assertEquals("2026-05-28T18:50:29.597415+02:00", written.get("offset").asText());
        assertEquals("2026-05-28T18:50:29.597415+02:00", written.get("zoned").asText(), "a zone's name is no part of an ISO-8601 date-time");
    }

    @Test
    @DisplayName("a zoned timestamp whose zone is a bare offset is written with the offset alone")
    void zonedTimestampAtABareOffset() throws IOException {
        Dated dated = new Dated();
        dated.setZoned(ZonedDateTime.of(LocalDateTime.of(2026, 5, 28, 16, 50, 29), ZoneOffset.UTC));
        assertEquals("2026-05-28T16:50:29Z", written(dated).get("zoned").asText());
        dated.setZoned(ZonedDateTime.of(LocalDateTime.of(2026, 5, 28, 16, 50, 29), ZoneOffset.ofHours(-5)));
        assertEquals("2026-05-28T16:50:29-05:00", written(dated).get("zoned").asText());
    }

    @Test
    @DisplayName("a date and time with no zone, and a time of day, gain no offset")
    void zonelessValuesGainNoOffset() throws IOException {
        JsonNode written = written(sample());
        assertEquals("2021-07-28T16:50:29", written.get("local").asText());
        assertEquals("16:50:29", written.get("time").asText());
    }

    @Test
    @DisplayName("a java.util.Date is written as a timestamp with its offset")
    void legacyDateIsATimestamp() throws IOException {
        Dated dated = new Dated();
        dated.setLegacy(Date.from(Instant.parse("2026-05-28T16:50:29.597Z")));
        JsonNode written = written(dated);
        assertTrue(written.get("legacy").isTextual(), "a java.util.Date is text, not a number of milliseconds");
        assertEquals(Instant.parse("2026-05-28T16:50:29.597Z"), OffsetDateTime.parse(written.get("legacy").asText()).toInstant());
        assertEquals(dated.getLegacy(), roundTrip(dated).getLegacy());
    }

    @Test
    @DisplayName("a duration is still written as a number of seconds")
    void durationIsUnchanged() throws IOException {
        Dated dated = new Dated();
        dated.setLasted(Duration.ofMillis(90_500));
        JsonNode written = written(dated);
        assertTrue(written.get("lasted").isNumber(), "a duration is a length of time, not a date");
        assertEquals(90.5, written.get("lasted").asDouble());
        assertEquals(dated.getLasted(), roundTrip(dated).getLasted());
    }

    @Test
    @DisplayName("the form written for a model is the same text")
    void summarizedFormIsTheSameText() throws IOException {
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(sample()));
        assertEquals("2021-07-28", written.get("day").asText());
        assertEquals("2026-05-28T16:50:29.597415Z", written.get("instant").asText());
        JsonNode compact = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeCompact(sample()));
        assertEquals("2021-07-28", compact.get("day").asText());
        assertEquals("2026-05-28T18:50:29.597415+02:00", compact.get("offset").asText());
    }

    @Test
    @DisplayName("every written value reads back to an equal value")
    void writtenValuesReadBackEqual() throws IOException {
        Dated original = sample();
        Dated read = roundTrip(original);
        assertEquals(original.getDay(), read.getDay());
        assertEquals(original.getLocal(), read.getLocal());
        assertEquals(original.getTime(), read.getTime());
        assertEquals(original.getInstant(), read.getInstant());
        assertEquals(original.getOffset(), read.getOffset(), "an offset timestamp keeps its offset, it is not moved to UTC");
        assertEquals(original.getZoned().toOffsetDateTime(), read.getZoned().toOffsetDateTime(), "a zoned timestamp keeps its date, time and offset");
        assertEquals(ZoneOffset.ofHours(2), read.getZoned().getZone(), "its zone is the offset it was written with");
        Dated atAnOffset = new Dated();
        atAnOffset.setZoned(ZonedDateTime.of(LocalDateTime.of(2026, 5, 28, 18, 50, 29), ZoneOffset.ofHours(2)));
        assertEquals(atAnOffset.getZoned(), roundTrip(atAnOffset).getZoned(), "a zoned timestamp whose zone is an offset reads back equal");
    }

    @Test
    @DisplayName("values at whole seconds, at nanoseconds and at the edges of a day read back equal")
    void everyPrecisionReadsBackEqual() throws IOException {
        List<Instant> instants = List.of(Instant.parse("2026-05-28T16:50:29Z"), Instant.parse("2026-05-28T16:50:29.5Z"),
                                         Instant.parse("2026-05-28T16:50:29.123456789Z"), Instant.EPOCH, Instant.parse("1969-12-31T23:59:59.999Z"),
                                         Instant.parse("2026-12-31T23:59:59.999999999Z"));
        for (Instant instant : instants) {
            Dated dated = new Dated();
            dated.setInstant(instant);
            dated.setOffset(instant.atOffset(ZoneOffset.ofHoursMinutes(5, 30)));
            dated.setZoned(instant.atZone(ZoneId.of("America/New_York")));
            dated.setLocal(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
            dated.setTime(LocalTime.ofInstant(instant, ZoneOffset.UTC));
            dated.setDay(LocalDate.ofInstant(instant, ZoneOffset.UTC));
            Dated read = roundTrip(dated);
            assertEquals(dated.getInstant(), read.getInstant(), "instant " + instant);
            assertEquals(dated.getOffset(), read.getOffset(), "offset timestamp of " + instant);
            assertEquals(dated.getZoned().toOffsetDateTime(), read.getZoned().toOffsetDateTime(), "zoned timestamp of " + instant);
            assertEquals(dated.getLocal(), read.getLocal(), "zoneless date and time of " + instant);
            assertEquals(dated.getTime(), read.getTime(), "time of day of " + instant);
            assertEquals(dated.getDay(), read.getDay(), "date of " + instant);
        }
    }

    @Test
    @DisplayName("the two zoned timestamps of a daylight-saving overlap read back as themselves")
    void daylightSavingOverlapReadsBackEqual() throws IOException {
        // 01:30 on 2026-11-01 happens twice in New York; the two are told apart by their offset
        ZonedDateTime first = ZonedDateTime.ofLocal(LocalDateTime.of(2026, 11, 1, 1, 30), ZoneId.of("America/New_York"), ZoneOffset.ofHours(-4));
        ZonedDateTime second = ZonedDateTime.ofLocal(LocalDateTime.of(2026, 11, 1, 1, 30), ZoneId.of("America/New_York"), ZoneOffset.ofHours(-5));
        for (ZonedDateTime zoned : List.of(first, second)) {
            Dated dated = new Dated();
            dated.setZoned(zoned);
            assertEquals(zoned.toOffsetDateTime(), roundTrip(dated).getZoned().toOffsetDateTime());
        }
    }

    @Test
    @DisplayName("dates in a list, as a map's values and in a nested object are written and read the same way")
    void datesInsideContainers() throws IOException {
        Dated dated = new Dated();
        dated.setDays(List.of(LocalDate.of(2021, 7, 28), LocalDate.of(2024, 2, 29)));
        Map<String, Instant> moments = new LinkedHashMap<>();
        moments.put("started", Instant.parse("2026-05-28T16:50:29Z"));
        dated.setMoments(moments);
        dated.setInner(sample());
        JsonNode written = written(dated);
        assertEquals("2024-02-29", written.get("days").get(1).asText());
        assertEquals("2026-05-28T16:50:29Z", written.get("moments").get("started").asText());
        assertEquals("2021-07-28", written.get("inner").get("day").asText());
        Dated read = roundTrip(dated);
        assertEquals(dated.getDays(), read.getDays());
        assertEquals(dated.getMoments(), read.getMoments());
        assertEquals(dated.getInner().getZoned().toOffsetDateTime(), read.getInner().getZoned().toOffsetDateTime());
    }

    @Test
    @DisplayName("a value written in the numeric forms of earlier releases is still read")
    void numericFormsAreStillRead() throws IOException {
        Dated read = NucleoJsonSerializer.parse("{\"day\":[2021,7,28],\"local\":[2021,7,28,16,50,29],\"time\":[16,50,29],"
                                                + "\"instant\":1779987029.597415000,\"offset\":1779987029.597415000,\"zoned\":1779987029.597415000,"
                                                + "\"legacy\":1779987029597,\"days\":[[2021,7,28],[2024,2,29]]}", Dated.class);
        Instant moment = Instant.parse("2026-05-28T16:50:29.597415Z");
        assertEquals(LocalDate.of(2021, 7, 28), read.getDay());
        assertEquals(LocalDateTime.of(2021, 7, 28, 16, 50, 29), read.getLocal());
        assertEquals(LocalTime.of(16, 50, 29), read.getTime());
        assertEquals(moment, read.getInstant());
        assertEquals(moment, read.getOffset().toInstant());
        assertEquals(moment, read.getZoned().toInstant());
        assertEquals(Instant.parse("2026-05-28T16:50:29.597Z"), read.getLegacy().toInstant());
        assertEquals(List.of(LocalDate.of(2021, 7, 28), LocalDate.of(2024, 2, 29)), read.getDays());
    }

    @Test
    @DisplayName("the text a model writes for a timestamp is read, with or without a zone's name")
    void modelWrittenTimestampsAreRead() throws IOException {
        Dated read = NucleoJsonSerializer.parse("{\"instant\":\"2026-05-28T18:50:29+02:00\",\"offset\":\"2026-05-28T16:50:29Z\","
                                                + "\"zoned\":\"2026-05-28T18:50:29+02:00\"}", Dated.class);
        Instant moment = Instant.parse("2026-05-28T16:50:29Z");
        assertEquals(moment, read.getInstant());
        assertEquals(OffsetDateTime.parse("2026-05-28T16:50:29Z"), read.getOffset());
        assertEquals(moment, read.getZoned().toInstant());
        assertEquals(ZoneOffset.ofHours(2), read.getZoned().getOffset());
    }
}
