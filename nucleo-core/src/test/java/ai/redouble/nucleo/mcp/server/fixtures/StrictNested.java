/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.schema.*;

/**
 * The nested half of {@link StrictInput}: the gate must judge a nested object by the same
 * rules as the top level, including refusing an undeclared property one level down.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class StrictNested {
    @LLMDescription("A note")
    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
