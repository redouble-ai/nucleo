/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;

import java.util.concurrent.atomic.*;

/**
 * Served orchestrator that spawns a slow child and waits on it: the fixture for
 * whole-workflow cancellation after a call timeout.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@MCP
@ToolName("mcp_slow_parent")
@ToolDescription(value = "Spawns a slow child and waits for it.", readOnly = true)
public class SlowParentDoer extends AbstractDoer<NoFieldsInput, String> {
    public static final AtomicReference<SlowChildTool> LAST_CHILD = new AtomicReference<>();

    public SlowParentDoer(Identifiable parent) {
        super(parent);
    }

    @Override
    public String execute(JobContext<String> context) throws LLMReadableCheckedException {
        SlowChildTool child = new SlowChildTool(this);
        child.setInput(new NoFieldsInput());
        LAST_CHILD.set(child);
        try {
            return submitInStep(child).get();
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
