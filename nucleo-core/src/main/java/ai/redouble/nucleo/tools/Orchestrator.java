/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * Interface for resource-free orchestrators that coordinate other jobs.
 *
 * <p>Coordinators hold no database connections or LLM tokens. They only hold job handles.
 * This allows them to wait for hours/days without consuming system resources (just a virtual thread).
 *
 * <p><b>Key characteristics:</b>
 * <ul>
 *   <li>Resource-free - no DB, LLM, or HTTP connections held</li>
 *   <li>Can spawn and wait on other jobs dynamically</li>
 *   <li>Implements Tool - can be invoked by Thinkers</li>
 * </ul>
 *
 * <p><b>Implementations:</b>
 * <ul>
 *   <li>{@link AbstractThinker} - LLM-driven decision loop with tool orchestration</li>
 *   <li>{@link AbstractDoer} - Coded logic orchestration (spawn/wait/return freely)</li>
 * </ul>
 *
 * @param <I> Input type - a POJO for structured input
 * @param <O> Output type - a POJO for structured output
 * @see AbstractOrchestrator
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-23)
 */
public interface Orchestrator<I , O > extends Tool<I, O>, ScopeAuthority {
    // Implementations must be resource-free (no DB, LLM, HTTP).
    // As a ScopeAuthority, an orchestrator may declare scope guardrails that bind its
    // whole subtree - the scoped thinker/doer IS the scope. Leaf tools have no such seat.
}
