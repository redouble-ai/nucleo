/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * The completely open picker base: any route of the same identity may substitute for
 * an overloaded sibling - Bedrock, Mantle, direct, anything the catalog carries. The
 * framework's invariant walls still apply to every substitute (undeprecated, not
 * probe-dead, envelope-permitted), so "open" means the picker family imposes no route
 * policy of its own and the compliance envelope is the only fence.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public abstract class OpenModelPicker extends AbstractModelPicker {

    @Override
    protected boolean eligibleForFailover(ModelSpec candidate, Situation situation) {
        return true;
    }
}
