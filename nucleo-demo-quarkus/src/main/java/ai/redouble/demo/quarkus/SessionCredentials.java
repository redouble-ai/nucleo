/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import ai.redouble.demo.*;
import jakarta.enterprise.context.*;
import org.slf4j.*;

import java.util.*;

/**
 * The Quarkus host's store of credentials a person pastes into the page. It writes them into
 * {@link SessionConfigSource}, the runtime config source read ahead of the deployment's own, so
 * {@code QuarkusSecrets} sees them through {@code ConfigProvider} the way it sees any property -
 * the runtime neither knows nor cares that the value arrived from a form. Nothing here is ever
 * written to a file, echoed back over HTTP, or logged beyond the credential's id; stopping the
 * process is the only place these values live.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@ApplicationScoped
public class SessionCredentials implements SessionStore {
    private static final Logger log = LoggerFactory.getLogger(SessionCredentials.class);
    /** The runtime's credential property namespace, as QuarkusSecrets reads it. */
    static final String PREFIX = "nucleo.credentials.";

    @Override
    public void provide(String id, String user, String secret, String host) {
        put(PREFIX + id, secret);
        put(PREFIX + id + ".user", user);
        put(PREFIX + id + ".host", host);
        log.info("Session credential provided for '{}' (held in memory for this process only)", id);
    }

    private static void put(String property, String value) {
        if (value != null && !value.isBlank()) {
            SessionConfigSource.VALUES.put(property, value.strip());
        }
    }

    @Override
    public boolean holds(String id) {
        Map<String, String> values = SessionConfigSource.VALUES;
        return values.containsKey(PREFIX + id) || values.containsKey(PREFIX + id + ".user") || values.containsKey(PREFIX + id + ".host");
    }
}
