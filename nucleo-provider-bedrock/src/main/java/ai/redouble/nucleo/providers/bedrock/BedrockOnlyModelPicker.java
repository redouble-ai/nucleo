/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;

/**
 * The stateful picker base for deployments whose overload substitution never leaves
 * AWS Bedrock: {@link #eligibleForFailover} admits Bedrock-hosted routes only - mantle,
 * native anthropic-bedrock, Converse - never a direct endpoint. Subclasses still author
 * everything that is theirs: the grade pins ({@link #pick}), the corpus declaration
 * ({@link #embeddingsSpec()}), and any per-seat routing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public abstract class BedrockOnlyModelPicker extends AbstractModelPicker {

    @Override
    protected boolean eligibleForFailover(ModelSpec candidate, Situation situation) {
        return candidate.isBedrockHosted();
    }
}
