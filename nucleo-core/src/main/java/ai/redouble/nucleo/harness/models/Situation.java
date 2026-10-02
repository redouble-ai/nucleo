/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;

import java.util.*;

/**
 * Everything the harness knows at the moment it consults the {@link ModelPicker} - an
 * open bag of ingredients, every field optional. The framework populates it faithfully
 * at the single resolution point; policies read whichever ingredients they care about.
 * An empty situation yields static resolution.
 *
 * <p>Deliberately NOT a sealed hierarchy of consultation kinds: the framework cannot
 * enumerate why a deployment would switch models, so the moments ("seating" = no
 * history, "upstream failure" = a retry attempt carrying its attempt record,
 * "semantic failure" = verdicts accumulated by the thinker loop) are contents of the
 * situation, never a closed type vocabulary.
 *
 * <p>History entries carry the actual exceptions - the existing sealed families
 * ({@code UpstreamRetryException}, {@code LLMReadableException}) ARE the outcome
 * vocabulary; no third taxonomy exists. Guardrail refusals never enter history: the
 * single framework-owned population site does not append them, because routing around
 * a guardrail is defeating it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public class Situation {
    /** One prior attempt of the current execution: the spec that served and how it failed. */
    public record Attempt(ModelSpec spec, Exception failure) {}
    private JobSnapshot snapshot;
    private ScopeGuard scopeGuard;
    private ModelSpec prior;
    private List<Attempt> attempts;
    private Depth depth;
    private boolean interactive;
    private Set<Input> sends;
    private Set<Input> carried;
    private ComplianceEnvelope envelope;

    public JobSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(JobSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public ScopeGuard getScopeGuard() {
        return scopeGuard;
    }

    public void setScopeGuard(ScopeGuard scopeGuard) {
        this.scopeGuard = scopeGuard;
    }

    /** The spec that served this conversation before, when one is known - stickiness and cache live here. */
    public ModelSpec getPrior() {
        return prior;
    }

    public void setPrior(ModelSpec prior) {
        this.prior = prior;
    }

    public List<Attempt> getAttempts() {
        return attempts;
    }

    public void setAttempts(List<Attempt> attempts) {
        this.attempts = attempts;
    }

    public Depth getDepth() {
        return depth;
    }

    public void setDepth(Depth depth) {
        this.depth = depth;
    }

    public boolean isInteractive() {
        return interactive;
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    /**
     * The inputs beyond text the request declared it sends ({@code ModelBinding.setSends}): the
     * picker serves it only from an entry that accepts every one. Null when the situation was
     * built without a binding, which declares none.
     */
    public Set<Input> getSends() {
        return sends;
    }

    public void setSends(Set<Input> sends) {
        this.sends = sends;
    }

    /**
     * The inputs the payload actually carries ({@code ConversationContext.carriedInputs}): the
     * gate refuses one the request did not declare or the entry does not accept, and no picker
     * chooses by it. Null when there is no conversation to read.
     */
    public Set<Input> getCarried() {
        return carried;
    }

    public void setCarried(Set<Input> carried) {
        this.carried = carried;
    }

    /** The dispatcher's sealed envelope - pickers filter candidates with it; the gate enforces it regardless. */
    public ComplianceEnvelope getEnvelope() {
        return envelope;
    }

    public void setEnvelope(ComplianceEnvelope envelope) {
        this.envelope = envelope;
    }
}
