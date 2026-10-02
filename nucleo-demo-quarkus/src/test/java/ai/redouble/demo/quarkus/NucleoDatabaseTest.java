/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.quarkus.*;
import io.quarkus.test.junit.*;
import jakarta.inject.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Quarkus integration binds {@code nucleo.database.*} from MicroProfile Config onto the
 * core {@link DatabaseSettings} and registers the counting provider under that name and bound,
 * in an application with no datasource at all.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@QuarkusTest
@TestProfile(NucleoDatabaseTest.Bounded.class)
class NucleoDatabaseTest {

    /** The demo with a database bound set. */
    public static class Bounded implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("nucleo.database.max-concurrent", "4", "nucleo.database.name", "quarkus-orders");
        }
    }

    @Inject
    NucleoDatabase database;

    @Test
    void theBoundPropertyRegistersTheCounter() {
        CountingDBResourceProvider provider = DBResourceProviders.get("quarkus-orders", CountingDBResourceProvider.class);
        assertSame(provider, database.provider());
        assertEquals(4, ((DatabaseGate) provider.admission()).capacity(), "the bound the property set");
    }
}
