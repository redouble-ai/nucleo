/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * A response POJO whose reasoning is a {@link ChainOfThoughtReasoning}: the approach and the
 * steps, for answers that need a documented path.
 *
 * @see ChainOfThoughtReasoning
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public abstract class ChainOfThoughtReasonablePojo extends ReasonablePojo<ChainOfThoughtReasoning> {

}
