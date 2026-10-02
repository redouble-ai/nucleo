/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

/**
 * A tool that holds a model seat: what a benchmark races, a thinker or a one-call tool
 * alike. Named because a field cannot hold the intersection {@code Tool<I,O> & ModelDependent}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public interface ModelDependentTool<I, O> extends Tool<I, O>, ModelDependent {
}
