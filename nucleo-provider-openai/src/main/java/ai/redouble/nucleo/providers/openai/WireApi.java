/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

/**
 * Which of OpenAI's two APIs a client speaks. A surface, so a provider's choice, the way Bedrock's
 * native and Mantle surfaces are two providers: {@code openai} serves the Responses API, where the
 * reasoning generation reasons and calls tools in one turn, {@code openai-chat-completions} the
 * older one, and the compatible providers come in the same pair because a third-party endpoint
 * decides which it serves. A model reachable on both is two catalog entries under one identity.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public enum WireApi {
    /** {@code /chat/completions}: messages in, choices out. */
    CHAT_COMPLETIONS,
    /** {@code /responses}: input items in, output items out, reasoning beside tool calls. */
    RESPONSES
}
