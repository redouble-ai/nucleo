/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;

/**
 * Interface for executable tools that can be invoked by Thinkers.
 *
 * <p>Tools are specialized jobs that accept structured input and produce
 * structured output. They declare their own resource requirements and can
 * perform database operations, LLM calls, or any other work. Tools are
 * identified by {@link ToolName} and {@link ToolDescription} annotations.
 *
 * <p>Every tool is a {@link GuardedExecution}: it may declare its own content,
 * auth and admission guardrails, and the framework enforces them at dispatch on
 * every route. The input-phase target is the tool's own input.
 *
 * @param <I> Input type - a POJO for structured input
 * @param <O> Output type - a POJO for structured output
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public interface Tool<I , O > extends Job<O>, GuardedExecution {

    /** Metadata key for serialized tool/thinker input. */
    String OBS_INPUT = "obs.input";

    /** Metadata key for serialized tool/thinker output. */
    String OBS_OUTPUT = "obs.output";

    /**
     * Executes this tool with provided resources.
     *
     * <p>Narrows the throws clause from {@link Job#execute}'s {@code throws Exception}
     * to {@code throws LLMReadableCheckedException}. This enforces at compile time that all
     * tool implementations (including Thinkers and Doers) communicate errors through
     * the typed LLM-readable exception hierarchy, never through raw exceptions.</p>
     *
     * @param resources the allocated resources
     * @param context   the job context
     * @return the tool result
     * @throws LLMReadableCheckedException if execution fails
     */
    @Override
    O execute(JobResources resources, JobContext<O> context) throws LLMReadableCheckedException;

    /**
     * Sets the structured input for this tool before execution.
     *
     * @param input Structured input parameters that this tool requires
     */
    void setInput(I input);

    /**
     * Gets the structured input that was set for this tool.
     *
     * @return Structured input parameters, null if not yet set
     */
    I getInput();

    /**
     * The input-phase guardrail target is the tool's typed input.
     */
    @Override
    default Object guardedInputTarget() {
        return getInput();
    }
}