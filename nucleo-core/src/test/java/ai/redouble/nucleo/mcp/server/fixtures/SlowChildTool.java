/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

import java.util.concurrent.atomic.*;

/**
 * The child a served orchestrator spawns: runs until cancelled, and records that it
 * observed the cancellation. Not exposable itself.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@ToolName("mcp_slow_child")
@ToolDescription(value = "Runs until cancelled.", readOnly = true)
public class SlowChildTool extends AbstractTool<NoFieldsInput, String> {
    public final AtomicBoolean observedCancellation = new AtomicBoolean(false);

    public SlowChildTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public String execute(JobResources resources, JobContext<String> context) throws LLMReadableCheckedException {
        long deadline = System.currentTimeMillis() + FaultyTool.SLOW_RUN.toMillis();
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    context.checkCancellation();
                }
                catch (JobContext.CancellationException e) {
                    observedCancellation.set(true);
                    throw e;
                }
                Thread.sleep(50);
            }
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
        return "child done";
    }
}
