/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import com.anthropic.models.messages.*;

/**
 * Resolves a {@link ModelSpec} to the Anthropic SDK {@link Model}. Each spec already carries
 * its endpoint-specific wire id ({@code claude-opus-5} for direct, the
 * {@code global.anthropic...}/{@code us.anthropic...} profile for Bedrock), so resolution is
 * just {@code Model.of(spec.getWireModelId())} - no tier remapping. Guards against a
 * non-Anthropic spec reaching the Anthropic client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class AnthropicModelResolver {
    public static Model resolve(ModelSpec spec) {
        if (spec == null || spec.getProviderKey() == null || !spec.getProviderKey().startsWith("anthropic")) {
            throw new UncorrectableRuntimeLLMException("Non-Anthropic model spec passed to Anthropic resolver: " + spec);
        }
        return Model.of(spec.getWireModelId());
    }
}
