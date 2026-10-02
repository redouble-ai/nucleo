/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.jdbc.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DBResourceProviders}: a name is one datasource, a lookup is typed, and a miss names
 * what is registered.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class DBResourceProvidersTest {

    @Test
    void aRegisteredProviderIsFoundByNameAsTheTypeTheCallerDeclares() {
        JdbcResourceProvider provider = new JdbcResourceProvider("registry-found", null, 1);
        DBResourceProviders.register(provider);
        try {
            assertSame(provider, DBResourceProviders.get("registry-found", JdbcResourceProvider.class));
            assertTrue(DBResourceProviders.names().contains("registry-found"));
            DBResourceProviders.register(provider);
            assertSame(provider, DBResourceProviders.get("registry-found", JdbcResourceProvider.class), "registering the same instance again is a no-op");
        }
        finally {
            DBResourceProviders.unregister(provider);
        }
        assertFalse(DBResourceProviders.names().contains("registry-found"), "unregistered");
    }

    @Test
    void aSecondProviderUnderARegisteredNameIsRefused() {
        JdbcResourceProvider first = new JdbcResourceProvider("registry-taken", null, 1);
        DBResourceProviders.register(first);
        try {
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> DBResourceProviders.register(new JdbcResourceProvider("registry-taken", null, 1)));
            assertTrue(refusal.getMessage().contains("registry-taken"), refusal.getMessage());
            assertSame(first, DBResourceProviders.get("registry-taken", JdbcResourceProvider.class), "the first stays");
        }
        finally {
            DBResourceProviders.unregister(first);
        }
    }

    @Test
    void aMissNamesTheRegisteredProviders_andAWrongTypeNamesBoth() {
        JdbcResourceProvider provider = new JdbcResourceProvider("registry-typed", null, 1);
        DBResourceProviders.register(provider);
        try {
            IllegalStateException miss = assertThrows(IllegalStateException.class,
                    () -> DBResourceProviders.get("registry-absent", JdbcResourceProvider.class));
            assertTrue(miss.getMessage().contains("registry-absent") && miss.getMessage().contains("registry-typed"), miss.getMessage());
            IllegalStateException wrongType = assertThrows(IllegalStateException.class,
                    () -> DBResourceProviders.get("registry-typed", OtherProvider.class));
            assertTrue(wrongType.getMessage().contains(JdbcResourceProvider.class.getName())
                       && wrongType.getMessage().contains(OtherProvider.class.getName()), wrongType.getMessage());
        }
        finally {
            DBResourceProviders.unregister(provider);
        }
    }

    @Test
    void theDefaultIsACounterFromTheSettings_andNothingWithoutABound() {
        DatabaseSettings settings = Settings.get(DatabaseSettings.class);
        String nameBefore = settings.name;
        Integer boundBefore = settings.maxConcurrent;
        try {
            settings.maxConcurrent = null;
            assertNull(DBResourceProviders.registerDefault(), "no bound, no provider: the pool's size is the host's");
            settings.name = "registry-default";
            settings.maxConcurrent = 7;
            CountingDBResourceProvider provider = DBResourceProviders.registerDefault();
            assertSame(provider, DBResourceProviders.get("registry-default", CountingDBResourceProvider.class));
            assertEquals(7, ((DatabaseGate) provider.admission()).capacity(), "the bound the settings name");
            DBResourceProviders.unregister(provider);
        }
        finally {
            settings.name = nameBefore;
            settings.maxConcurrent = boundBefore;
        }
    }

    /** A provider type nothing registers under the name, for the type refusal. */
    private abstract static class OtherProvider implements DBResourceProvider<Void> {
    }
}
