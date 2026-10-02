/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * A response POJO whose reasoning is a {@link SimpleReasoning}: one thought, for answers that
 * need a justification and nothing more.
 *
 * @see SimpleReasoning
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public abstract class StringReasonablePojo extends ReasonablePojo<SimpleReasoning> {

}
