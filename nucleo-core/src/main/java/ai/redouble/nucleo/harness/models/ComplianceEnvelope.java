/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;



/**
 * The application's standing answer to "may this model serve my traffic".
 *
 * <p>One compliance domain per {@code JobDispatcher}, which is one per application:
 * the MessageBus is bidirectional, artifacts and persisted conversations live in shared
 * stores, so once sensitive data enters the process there is no boundary inside it that
 * data provably stays behind. Anything finer than the dispatcher (per-thinker,
 * per-workload) would require taint tracking and is deliberately not offered.
 *
 * <p>The host seals its envelope into the dispatcher once, through
 * {@code JobDispatcher.sealComplianceEnvelope}, before {@code start()}; a dispatcher
 * started without one seals the refusing {@link DefaultComplianceEnvelope}. A switch flipped later would only promise "compliant from now on", which is not the
 * property anyone wants - the seal is permanent for the process lifetime.
 *
 * <p>Consulted at the harness's resolution step before any tokens are counted, and by
 * the Mantle client as the last line before a request leaves the process. The envelope
 * is a fixed function over live input: a spec's facts (e.g. {@link ModelSpec#requiresLax()})
 * change in the catalog over time, so a model gaining zero-data-retention eligibility
 * opens up on the next resolution with no redeploy.
 *
 * <p>{@link DefaultComplianceEnvelope} covers the normal decisions (data-share opt-in,
 * provider and identity restrictions). Bespoke policies - vendor-origin bans, thinking-mode
 * restrictions - are authored as their own implementations.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
@FunctionalInterface
public interface ComplianceEnvelope {
    boolean permits(ModelSpec spec);
}
