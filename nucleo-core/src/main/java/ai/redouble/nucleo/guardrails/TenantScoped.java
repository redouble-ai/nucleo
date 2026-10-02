/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Reference scope axis, carrier side: anything owning a tenant id. An orchestrator
 * implementing this IS tenant-scoped - its {@link #scope()} value is captured frozen
 * at dispatch and pins every tenant-carrying input in its subtree; an input
 * implementing this carries the claim that gets judged.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public interface TenantScoped extends Scoped {
    String getTenantId();

    @Override
    default Scope scope() {
        return new TenantScope(getTenantId());
    }
}
