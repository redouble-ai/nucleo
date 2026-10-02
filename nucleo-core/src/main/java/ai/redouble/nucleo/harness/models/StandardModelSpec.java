/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * Concrete {@link ModelSpec} for providers that need no fields beyond the common base
 * (OpenAI chat, OpenAI/Azure embeddings, Bedrock Converse models). Providers that grow
 * endpoint-specific metadata get their own subtype, as the Anthropic artifact does with
 * {@code AnthropicModelSpec}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class StandardModelSpec extends AbstractModelSpec {
}
