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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Without {@code nucleo.database.max-concurrent} the Quarkus integration registers no database
 * provider: the pool's size is the host's, and no bound is inferred from it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@QuarkusTest
class NucleoDatabaseUnboundTest {

    @Inject
    NucleoDatabase database;

    @Test
    void withoutABoundNothingIsRegistered() {
        assertNull(database.provider());
        assertTrue(DBResourceProviders.names().isEmpty(), "registered: " + DBResourceProviders.names());
    }
}
