/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import ai.redouble.nucleo.*;

/**
 * The secrets package's knobs: where the runtime gets its credentials.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public class SecretsSettings extends Settings {

    /**
     * The deployment's {@link Secrets} store, where every provider and tool looks up the
     * credential id it declares. Built once at the first {@link Secrets#configured()} call;
     * the shipped default reads environment variables.
     */
    public volatile Class<? extends Secrets> secretsClass = EnvironmentSecrets.class;
}
