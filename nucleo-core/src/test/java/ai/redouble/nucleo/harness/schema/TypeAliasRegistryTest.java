/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.unaliased.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TypeAliasRegistry}: a class registers under its {@code @TypeAlias}, lazily on the first
 * {@code getAlias} if nothing registered it before; a class without the annotation registers as
 * nothing and has no alias; one alias on two classes is refused; {@code resolveAlias} is exact,
 * {@code resolveAliasWithFallback} climbs the colon hierarchy to the nearest registered parent
 * and answers null past the root; {@code init} scans the named packages and no more, refuses a
 * package with a concrete {@link Artifact} implementor that carries no annotation by naming it,
 * succeeds on a package free of them whatever else its classpath root holds, and runs once.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class TypeAliasRegistryTest {

    @TypeAlias("registry-test:parent")
    public static class Parent extends AbstractArtifact {
    }

    @TypeAlias("registry-test:parent:child")
    public static class Child extends Parent {
    }

    public static class Unaliased {
    }

    /**
     * Defines a second {@link Class} object from the same bytes, so two classes carry one alias
     * without a second annotated class on the classpath for a scan to find.
     */
    static final class TwinLoader extends ClassLoader {
        TwinLoader() {
            super(TypeAliasRegistryTest.class.getClassLoader());
        }

        Class<?> twinOf(Class<?> type) throws IOException {
            try (InputStream in = getParent().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
                byte[] bytes = in.readAllBytes();
                return defineClass(type.getName(), bytes, 0, bytes.length);
            }
        }
    }

    @Test
    void aClassRegistersUnderItsAliasLazilyAndBothWays() {
        assertEquals("registry-test:parent", TypeAliasRegistry.getAlias(Parent.class), "the first lookup registers the class");
        assertSame(Parent.class, TypeAliasRegistry.resolveAlias("registry-test:parent"), "and the alias resolves back to it");
    }

    @Test
    void aClassWithoutTheAnnotationHasNoAlias() {
        TypeAliasRegistry.register(Unaliased.class);
        assertNull(TypeAliasRegistry.getAlias(Unaliased.class), "register ignores it, getAlias answers null");
    }

    @Test
    void oneAliasOnTwoClassesIsRefused() throws IOException {
        TypeAliasRegistry.register(Parent.class);
        Class<?> twin = new TwinLoader().twinOf(Parent.class);
        assertNotSame(Parent.class, twin, "a second class object carrying the same alias");
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> TypeAliasRegistry.register(twin));
        assertTrue(refusal.getMessage().contains("registry-test:parent"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(Parent.class.getName()), "the classes are named: " + refusal.getMessage());
        assertSame(Parent.class, TypeAliasRegistry.resolveAlias("registry-test:parent"), "the first registration stands");
    }

    @Test
    void resolutionIsExactUnlessAskedToClimbTheHierarchy() {
        TypeAliasRegistry.register(Parent.class);
        TypeAliasRegistry.register(Child.class);
        assertNull(TypeAliasRegistry.resolveAlias("registry-test:parent:child:grandchild"), "exact lookup knows no hierarchy");
        assertSame(Child.class, TypeAliasRegistry.resolveAliasWithFallback("registry-test:parent:child:grandchild"), "the nearest registered parent");
        assertSame(Child.class, TypeAliasRegistry.resolveAliasWithFallback("registry-test:parent:child"), "an exact match first");
        assertSame(Parent.class, TypeAliasRegistry.resolveAliasWithFallback("registry-test:parent:other"), "one level up");
        assertNull(TypeAliasRegistry.resolveAliasWithFallback("nobody:knows:this"), "nothing registered at any level");
        assertNull(TypeAliasRegistry.resolveAliasWithFallback(null));
    }

    @Test
    void initScansTheNamedPackagesOnlyRefusesAnUnannotatedArtifactByNameAndRunsOnce() {
        // The package holding Bare refuses, naming the class
        MissingTypeAliasException refusal = assertThrows(MissingTypeAliasException.class,
                () -> TypeAliasRegistry.init(Bare.class.getPackageName()));
        assertEquals(Set.of(Bare.class), refusal.getOffendingClasses(), "the unannotated implementor is named, and nothing outside its package");
        assertTrue(refusal.getMessage().contains(Bare.class.getName()), refusal.getMessage());
        assertFalse(TypeAliasRegistry.isInitialized(), "a refused scan leaves the registry uninitialized");
        // A sibling package in the same classpath root, holding no artifact, succeeds although
        // Bare shares the root; the runtime's own package comes along and its aliases register
        TypeAliasRegistry.init(ai.redouble.nucleo.harness.schema.twins.Inner.class.getPackageName(), LinkArtifact.class.getPackageName());
        assertTrue(TypeAliasRegistry.isInitialized());
        assertSame(LinkArtifact.class, TypeAliasRegistry.resolveAlias("link"), "the scan registered the runtime's classes");
        // Once initialized, a later call is a no-op, so the refusing package no longer refuses
        assertDoesNotThrow(() -> TypeAliasRegistry.init(Bare.class.getPackageName()), "init runs once per process");
    }

    @Test
    void theMissingAliasExceptionNamesEveryOffendingClass() {
        MissingTypeAliasException failure = new MissingTypeAliasException(new LinkedHashSet<>(List.of(Unaliased.class, Object.class)));
        assertEquals(Set.of(Unaliased.class, Object.class), failure.getOffendingClasses());
        assertThrows(UnsupportedOperationException.class, () -> failure.getOffendingClasses().clear(), "the set is read-only");
        assertTrue(failure.getMessage().startsWith("Artifact classes missing @TypeAlias:"), failure.getMessage());
        assertTrue(failure.getMessage().contains(Unaliased.class.getName()) && failure.getMessage().contains("java.lang.Object"), failure.getMessage());
    }
}
