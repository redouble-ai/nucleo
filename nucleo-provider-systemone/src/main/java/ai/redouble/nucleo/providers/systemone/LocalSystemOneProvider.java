/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

/**
 * A decision model served on this machine over the System One wire - a {@code kev.serve}, or
 * any open replica started locally. Its credential is only the server's address
 * ({@link SystemOneClient#LOCAL_SHAPE}, read from {@code SYSTEMONE_LOCAL_HOST}): a server on
 * this machine checks no key, so none is asked for and none is sent. Everything else - the
 * listing, the connection facts, which names the server serves - is {@link SystemOneProvider}'s,
 * under the key {@code systemone-local-decision}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class LocalSystemOneProvider extends SystemOneProvider {

    public LocalSystemOneProvider() {
        super("systemone-local-decision", SystemOneClient.LOCAL_SHAPE);
    }
}
