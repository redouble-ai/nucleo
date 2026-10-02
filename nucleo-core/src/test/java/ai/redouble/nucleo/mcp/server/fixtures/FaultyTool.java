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

import java.time.*;

/**
 * Served leaf that fails on demand: a correctable input error, a plain runtime failure,
 * a null result, or a slow run that outlives the server's call timeout while observing
 * cancellation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@MCP
@ToolName("mcp_faulty")
@ToolDescription(value = "Fails in the requested way.", readOnly = true)
public class FaultyTool extends AbstractTool<FaultInput, String> {
    public static final Duration SLOW_RUN = Duration.ofSeconds(20);

    public FaultyTool(Identifiable parent) {
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
        String mode = getInput().getMode();
        switch (mode) {
            case FaultInput.CORRECTABLE -> throw new InvalidInputException("mode", mode, "the fixture was asked to refuse this input");
            case FaultInput.RUNTIME -> throw new IllegalStateException("boom");
            case FaultInput.NULL -> {
                return null;
            }
            case FaultInput.SLOW -> {
                long deadline = System.currentTimeMillis() + SLOW_RUN.toMillis();
                try {
                    while (System.currentTimeMillis() < deadline) {
                        context.checkCancellation();
                        Thread.sleep(50);
                    }
                }
                catch (Exception e) {
                    throw LLMReadableCheckedException.unwrap(e);
                }
                return "slow done";
            }
            default -> throw new IllegalArgumentException("unknown fixture mode " + mode);
        }
    }
}
