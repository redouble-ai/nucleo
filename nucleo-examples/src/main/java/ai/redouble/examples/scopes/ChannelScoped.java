/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;

/**
 * The channel marker.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public interface ChannelScoped extends Scoped {
    String getChannel();

    @Override
    default Scope scope() {
        return new ChannelScope(getChannel());
    }
}
