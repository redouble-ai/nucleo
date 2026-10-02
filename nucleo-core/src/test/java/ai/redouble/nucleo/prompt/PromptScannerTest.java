/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.prompt.sources.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link PromptScanner} discovers and registers valid {@link StaticPrompt} and
 * {@link DynamicPrompt} placements (static final String, static final PromptSource, static
 * no-arg method, TYPE on a PromptSource class), rejects every static/dynamic contract
 * violation with a {@link PromptRegistrationException} naming the rule (marker mismatches
 * on TYPE, FIELD and METHOD, a String under {@code @DynamicPrompt}, a non-final field,
 * both annotations on one element, a missing no-arg constructor), auto-derives the key
 * from the declaration when {@code value()} is empty, and skips instance members (the
 * thinker runtime registers those).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class PromptScannerTest {
    @BeforeEach
    void reset() {
        Prompts.resetAll();
    }

    public static final class ValidStringDecls {
        @StaticPrompt("scanner.test.str")
        public static final String STR = "hello from string";
    }

    public static final class ValidSourceFieldDecls {
        @StaticPrompt("scanner.test.src-field")
        public static final StaticPromptSource SRC = new StaticTextSource("from-field");
    }

    public static final class ValidMethodDecls {
        @StaticPrompt("scanner.test.src-method")
        public static StaticPromptSource supply() {
            return new StaticTextSource("from-method");
        }
    }

    @StaticPrompt("scanner.test.type")
    public static class ValidTypeDecl implements StaticPromptSource {
        public ValidTypeDecl() {}
        @Override
        public JsonNode produce(String key) {
            return TextNode.valueOf("from-type");
        }
    }

    public static final class DynamicSourceFieldDecls {
        @DynamicPrompt("scanner.test.dynamic-field")
        public static final PromptSource SRC = key -> TextNode.valueOf("from-dynamic-field");
    }

    @Test
    void stringFieldRegistersAsStaticTextSource() throws Exception {
        Prompts.scanPackage(ValidStringDecls.class.getPackageName());
        assertEquals("hello from string", Prompts.produce("scanner.test.str").content().asText());
    }

    @Test
    void staticPromptSourceFieldRegistersDirectly() throws Exception {
        Prompts.scanPackage(ValidSourceFieldDecls.class.getPackageName());
        assertEquals("from-field", Prompts.produce("scanner.test.src-field").content().asText());
    }

    @Test
    void staticMethodFactoryInvokedOnce() throws Exception {
        Prompts.scanPackage(ValidMethodDecls.class.getPackageName());
        assertEquals("from-method", Prompts.produce("scanner.test.src-method").content().asText());
    }

    @Test
    void staticTypeAnnotationInstantiatedAndRegistered() throws Exception {
        Prompts.scanPackage(ValidTypeDecl.class.getPackageName());
        assertEquals("from-type", Prompts.produce("scanner.test.type").content().asText());
    }

    @Test
    void annotatedTypeThatIsNotAPromptSourceIsRejectedLoudly() {
        // the invalid fixture lives entirely outside ai.redouble so no framework scan trips over it
        assertThrows(PromptRegistrationException.class,
                () -> Prompts.scanPackage("scannerrejects"),
                "a contract violation fails the scan instead of silently registering garbage");
    }

    @Test
    void dynamicPromptSourceFieldRegistersAndReproduces() throws Exception {
        Prompts.scanPackage(DynamicSourceFieldDecls.class.getPackageName());
        assertEquals("from-dynamic-field", Prompts.produce("scanner.test.dynamic-field").content().asText());
    }

    @Test
    void everyStaticDynamicContractViolationIsRefusedNamingTheRule() {
        // One fixture package per violation - a scan aborts at the first refusal, so
        // isolating them keeps each check deterministic. PromptScanner.scan is called
        // directly to sidestep the facade's one-scan-per-process guard.
        assertScanRefusal("scannerbad.typemarkermissing", "must implement StaticPromptSource");
        assertScanRefusal("scannerbad.typemarkerwrong", "must NOT implement StaticPromptSource");
        assertScanRefusal("scannerbad.noctor", "requires a no-arg constructor");
        assertScanRefusal("scannerbad.dynstring", "use @StaticPrompt for String constants");
        assertScanRefusal("scannerbad.nonfinal", "must be final");
        assertScanRefusal("scannerbad.both", "pick one");
        assertScanRefusal("scannerbad.fieldmarker", "must hold a StaticPromptSource");
        assertScanRefusal("scannerbad.methodmarker", "must NOT return a StaticPromptSource");
    }

    private static void assertScanRefusal(String pkg, String rule) {
        PromptRegistrationException refusal = assertThrows(PromptRegistrationException.class,
                () -> PromptScanner.scan(pkg), pkg);
        assertTrue(refusal.getMessage().contains(rule),
                pkg + " must be refused naming its rule, got: " + refusal.getMessage());
    }

    @Test
    void anEmptyAnnotationValueAutoDerivesTheKey_andInstanceMembersAreSkipped() throws Exception {
        PromptScanner.scan("scannerauto");
        assertEquals("hello auto", Prompts.produce("scannerauto.AutoKeyDecls.GREETING").content().asText(),
                "the key auto-derives as <declaring-class-fqn>.<member-name>");
        assertThrows(PromptNotFoundException.class,
                () -> Prompts.produce("scannerauto.AutoKeyDecls.instanceNote"),
                "an annotated INSTANCE member belongs to the thinker runtime - the scanner skips it");
    }
}
