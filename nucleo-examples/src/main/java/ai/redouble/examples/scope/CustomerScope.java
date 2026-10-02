/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scope;

import ai.redouble.nucleo.guardrails.*;

/**
 * The customer axis: a flow bound to one customer admits only inputs that name that
 * customer.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region scope
public record CustomerScope(String customerId) implements Scope {
}
// endregion
