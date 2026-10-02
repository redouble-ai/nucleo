/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.secrets.*;

/**
 * The credential store of this module's dispatcher-driven tests: the two System One
 * credentials, each with the local server a test started as its host. The host is set by the test
 * before the first resolution and read at every lookup, so the store answers for whichever
 * server is up.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class SystemOneTestSecrets implements Secrets {
    /** The API root of the local server the running test serves the decision entry from. */
    static volatile String host;
    /** The address of the local server the running test serves as the local connection, which carries no key. */
    static volatile String localHost;

    @Override
    public Credential find(String id) {
        if (SystemOneClient.SECRET_ID.equals(id) && host != null) {
            return new Credential(null, "local", host);
        }
        if (SystemOneClient.LOCAL_ID.equals(id) && localHost != null) {
            return new Credential(null, null, localHost);
        }
        return null;
    }
}
