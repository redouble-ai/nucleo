/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

/**
 * Where a block sits in the rendered conversation, for the billing rules that depend on it.
 * Anthropic bills a thinking block only on the newest assistant turn; the place is what lets
 * a provider's estimator say so.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public enum TurnPlace {
    /** A user turn: the question, tool results, announcements, the registry section. */
    USER,
    /** An assistant turn other than the newest. */
    EARLIER_ASSISTANT,
    /** The newest assistant turn. */
    LAST_ASSISTANT
}
