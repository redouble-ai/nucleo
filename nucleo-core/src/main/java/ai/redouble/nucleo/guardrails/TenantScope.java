/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Reference scope value for the {@link TenantScoped} axis. Together with the marker
 * this pair is the complete recipe for authoring a scope axis: a record implementing
 * {@link Scope} (equality judgment and the refusal message come from the default) and
 * a marker whose default {@code scope()} bridges the domain getter.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public record TenantScope(String tenantId) implements Scope {
}
