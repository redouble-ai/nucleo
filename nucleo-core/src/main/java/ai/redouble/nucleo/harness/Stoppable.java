/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;


/**
 * Interface for components that need graceful shutdown.
 * <p>
 * Components must be registered with {@link JobDispatcher#registerForShutdown(Stoppable)}.
 * Registration can be done either by the component itself or by JobDispatcher
 * (for components initialized during JobDispatcher construction).
 * <p>
 * Components should check {@link #isStopping()} before accepting new work
 * to prevent race conditions during shutdown.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-03)
 */
public interface Stoppable {
    /**
     * Returns true if shutdown is in progress.
     */
    boolean isStopping();

    /**
     * Initiates graceful shutdown. Must be idempotent.
     */
    void stop();
}
