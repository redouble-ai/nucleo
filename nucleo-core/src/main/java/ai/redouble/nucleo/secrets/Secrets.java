/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.util.*;

/**
 * Where the runtime gets its credentials. The runtime asks by id - each provider or tool declares
 * its own as a constant - and the deployment decides what an id maps to: environment variables
 * ({@link EnvironmentSecrets}, the default), the host framework's own store, a vault. The
 * implementation is named by {@link SecretsSettings#secretsClass}, next to the model picker,
 * and built once on first use.
 * <p>
 * Two questions, because callers differ: {@link #find} for a credential the caller can do
 * without (an API key that only lifts a rate limit), {@link #require} for one it cannot (the
 * provider key a client is constructed around).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public interface Secrets {

    /**
     * The credential under this id, or null when the deployment holds none. A store that cannot
     * answer at all (unreachable, unreadable, misconfigured) throws {@link SecretUnavailableException}.
     */
    Credential find(String id);

    /** The credential under this id; {@link SecretUnavailableException} when the deployment holds none. */
    default Credential require(String id) {
        Credential credential = find(id);
        if (credential == null) {
            throw new SecretUnavailableException(id, "not configured - provide " + describe(id));
        }
        return credential;
    }

    /**
     * How a person provides the credential under this id in THIS store, in words a log line or an
     * error can carry: the environment store names the variable, a vault names the record, and a
     * store that does not override this is described as {@code the credential '<id>' in <its class
     * name>}. Every "not configured" message goes through here, so nobody has to read source to
     * learn what to set.
     */
    default String describe(String id) {
        return "the credential '" + id + "' in " + getClass().getSimpleName();
    }

    /** The deployment's store, built once from {@link SecretsSettings#secretsClass}. */
    static Secrets configured() {
        return Configured.INSTANCE;
    }

    /** Lazy holder: the class initializes, and so the store gets built, on the first {@link #configured()} call. */
    final class Configured {
        static final Secrets INSTANCE = Reflection.newInstance(Settings.get(SecretsSettings.class).secretsClass);

        private Configured() {
        }
    }
}
