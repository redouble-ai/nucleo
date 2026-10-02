/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;

/**
 * The channel axis: which channel's work this is (email, phone). Independent of the case
 * axis, which is why the two can bind one flow side by side without knowing of each other.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region channel
public record ChannelScope(String channel) implements Scope {
}
// endregion
