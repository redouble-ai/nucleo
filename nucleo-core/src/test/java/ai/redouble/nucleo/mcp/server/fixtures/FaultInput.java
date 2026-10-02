/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.schema.*;

/**
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public class FaultInput {
    public static final String CORRECTABLE = "correctable";
    public static final String RUNTIME = "runtime";
    public static final String NULL = "null";
    public static final String SLOW = "slow";
    @LLMRequired
    @LLMDescription("Which failure to produce")
    private String mode;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
