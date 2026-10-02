/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.spring;

import ai.redouble.demo.*;
import org.slf4j.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Credentials a person pastes into the page, held for this process only. They land as a
 * runtime {@link MapPropertySource} placed ahead of every other source, so the runtime's
 * Boot-property store ({@code SpringSecrets}, reading {@code nucleo.credentials.<id>}) sees
 * them through the same machinery a vault or an environment variable uses - the runtime
 * neither knows nor cares that the value arrived from a form. Nothing here is ever written
 * to a file, echoed back over HTTP, or logged beyond the credential's id; stopping the
 * process is the only place these values live.
 *
 * <p>A credential the deployment already holds is refused rather than shadowed: the app's
 * clients are constructed once around a credential and shared, so replacing one mid-process
 * would leave live clients on the old value and new ones on the new - a lie on the status
 * page. Session credentials fill gaps, they never override the deployment's. A credential
 * this store itself holds may be corrected - a mistyped endpoint must not need a restart -
 * and the caller evicts the affected providers' cached clients in the same breath.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
@Component
public class SessionCredentials implements SessionStore {
    private static final Logger log = LoggerFactory.getLogger(SessionCredentials.class);
    static final String SOURCE_NAME = "nucleo-session-credentials";
    /** The runtime's credential property namespace, as SpringSecrets reads it. */
    static final String PREFIX = "nucleo.credentials.";
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public SessionCredentials(ConfigurableEnvironment environment) {
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));
    }

    /**
     * Holds one credential for the rest of this process: the id is the runtime's record name
     * ({@code anthropic-api-key}, {@code aws-region}); user, secret and host are its parts,
     * blank parts left unset. Effective from the next lookup - the property source is live.
     */
    public void provide(String id, String user, String secret, String host) {
        put(PREFIX + id, secret);
        put(PREFIX + id + ".user", user);
        put(PREFIX + id + ".host", host);
        log.info("Session credential provided for '{}' (held in memory for this process only)", id);
    }

    private void put(String property, String value) {
        if (value != null && !value.isBlank()) {
            values.put(property, value.strip());
        }
    }

    /** Whether this store holds any part of the credential - a session credential may be corrected, a deployment's never. */
    public boolean holds(String id) {
        return values.containsKey(PREFIX + id) || values.containsKey(PREFIX + id + ".user") || values.containsKey(PREFIX + id + ".host");
    }
}
