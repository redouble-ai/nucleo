/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReadOnlyPalette}, the enforcement behind a read-only thinker.
 *
 * <p>What these pin down is that the two enforcement points disagree in the one way that
 * matters: the sweep only shapes what a thinker offers, while the admission check is what
 * actually holds when the registry is widened after the offer was built. A test that only
 * exercised the sweep would pass against an implementation with no guarantee at all.
 *
 * <p>They also pin the annotation default and the one-way latch, because both are load
 * bearing: an unmarked tool must be treated as mutating, and read-only must not be
 * revocable.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-06)
 */
public class ReadOnlyPaletteTest {

    private static final String OWNER = "TestSkeptic";

    public static class Input {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @ToolName("reader")
    @ToolDescription(value = "Reads a thing and changes nothing", readOnly = true)
    public static class ReaderTool extends AbstractTool<Input, String> {
        public ReaderTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "read";
        }
    }

    @ToolName("second_reader")
    @ToolDescription(value = "Also reads a thing", readOnly = true)
    public static class SecondReaderTool extends AbstractTool<Input, String> {
        public SecondReaderTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "read";
        }
    }

    @ToolName("writer")
    @ToolDescription("Writes a thing")
    public static class WriterTool extends AbstractTool<Input, String> {
        public WriterTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "wrote";
        }
    }

    @Test
    void annotationDefaultMakesAToolMutating() {
        assertFalse(ClassToolProvider.of(WriterTool.class).readOnly(),
                "a tool that does not declare readOnly must not be treated as read-only");
        assertTrue(ClassToolProvider.of(ReaderTool.class).readOnly());
    }

    @Test
    void sweepWithholdsMutatingToolsAndKeepsReaders() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ReaderTool.class);
        registry.register(WriterTool.class);
        registry.register(SecondReaderTool.class);

        ReadOnlyPalette.sweep(registry, OWNER);

        assertEquals(2, registry.getAllProviders().size());
        assertTrue(registry.hasTool("reader"));
        assertTrue(registry.hasTool("second_reader"));
        assertFalse(registry.hasTool("writer"));
    }

    @Test
    void sweepIsIdempotent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ReaderTool.class);
        registry.register(WriterTool.class);

        ReadOnlyPalette.sweep(registry, OWNER);
        ReadOnlyPalette.sweep(registry, OWNER);

        assertEquals(1, registry.getAllProviders().size());
        assertTrue(registry.hasTool("reader"));
    }

    @Test
    void subThinkerIsWithheldByTheSameRuleAsAnyMutatingTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(SubThinker.class);
        registry.register(ReaderTool.class);

        ReadOnlyPalette.sweep(registry, OWNER);

        assertFalse(registry.hasTool("sub_thinker"),
                "delegation must not survive the sweep - a sub-agent does not inherit the parent's constraints");
    }

    @Test
    void admissionAllowsAReadOnlyTool() throws GuardrailException {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ReaderTool.class);

        ReadOnlyPalette.requireReadOnly(registry, "reader", OWNER);
    }

    @Test
    void admissionRefusesAMutatingToolEvenWhenTheSweepNeverRan() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(WriterTool.class);

        GuardrailException thrown = assertThrows(GuardrailException.class,
                () -> ReadOnlyPalette.requireReadOnly(registry, "writer", OWNER));
        assertTrue(thrown.getMessage().contains("writer"));
    }

    @Test
    void admissionRefusesAToolAddedAfterTheDefinitionsWereBuilt() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ReaderTool.class);
        ReadOnlyPalette.sweep(registry, OWNER);
        // Whatever widens the registry mid-turn - request_tools, a ToolHub system-wide
        // tool, a direct addTool - lands after the offer was made. Admission is the only
        // point that still sees it.
        registry.register(WriterTool.class);

        assertThrows(GuardrailException.class,
                () -> ReadOnlyPalette.requireReadOnly(registry, "writer", OWNER));
    }

    @Test
    void admissionRefusesAnUnknownToolName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(ReaderTool.class);

        assertThrows(GuardrailException.class,
                () -> ReadOnlyPalette.requireReadOnly(registry, "never_registered", OWNER));
    }

    @Test
    void requestToolsIsWithheldWithoutASpecialCase() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequestToolsProvider(Set.of(ClassToolProvider.of(ReaderTool.class))));
        registry.register(ReaderTool.class);

        ReadOnlyPalette.sweep(registry, OWNER);

        assertFalse(registry.hasTool(RequestToolsProvider.NAME),
                "request_tools admits arbitrary tools, so it must not survive the sweep");
        assertTrue(registry.hasTool("reader"));
    }

    @Test
    void forceReadOnlyIsFixedAtConstruction() throws NoSuchFieldException {
        // The binding must cover the thinker's whole life, not the part after someone
        // remembered to ask for it: a supervisor is trustworthy because it never ran
        // anything mutating, not because it stopped. A final field is what makes that
        // true, so nothing may reintroduce a way to change it after construction.
        java.lang.reflect.Field field = AbstractThinker.class.getDeclaredField("forceReadOnly");
        assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                "AbstractThinker.forceReadOnly must be final");
        for (java.lang.reflect.Method method : AbstractThinker.class.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            boolean takesBoolean = params.length == 1
                    && (params[0] == boolean.class || params[0] == Boolean.class);
            assertFalse(takesBoolean && method.getName().toLowerCase().contains("readonly"),
                    "AbstractThinker must expose no way to change read-only after construction");
        }
    }
}
