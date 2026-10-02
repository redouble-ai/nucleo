/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.*;

/**
 * Protected steps of the client base that provider tests in other packages exercise. Test scope
 * only: the production API stays as narrow as it is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public final class LlmTestDoors {

    private LlmTestDoors() {
    }

    /** The conversation as the client would put it on the wire, before any provider-specific encoding. */
    public static PreparedConversation prepare(AbstractLLMClient<?> client, ConversationContext conversation) {
        return client.prepareConversation(conversation);
    }
}
