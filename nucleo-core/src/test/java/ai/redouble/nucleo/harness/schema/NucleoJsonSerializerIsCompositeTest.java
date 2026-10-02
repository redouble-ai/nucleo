/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import org.junit.jupiter.api.*;

import java.math.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NucleoJsonSerializer#isComposite(Class)}.
 *
 * <p>Verifies that the leaf/composite classification correctly distinguishes
 * user-defined POJOs (recurse) from JDK scalar/temporal/container types (leaf).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class NucleoJsonSerializerIsCompositeTest {

    public static class UserPojo {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public enum Color { RED, GREEN, BLUE }

    @Test
    public void nullIsLeaf() {
        assertFalse(NucleoJsonSerializer.isComposite(null));
    }

    @Test
    public void objectClassIsLeaf() {
        assertFalse(NucleoJsonSerializer.isComposite(Object.class));
    }

    @Test
    public void primitivesAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(int.class));
        assertFalse(NucleoJsonSerializer.isComposite(long.class));
        assertFalse(NucleoJsonSerializer.isComposite(double.class));
        assertFalse(NucleoJsonSerializer.isComposite(boolean.class));
        assertFalse(NucleoJsonSerializer.isComposite(char.class));
        assertFalse(NucleoJsonSerializer.isComposite(byte.class));
    }

    @Test
    public void primitiveWrappersAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(Integer.class));
        assertFalse(NucleoJsonSerializer.isComposite(Long.class));
        assertFalse(NucleoJsonSerializer.isComposite(Double.class));
        assertFalse(NucleoJsonSerializer.isComposite(Boolean.class));
        assertFalse(NucleoJsonSerializer.isComposite(Character.class));
    }

    @Test
    public void stringIsLeaf() {
        assertFalse(NucleoJsonSerializer.isComposite(String.class));
        assertFalse(NucleoJsonSerializer.isComposite(CharSequence.class));
    }

    @Test
    public void bigNumericsAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(BigDecimal.class));
        assertFalse(NucleoJsonSerializer.isComposite(BigInteger.class));
    }

    @Test
    public void javaTimeTypesAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(Instant.class));
        assertFalse(NucleoJsonSerializer.isComposite(LocalDate.class));
        assertFalse(NucleoJsonSerializer.isComposite(LocalDateTime.class));
        assertFalse(NucleoJsonSerializer.isComposite(ZonedDateTime.class));
        assertFalse(NucleoJsonSerializer.isComposite(OffsetDateTime.class));
        assertFalse(NucleoJsonSerializer.isComposite(Duration.class));
        assertFalse(NucleoJsonSerializer.isComposite(Period.class));
        assertFalse(NucleoJsonSerializer.isComposite(Year.class));
        assertFalse(NucleoJsonSerializer.isComposite(YearMonth.class));
        assertFalse(NucleoJsonSerializer.isComposite(MonthDay.class));
    }

    @Test
    public void miscLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(UUID.class));
        assertFalse(NucleoJsonSerializer.isComposite(Date.class));
        assertFalse(NucleoJsonSerializer.isComposite(Locale.class));
        assertFalse(NucleoJsonSerializer.isComposite(Currency.class));
    }

    @Test
    public void enumsAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(Color.class));
    }

    @Test
    public void arraysAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(int[].class));
        assertFalse(NucleoJsonSerializer.isComposite(String[].class));
        assertFalse(NucleoJsonSerializer.isComposite(UserPojo[].class));
    }

    @Test
    public void collectionsAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(List.class));
        assertFalse(NucleoJsonSerializer.isComposite(ArrayList.class));
        assertFalse(NucleoJsonSerializer.isComposite(Set.class));
        assertFalse(NucleoJsonSerializer.isComposite(HashSet.class));
    }

    @Test
    public void mapsAreLeaves() {
        assertFalse(NucleoJsonSerializer.isComposite(Map.class));
        assertFalse(NucleoJsonSerializer.isComposite(HashMap.class));
        assertFalse(NucleoJsonSerializer.isComposite(LinkedHashMap.class));
    }

    @Test
    public void jdkTypesNotInLeafSetAreLeaves() {
        // These are the types the leaf set doesn't explicitly enumerate,
        // but the java.* package filter should catch them.
        assertFalse(NucleoJsonSerializer.isComposite(URL.class));
        assertFalse(NucleoJsonSerializer.isComposite(URI.class));
        assertFalse(NucleoJsonSerializer.isComposite(Path.class));
        assertFalse(NucleoJsonSerializer.isComposite(Optional.class));
        assertFalse(NucleoJsonSerializer.isComposite(OptionalInt.class));
        assertFalse(NucleoJsonSerializer.isComposite(java.sql.Timestamp.class));
        assertFalse(NucleoJsonSerializer.isComposite(java.sql.Date.class));
    }

    @Test
    public void userDefinedPojoIsComposite() {
        assertTrue(NucleoJsonSerializer.isComposite(UserPojo.class));
    }
}
