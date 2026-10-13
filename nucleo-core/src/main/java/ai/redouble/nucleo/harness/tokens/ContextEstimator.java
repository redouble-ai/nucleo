/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;

/**
 * What a conversation will cost a model before the call is made. A provider module supplies
 * one through {@code ClientProvider.contextEstimator(ModelSpec)}, because the answer depends
 * on the provider: how its tokenizer counts, which blocks it sends and which it bills, how it
 * frames a tool call. Code never names an estimator; it asks {@link ModelSpec#estimate}, and
 * the spec finds its provider's.
 *
 * <p>An estimator renders the conversation exactly as the provider's client will send it and
 * counts that rendering. It needs no credential, so it runs before any client is acquired:
 * the reservation at requirements resolution and the compaction trigger both read it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public interface ContextEstimator {
    /**
     * The ledger of the next call on this conversation: input by part, and the output reserve
     * the call books. Pure: the conversation is read, never changed.
     */
    TokenLedger estimate(ConversationContext conversation, ModelSpec spec);
}
