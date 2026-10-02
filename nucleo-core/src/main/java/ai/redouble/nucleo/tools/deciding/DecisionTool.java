/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.deciding;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.tools.*;

/**
 * The type of every tool a {@link DecisionThinker} may hold: a tool that takes an artifact
 * and produces an artifact. Everything around a decision model is an artifact, because the
 * model never writes a value and only ranks what is there; this type is where that rule
 * lives, so a palette is legal because it compiled. A subclass has the
 * {@code (Identifiable parent)} constructor and a {@code @ToolName}, as any tool a thinker
 * instantiates by class does.
 *
 * @param <I> the artifact type the tool takes
 * @param <O> the artifact type the tool produces; a {@link ListArtifact} when it produces many
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public abstract class DecisionTool<I extends Artifact, O extends Artifact> extends AbstractTool<I, O> {
    protected DecisionTool(Identifiable parent) {
        super(parent);
    }
}
