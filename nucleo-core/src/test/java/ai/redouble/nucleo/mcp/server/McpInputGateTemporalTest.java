/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A date or time agrees with the schema published for its field: every temporal type
 * publishes a string with a format ({@code date}, {@code date-time}, {@code time}), what the
 * serializer writes for the type is admitted by the gate that judges that schema, and text of
 * another shape is refused. A consumer can therefore send back any value it was handed.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class McpInputGateTemporalTest {

    public static class Moments {
        @LLMDescription("A day")
        private LocalDate day;
        @LLMDescription("A date and time with no zone")
        private LocalDateTime local;
        @LLMDescription("A time of day")
        private LocalTime time;
        @LLMDescription("A moment")
        private Instant instant;
        @LLMDescription("A moment at an offset")
        private OffsetDateTime offset;
        @LLMDescription("A moment in a zone")
        private ZonedDateTime zoned;
        @LLMDescription("A moment, the old way")
        private Date legacy;

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
    }

    private static JsonNode published() throws Exception {
        ObjectNode schema = (ObjectNode)NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Moments.class).toJsonSchema());
        SchemaRewrites.closeObjects(schema);
        return schema;
    }

    private static Moments sample(Instant instant) {
        Moments moments = new Moments();
        moments.setDay(LocalDate.ofInstant(instant, ZoneOffset.UTC));
        moments.setLocal(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        moments.setTime(LocalTime.ofInstant(instant, ZoneOffset.UTC));
        moments.setInstant(instant);
        moments.setOffset(instant.atOffset(ZoneOffset.ofHours(2)));
        moments.setZoned(instant.atZone(ZoneId.of("Europe/Paris")));
        moments.setLegacy(Date.from(instant));
        return moments;
    }

    @Test
    @DisplayName("every temporal type publishes a string with its format")
    void everyTemporalTypePublishesItsFormat() throws Exception {
        JsonNode properties = published().path("properties");
        Map<String, String> formats = Map.of("day", "date", "local", "date-time", "time", "time", "instant", "date-time",
                                             "offset", "date-time", "zoned", "date-time", "legacy", "date-time");
        for (Map.Entry<String, String> expected : formats.entrySet()) {
            JsonNode field = properties.path(expected.getKey());
            assertEquals("string", field.path("type").asText(), expected.getKey() + " is a string: " + field);
            assertEquals(expected.getValue(), field.path("format").asText(), expected.getKey() + " states its format: " + field);
        }
    }

    @Test
    @DisplayName("what the serializer writes for each type is admitted against the published schema")
    void writtenValuesAreAdmitted() throws Exception {
        JsonNode schema = published();
        for (Instant instant : List.of(Instant.parse("2026-05-28T16:50:29.597415Z"), Instant.parse("2026-05-28T16:50:29Z"),
                                       Instant.parse("2026-12-31T23:59:59.999999999Z"), Instant.parse("2026-01-15T00:00:00Z"))) {
            JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(sample(instant)));
            assertDoesNotThrow(() -> McpInputGate.admit("moments", schema, written), "written for " + instant + ": " + written);
        }
    }

    @Test
    @DisplayName("a time of day is admitted with an offset or without, and other text is refused")
    void timeOfDayIsChecked() throws Exception {
        JsonNode schema = published();
        for (String good : List.of("16:50:29", "16:50", "16:50:29.5", "16:50:29+02:00")) {
            assertDoesNotThrow(() -> McpInputGate.admit("moments", schema, NucleoJsonSerializer.createObjectNode().put("time", good)), good);
        }
        for (String bad : List.of("2026-05-28T16:50:29", "25:00:00", "half past four")) {
            InvalidInputException refused = assertThrows(InvalidInputException.class,
                    () -> McpInputGate.admit("moments", schema, NucleoJsonSerializer.createObjectNode().put("time", bad)), bad);
            assertTrue(refused.getMessage().contains("time") && refused.getMessage().contains("hh:mm:ss"), refused.getMessage());
            assertFalse(refused.getMessage().contains(bad), "the refusal repeats nothing it was sent: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("a timestamp followed by a zone's name is no date-time and is refused")
    void zoneNameIsRefused() throws Exception {
        JsonNode schema = published();
        assertThrows(InvalidInputException.class, () -> McpInputGate.admit("moments", schema,
                NucleoJsonSerializer.createObjectNode().put("zoned", "2026-05-28T18:50:29+02:00[Europe/Paris]")));
    }
}
