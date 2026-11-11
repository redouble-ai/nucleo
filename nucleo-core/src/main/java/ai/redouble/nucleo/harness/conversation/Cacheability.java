/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

/**
 * What the author of a message says about storing it in a provider's prompt cache.
 *
 * <p>A provider's cache stores a prefix of a request: everything from its first byte through
 * some point. A message is therefore never cached by itself; what a value states is whether
 * a stored prefix may end on the message, and whether one may contain it. It is a statement
 * about the content, the same for every provider. What a provider's client does with it -
 * breakpoints and how many, cache points, or nothing where the provider caches without being
 * asked - is that client's decision alone. A provider that caches without being asked
 * stores what it chooses, so on a model of that kind {@link #NEVER} cannot be honoured.
 *
 * <p>A message that states nothing ({@code null}) takes its conversation's default; see
 * {@link ConversationContext#setCacheTurns(boolean)}.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-11)
 */
public enum Cacheability {

    /** A stored prefix must end on this message. */
    ALWAYS,

    /** A stored prefix ending on this message is worth more than usual. */
    PREFERRED,

    /** No opinion: the message may sit inside a stored prefix and may end one. */
    AUTO,

    /** Fine inside a stored prefix, a poor place to end one. */
    UNLIKELY,

    /** Must not be stored: no stored prefix contains this message. */
    NEVER;

    /** Whether a stored prefix may end on a message that states this value. */
    public boolean mayEndPrefix() {
        return this == ALWAYS || this == PREFERRED || this == AUTO;
    }
}
