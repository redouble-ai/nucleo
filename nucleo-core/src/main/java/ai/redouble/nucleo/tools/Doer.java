/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;


/**
 * Interface for coded orchestration logic.
 *
 * <p>Doers are coordinators with coded (non-LLM) logic. They can be invoked by Thinkers
 * just like any other tool, but their execute() contains coded logic that spawns
 * and coordinates other jobs dynamically.
 *
 * <p><b>Key characteristics:</b>
 * <ul>
 *   <li>Resource-free - no DB, LLM, or HTTP connections held</li>
 *   <li>Coded orchestration - logic is in Java, not decided by LLM</li>
 *   <li>Dynamic workflow - can spawn N jobs based on runtime data</li>
 * </ul>
 *
 * <p><b>Use cases:</b>
 * <ul>
 *   <li>Fan-out/fan-in: spawn N workers dynamically, wait for all, aggregate</li>
 *   <li>Conditional workflows: run job A, based on result run B or C</li>
 *   <li>Retry orchestration: coded retry logic with backoff</li>
 *   <li>Multi-stage pipelines: each stage determines next stage's parallelism</li>
 * </ul>
 *
 * @param <I> Input type - a POJO for structured input
 * @param <O> Output type - a POJO for structured output
 * @see AbstractDoer
 * @see Orchestrator
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-02)
 */
public interface Doer<I , O > extends Orchestrator<I, O> {
    // Marker interface - all methods inherited from Coordinator/Tool
    // Implementations contain coded orchestration logic
}
