/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * The deployment cannot serve a model seat at all: every rung the seat may legally be
 * served from was refused - by the compliance envelope, or because nothing is configured
 * there. Deliberately an {@link Error}, outside the LLM-readable hierarchy: this is not a
 * fault a model can route around and not an input anyone can correct, it is the runtime
 * discovering that its deployment's model policy is fundamentally broken, the way a file
 * manager might discover it cannot reach the file system. As an Error it rides through
 * blanket {@code catch (Exception)} recovery unharmed and surfaces at the top instead of
 * being absorbed into a quiet failure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-20)
 */
public class ModelResolutionError extends Error {

    public ModelResolutionError(String message) {
        super(message);
    }

    public ModelResolutionError(String message, Throwable cause) {
        super(message, cause);
    }
}
