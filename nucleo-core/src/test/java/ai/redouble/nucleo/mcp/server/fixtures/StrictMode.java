/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;


/**
 * An enum-typed input field publishes as a string constrained by its constants, and the
 * gate refuses anything outside them; this is the fixture that gives the campaigns one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public enum StrictMode {
    FAST,
    DEEP
}
