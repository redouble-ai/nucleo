/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * The immutable identity payload an {@link AdmissionGuardrail} judges: who is calling
 * (principal), through which agent (the calling agent's class name, resolved from the
 * job lineage; null when the gated job sits directly under the workflow root), reaching
 * which tool. No live framework object crosses into a guardrail; this record is the
 * admission rung's complete evaluation context.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public record AdmissionContext(String principal, String callerClassName, Class<?> toolClass) {
}
