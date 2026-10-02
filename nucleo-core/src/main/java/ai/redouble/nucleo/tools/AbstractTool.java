/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import org.slf4j.*;

import java.util.*;

/**
 * Base implementation for tools that provides input management.
 *
 * <p>Handles the common pattern of accepting structured input before execution.
 * Subclasses define their resource requirements and implement their specific
 * execution logic. A tool whose work is one exchange with a model extends
 * {@link AbstractModelDependentTool} instead, which carries the seat and the conversation
 * wiring; a tool that holds no model seat has none of that.
 *
 * <p><strong>Error Handling:</strong></p>
 * <p>Every failure a tool raises is a typed {@link LLMReadableCheckedException} (or its
 * unchecked twin where checked exceptions cannot propagate), so the thinker loop can tell
 * "fix your input and retry" from "try a different approach". The refusal names the
 * parameter and the rule it broke. Whether the offending value is repeated is the throwing
 * site's judgment: a tool judging a typed field it already parsed may name it, as the
 * example below does, while the boundary seats - input parsers, the URL policy, the
 * answer-shape checks - pass a type name in its place, because there the value is whatever
 * the model pasted and repeating it would launder payloads into logs and prompts.</p>
 *
 * <p><strong>Example:</strong></p>
 * <pre>
 * if (input.getMaxResults() > 100) {
 *     throw new InvalidInputException("maxResults", input.getMaxResults(), "must be 100 or less");
 * }
 * </pre>
 *
 * <p>This enables self-correcting agentic workflows where the LLM can understand
 * what went wrong and retry with corrected parameters. The blanket shape at the bottom of
 * {@code execute} is {@code catch (Exception e) { throw LLMReadableCheckedException.unwrap(e); }},
 * which lets typed failures and the dispatcher's retry signals through unchanged.</p>
 *
 * @param <I> Input type - a POJO for structured input
 * @param <O> Output type - a POJO for structured output
 * @see LLMReadableCheckedException
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public abstract class AbstractTool<I , O > extends AbstractJob<O> implements Tool<I, O> {
    private static final Logger log = LoggerFactory.getLogger(AbstractTool.class);
    protected I input;

    /**
     * Creates a tool with lineage tracking.
     *
     * @param parent the parent identity for lineage tracking
     */
    public AbstractTool(Identifiable parent) {
        super(parent, null);  // Use default class-name-based prefix
    }

    /**
     * Creates a tool with lineage tracking and custom ID prefix.
     *
     * @param parent the parent identity for lineage tracking
     * @param idPrefix custom prefix for the tool's job ID
     */
    public AbstractTool(Identifiable parent, String idPrefix) {
        super(parent, idPrefix);
    }

    @Override
    public JobType getJobType() {
        return JobType.TOOL;
    }

    /**
     * Default requirements for tools.
     * Subclasses should override to add database, LLM, or other requirements.
     * JobType is handled by {@link #getJobType()}, not here.
     */
    @Override
    public JobRequirements getRequirements() {
        return new JobRequirements();
    }

    @Override
    public void setInput(I input) {
        this.input = input;
    }

    @Override
    public I getInput() {
        return input;
    }

    @Override
    public void preExecute(JobContext<O> context) {
        try {
            if (input != null) {
                context.putMetadata(OBS_INPUT, NucleoJsonSerializer.writeCompact(input));
            }
        }
        catch (Exception e) {
            log.warn("Failed to serialize tool input for observability: {}", e.getMessage());
        }
    }

    @Override
    public void postExecute(JobContext<O> context) {
        try {
            Optional<O> result = context.getResult();
            if (result.isPresent()) {
                context.putMetadata(OBS_OUTPUT, NucleoJsonSerializer.writeCompact(result.get()));
            }
        }
        catch (Exception e) {
            log.warn("Failed to serialize tool output for observability: {}", e.getMessage());
        }
    }
}
