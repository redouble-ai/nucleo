/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.util.concurrent.atomic.*;

/**
 * Base implementation of {@link Stoppable} with common shutdown flag handling.
 * <p>
 * Subclasses must implement {@link #doStop()} for actual cleanup logic.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-03)
 */
public abstract class AbstractStoppable implements Stoppable {
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    @Override
    public boolean isStopping() {
        return stopping.get();
    }

    @Override
    public final void stop() {
        if (stopping.compareAndSet(false, true)) {
            doStop();
        }
    }

    /**
     * Performs the actual shutdown logic.
     * <p>
     * Called exactly once when {@link #stop()} is first invoked.
     * At this point {@link #isStopping()} already returns true.
     */
    protected abstract void doStop();
}
