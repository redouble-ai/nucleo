/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The coercions the serializer applies to values a model spelled its own way, as the package
 * documentation lists them: a boolean from its usual spellings and from 0 and 1; an integer
 * from a whole-number float or its text, an empty string as null, a fraction refused; a date
 * and a date-time from any lenient shape or from a numeric array, a date-time contributing its
 * date part to a date and a date alone reading as the start of its day for a date-time; an enum
 * by exact then
 * case-insensitive name, or from an object whose first textual member names it. Anything the
 * coercion cannot read is refused as a mapping failure, never guessed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class NucleoJsonSerializerCoercionTest {

    public enum Mood { CALM, EAGER }

    public static class Typed {
        private Boolean flag;
        private boolean primitiveFlag;
        private Integer count;
        private LocalDate day;
        private LocalDateTime moment;
        private Mood mood;

        public Boolean getFlag() { return flag; }
        public void setFlag(Boolean flag) { this.flag = flag; }
        public boolean isPrimitiveFlag() { return primitiveFlag; }
        public void setPrimitiveFlag(boolean primitiveFlag) { this.primitiveFlag = primitiveFlag; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
        public LocalDate getDay() { return day; }
        public void setDay(LocalDate day) { this.day = day; }
        public LocalDateTime getMoment() { return moment; }
        public void setMoment(LocalDateTime moment) { this.moment = moment; }
        public Mood getMood() { return mood; }
        public void setMood(Mood mood) { this.mood = mood; }
    }

    private static Typed read(String json) throws IOException {
        return NucleoJsonSerializer.parse(json, Typed.class);
    }

    @Test
    void booleansReadFromTheirUsualSpellingsAndFromZeroAndOne() throws IOException {
        assertEquals(Boolean.TRUE, read("{\"flag\": \"yes\"}").getFlag(), "a yes spelling");
        assertEquals(Boolean.FALSE, read("{\"flag\": \"N\"}").getFlag(), "a no spelling, any case");
        assertEquals(Boolean.TRUE, read("{\"flag\": 1}").getFlag(), "the number one");
        assertEquals(Boolean.FALSE, read("{\"flag\": \"0\"}").getFlag(), "the text zero");
        assertTrue(read("{\"primitive_flag\": \"true\"}").isPrimitiveFlag(), "the same for a primitive");
        assertNull(read("{\"flag\": null}").getFlag());
        assertFalse(read("{\"primitive_flag\": null}").isPrimitiveFlag(), "a null for a primitive boolean reads as false, never as a failure");
        assertThrows(IOException.class, () -> read("{\"flag\": \"perhaps\"}"), "an unrecognised spelling is refused");
        assertThrows(IOException.class, () -> read("{\"flag\": 2}"), "a number other than 0 or 1 is refused");
    }

    @Test
    void integersReadFromWholeFloatsAndTextAndRefuseFractions() throws IOException {
        assertEquals(3, read("{\"count\": 3.0}").getCount(), "a whole-number float");
        assertEquals(4, read("{\"count\": \"4\"}").getCount(), "a number spelled as text");
        assertEquals(5, read("{\"count\": \"5.0\"}").getCount(), "a whole-number float spelled as text");
        assertNull(read("{\"count\": \"\"}").getCount(), "an empty string is null");
        assertThrows(IOException.class, () -> read("{\"count\": 3.5}"), "a fraction is refused");
        assertThrows(IOException.class, () -> read("{\"count\": \"many\"}"), "text that is not a number is refused");
    }

    @Test
    void datesReadFromLenientTextAndFromNumericArrays() throws IOException {
        assertEquals(LocalDate.of(2024, 12, 31), read("{\"day\": \"12/31/2024\"}").getDay(), "a US date shape");
        assertEquals(LocalDate.of(2024, 12, 31), read("{\"day\": \"2024-12-31\"}").getDay(), "ISO");
        assertEquals(LocalDate.of(2024, 12, 31), read("{\"day\": [2024, 12, 31]}").getDay(), "the numeric array form");
        assertEquals(LocalDate.of(2024, 12, 31), read("{\"day\": \"2024-12-31T10:15:30\"}").getDay(), "a date-time contributes its date part");
        assertNull(read("{\"day\": \"\"}").getDay(), "an empty string is null");
        assertThrows(IOException.class, () -> read("{\"day\": \"someday\"}"), "text no format fits is refused");
    }

    @Test
    void dateTimesReadFromLenientTextAndFromNumericArrays() throws IOException {
        LocalDateTime morning = LocalDateTime.of(2024, 12, 31, 10, 15, 30);
        assertEquals(morning, read("{\"moment\": \"2024-12-31T10:15:30\"}").getMoment(), "ISO local date-time");
        assertEquals(morning, read("{\"moment\": \"31-Dec-2024 10:15:30\"}").getMoment(), "a lenient shape");
        assertEquals(morning, read("{\"moment\": [2024, 12, 31, 10, 15, 30]}").getMoment(), "the numeric array form with seconds");
        assertEquals(morning.withSecond(0), read("{\"moment\": [2024, 12, 31, 10, 15]}").getMoment(), "the numeric array form without seconds");
        assertEquals(LocalDateTime.of(2024, 12, 31, 0, 0), read("{\"moment\": \"2024-12-31\"}").getMoment(), "a date alone is the start of that day");
        assertThrows(IOException.class, () -> read("{\"moment\": \"whenever\"}"), "text no format fits is refused");
    }

    @Test
    void enumsReadByNameThenCaseInsensitivelyThenFromAnObject() throws IOException {
        assertEquals(Mood.CALM, read("{\"mood\": \"CALM\"}").getMood(), "the exact constant");
        assertEquals(Mood.EAGER, read("{\"mood\": \" eager \"}").getMood(), "any case, whitespace ignored");
        assertEquals(Mood.CALM, read("{\"mood\": {\"value\": \"calm\"}}").getMood(), "an object whose first textual member names the constant");
        assertThrows(IOException.class, () -> read("{\"mood\": \"furious\"}"), "a name that is no constant is refused");
        assertThrows(IOException.class, () -> read("{\"mood\": {\"n\": 1}}"), "an object with no textual member is refused");
    }

    @Test
    void whatTheCoercionRefusesReadsAsAMappingFailureThatNamesTheValue() {
        IOException refusal = assertThrows(IOException.class, () -> read("{\"count\": \"many\"}"));
        assertTrue(refusal.getMessage().contains("many"), "the failure names the value it could not read: " + refusal.getMessage());
    }

    @Test
    void coercionsApplyToTheRawLlmPathToo() throws IOException {
        Typed typed = NucleoJsonSerializer.parseLLMResponse("Here you go:\n```json\n{\"flag\": \"yes\", \"count\": \"2\", \"mood\": \"eager\"}\n```", Typed.class);
        assertEquals(Boolean.TRUE, typed.getFlag());
        assertEquals(2, typed.getCount());
        assertEquals(Mood.EAGER, typed.getMood());
    }
}
