/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Reflection}: a configured class by binary name, an instance through its no-arg
 * constructor made accessible when it is not public, and an {@link IllegalStateException}
 * carrying the cause when the class cannot be found or built. There is no fallback.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class ReflectionTest {

    /** The shape of a configured class: public type, constructor deliberately not public. */
    public static class PrivatelyConstructed {
        private PrivatelyConstructed() {
        }
    }

    /** A class the runtime cannot build: no no-arg constructor. */
    public static class NeedsAnArgument {
        public NeedsAnArgument(String argument) {
        }
    }

    @Test
    void aClassIsFoundByItsBinaryName() {
        assertSame(PrivatelyConstructed.class, Reflection.forName(PrivatelyConstructed.class.getName()),
                "the binary name (with $ for a nested class) resolves to the class");
    }

    @Test
    void anUnknownNameStopsWithTheCauseAttached() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Reflection.forName("no.such.Configured"));
        assertInstanceOf(ClassNotFoundException.class, failure.getCause(), "the JVM's own failure is the cause");
        assertTrue(failure.getMessage().contains("no.such.Configured"), "the failure names the class: " + failure.getMessage());
    }

    @Test
    void aNonPublicNoArgConstructorIsMadeAccessible() {
        PrivatelyConstructed byType = Reflection.newInstance(PrivatelyConstructed.class);
        assertNotNull(byType, "a private constructor is no obstacle to a configured class");
        assertInstanceOf(PrivatelyConstructed.class, Reflection.newInstance(PrivatelyConstructed.class.getName()),
                "the name form finds the class and builds it the same way");
    }

    @Test
    void aClassWithoutANoArgConstructorStopsWithTheCauseAttached() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Reflection.newInstance(NeedsAnArgument.class));
        assertInstanceOf(NoSuchMethodException.class, failure.getCause(), "the reflective failure is the cause");
        assertTrue(failure.getMessage().contains(NeedsAnArgument.class.getName()), "the failure names the class: " + failure.getMessage());
    }
}
