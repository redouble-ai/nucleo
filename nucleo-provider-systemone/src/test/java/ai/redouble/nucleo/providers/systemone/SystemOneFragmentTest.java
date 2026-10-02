/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fragment this module ships: three decision entries that load under the catalog's
 * rules. On the TypeSafe-compatible key {@code systemone-decision}, TypeSafe's hosted Jev
 * bounded by its quota window and priced per input token, and Kev served behind a key; on
 * the local key {@code systemone-local-decision}, Kev on this machine. Each Kev is bounded by
 * one request in flight and priced at nothing, and every entry carries the state ceiling its
 * server reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
class SystemOneFragmentTest {

    private static JsonModelsBackend fragment() throws IOException {
        try (InputStream is = SystemOneFragmentTest.class.getResourceAsStream("/" + JsonModelsBackend.FRAGMENT_RESOURCE)) {
            assertNotNull(is, "the fragment rides the module's classpath");
            return new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("systemone", new String(is.readAllBytes(), StandardCharsets.UTF_8), false)));
        }
    }

    @Test
    void theFragmentCarriesJevWindowedAndPricedAndKevGatedAndFree() throws IOException {
        JsonModelsBackend catalog = fragment();
        assertEquals(List.of("jev-1.13.0", "kev", "kev-local"), catalog.all().stream().map(ModelSpec::getId).toList());
        ModelSpec jev = catalog.spec("jev-1.13.0");
        assertEquals("systemone-decision", jev.getProviderKey());
        assertEquals(ModelKind.DECISION, jev.kind());
        assertEquals("jev-1.13.0", jev.getWireModelId());
        assertEquals(64000, jev.getMaxContextTokens());
        assertEquals(15000000, jev.getTpm(), "250,000 input tokens per second, as a quota window");
        assertEquals(1200, jev.getRpm());
        assertNull(jev.getMaxConcurrent(), "a hosted endpoint is bounded by its window");
        assertEquals(0.042, jev.getInputPricePerMillion(), 1e-9);
        assertEquals("USD", jev.getCurrency());
        ModelSpec kev = catalog.spec("kev");
        assertEquals("systemone-decision", kev.getProviderKey(), "a Kev served behind a key rides the TypeSafe-compatible connection");
        ModelSpec local = catalog.spec("kev-local");
        assertEquals("systemone-local-decision", local.getProviderKey(), "a Kev on this machine rides the local connection");
        assertEquals(ModelKind.DECISION, local.kind());
        assertEquals(kev.getIdentity(), local.getIdentity(), "one model, two ways to reach it");
        for (ModelSpec either : List.of(kev, local)) {
            assertEquals("kev-latest", either.getWireModelId(), "the wire always answers as kev-latest");
            assertEquals(1, either.getMaxConcurrent(), "a Mac server computes one batch at a time");
            assertEquals(0, either.getTpm());
            assertEquals(16384, either.getMaxContextTokens());
            assertEquals(0.0, either.getInputPricePerMillion(), 1e-9, "no per-token bill");
            assertTrue(either.isPriced());
        }
    }
}
