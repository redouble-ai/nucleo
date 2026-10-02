/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import org.eclipse.microprofile.config.spi.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * A MicroProfile config source over the credentials a person pastes into the page, held in memory
 * for this process only. It is the Quarkus analog of the property source the Spring host inserts
 * ahead of the rest: the runtime's {@code QuarkusSecrets} reads {@code nucleo.credentials.<id>}
 * through {@code ConfigProvider} on every lookup, so a value put here is seen the next time a
 * provider asks for its credential, through the same path {@code application.properties}, a system
 * property or an environment variable uses. Its ordinal sits above those so a session value fills
 * a gap the deployment left; a credential the deployment itself configures is refused upstream, in
 * {@link ai.redouble.demo.CatalogAdmin}, so this source never shadows one.
 *
 * <p>The values live in a static map because the config machinery loads this source through the
 * service loader while {@link SessionCredentials}, a CDI bean, is what writes to it; both reach the
 * one map. Nothing here is ever written to a file, echoed back over HTTP, or logged.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class SessionConfigSource implements ConfigSource {
    static final String NAME = "nucleo-session-credentials";
    /** Above system properties (400), environment variables (300) and application.properties (250), so a session value fills a gap the deployment left empty. */
    static final int ORDINAL = 500;
    static final Map<String, String> VALUES = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.copyOf(VALUES);
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.copyOf(VALUES.keySet());
    }

    @Override
    public String getValue(String propertyName) {
        return VALUES.get(propertyName);
    }
}
