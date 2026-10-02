/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * Specifies the configuration for job execution.
 * Contains all operation requirements functionality.
 * Jobs declare:
 * - Zero or more {@link DBResourceProvider}s that the job will access
 * - Whether automatic transaction management is needed
 * - Maximum execution time for timeout enforcement
 * - Model NEEDS via {@link #requireModel} / {@link #requireEmbeddings} / {@link #requireDecision}
 * - a grade and depth, or a kind, never a concrete model: the harness resolves each returned
 * {@link ModelBinding} through the deployment's picker at the requirements-resolution step,
 * before any acquisition
 * The scheduler uses these to:
 * - Resolve model bindings, then assemble the job's whole {@link Demand} and hand it to
 * {@link Admission}, which grants every account at once or parks the job holding nothing
 * - Create appropriate JobResources with handles from each declared provider
 * - Manage transaction lifecycle if requested
 * - Enforce execution timeouts
 * Note: rate limiting is admission: no request goes upstream before its budget was taken.
 * Connection checkout happens inside each provider implementation, under a permit the
 * admission already holds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-10)
 */
public class JobRequirements {
    private final List<DBResourceProvider<?>> providers = new ArrayList<>();
    private boolean requiresTransaction;
    private final List<ModelBinding> modelBindings = new ArrayList<>();
    private final Map<RateLimiter<?>, Object> customRateLimiters = new LinkedHashMap<>();
    private boolean isReadOnly = false;
    private boolean toleratesDependencyFailures = false;
    private boolean requiresQueueing = true; // false for instant execution
    // jobType lives on Job.getJobType(), not here - it's intrinsic to the job class
    private boolean explicitHttpConnection = false;

    /**
     * Declares that this job needs a handle from the given provider. Jobs
     * may declare multiple providers; JobResources acquires one handle per
     * provider in declaration order.
     */
    public void addProvider(DBResourceProvider<?> provider) {
        providers.add(provider);
    }

    /**
     * Returns a copy of the declared providers, in declaration order.
     */
    public List<DBResourceProvider<?>> getProviders() {
        return new ArrayList<>(providers);
    }

    public boolean requiresTransaction() {
        return requiresTransaction;
    }

    public void setRequiresTransaction(boolean requiresTransaction) {
        this.requiresTransaction = requiresTransaction;
    }

    /**
     * Declares an LLM need whose sizes come from a wired conversation: mint the binding
     * here, wire it with {@code conversation.setModelBinding(binding)}, and the harness
     * counts input under the resolved spec's tokenizer at resolution time.
     *
     * <p>One binding per invocation. The dispatcher captures requirements once per
     * attempt, and that capture is the last mint before resolution - so a job stashing
     * the returned binding in a field always holds the cell that resolves.
     */
    public ModelBinding requireModel(Grade grade, Depth depth) {
        ModelBinding binding = new ModelBinding(grade, depth);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares an LLM need with a flat input count and an output declaration - for jobs whose
     * prompt is not built at requirements time. Both sides are required: the input is the
     * job's own estimate, the output is a rung or a count, and the spec's thinking budget for
     * the depth is added at pricing.
     */
    public ModelBinding requireModel(Grade grade, Depth depth, int inTokens, OutputDeclaration output) {
        ModelBinding binding = new ModelBinding(grade, depth, inTokens, output);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares an LLM need priced from a prompt text the job already holds: the text is
     * counted at resolution time under the resolved spec's tokenizer, plus the declared
     * output and the spec's thinking budget for the depth.
     */
    public ModelBinding requireModelForPrompt(Grade grade, Depth depth, String promptText, OutputDeclaration output) {
        ModelBinding binding = new ModelBinding(grade, depth, promptText, output);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares an LLM need pinned to an exact spec, wired to a conversation. Discouraged -
     * a grade seat lets the picker keep the system resilient - but first-class: the pin
     * skips only the picker consult; the compliance/kind/vision/deprecation gate still
     * judges it, and a retry re-mints the same spec (no failover for pins).
     */
    public ModelBinding requireModel(ModelSpec pinned, Depth depth) {
        ModelBinding binding = new ModelBinding(pinned, depth);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares a pinned LLM need with flat token scalars. Same discouragement and gate posture as the wired pin.
     */
    public ModelBinding requireModel(ModelSpec pinned, Depth depth, int inTokens, OutputDeclaration output) {
        ModelBinding binding = new ModelBinding(pinned, depth, inTokens, output);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares an embeddings need. Embeddings are their own channel: no grade, resolved from the deployment's frozen declaration ({@code ModelPicker.embeddingsSpec}).
     */
    public ModelBinding requireEmbeddings(int tokens) {
        ModelBinding binding = ModelBinding.embeddings(tokens);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares an embeddings need pinned to an exact spec, still subject to the gate.
     */
    public ModelBinding requireEmbeddings(ModelSpec pinned, int tokens) {
        ModelBinding binding = ModelBinding.embeddingsPinned(pinned, tokens);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares a decision need with a flat input count. Decision models are their own channel:
     * no grade, resolved from the deployment's declaration ({@code ModelPicker.decisionSpec}).
     */
    public ModelBinding requireDecision(int tokens) {
        ModelBinding binding = ModelBinding.decision(tokens);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares a decision need priced from the request text the job already holds: the text
     * is counted at resolution time under the resolved spec's tokenizer, and a state past the
     * entry's ceiling fails there instead of on the wire.
     */
    public ModelBinding requireDecisionForText(String requestText) {
        ModelBinding binding = ModelBinding.decision(requestText);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares a decision need pinned to an exact spec, still subject to the gate.
     */
    public ModelBinding requireDecision(ModelSpec pinned, int tokens) {
        ModelBinding binding = ModelBinding.decisionPinned(pinned, tokens);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * Declares a decision need pinned to an exact spec and priced from the request text.
     */
    public ModelBinding requireDecisionForText(ModelSpec pinned, String requestText) {
        ModelBinding binding = ModelBinding.decisionPinned(pinned, requestText);
        modelBindings.add(binding);
        return binding;
    }

    /**
     * The declared model bindings, in declaration order. The harness resolves each before acquisition.
     */
    public List<ModelBinding> getModelBindings() {
        return new ArrayList<>(modelBindings);
    }

    /**
     * Adds a custom rate limiter requirement with its amount.
     *
     * @param limiter The rate limiter to require
     * @param input   The amount the demand carries on the limiter ({@code null} for unit permits)
     * @param <T>     The amount type the limiter expects
     */
    public <T> void requireRateLimiter(RateLimiter<T> limiter, T input) {
        if (limiter == null) {
            throw new IllegalArgumentException("requireRateLimiter needs a limiter; a job with nothing to limit declares nothing");
        }
        customRateLimiters.put(limiter, input);
    }

    /**
     * Gets all custom rate limiter requirements, in declaration order.
     *
     * @return Map of rate limiters to their amounts
     */
    public Map<RateLimiter<?>, Object> getCustomRateLimiters() {
        return new LinkedHashMap<>(customRateLimiters);
    }

    /**
     * Checks if this job requires any custom rate limiters.
     *
     * @return true if custom rate limiters are required
     */
    public boolean requiresCustomRateLimiters() {
        return !customRateLimiters.isEmpty();
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.isReadOnly = readOnly;
    }

    public boolean requiresQueueing() {
        return requiresQueueing;
    }

    public void setRequiresQueueing(boolean requiresQueueing) {
        this.requiresQueueing = requiresQueueing;
    }

    // Additional getters specific to JobRequirements

    /**
     * @return true if this job requires any database provider
     */
    public boolean requiresDatabase() {
        return !providers.isEmpty();
    }

    /**
     * Checks if this job requires any LLM resources.
     *
     * @return true if any LLM binding is declared
     */
    public boolean requiresLlm() {
        return requiresKind(ModelKind.LLM);
    }

    /**
     * Checks if this job requires any embeddings resources.
     *
     * @return true if any embeddings binding is declared
     */
    public boolean requiresEmbeddings() {
        return requiresKind(ModelKind.EMBEDDINGS);
    }

    /**
     * Checks if this job requires any decision resources.
     *
     * @return true if any decision binding is declared
     */
    public boolean requiresDecision() {
        return requiresKind(ModelKind.DECISION);
    }

    private boolean requiresKind(ModelKind kind) {
        for (ModelBinding binding : modelBindings) {
            if (binding.getKind() == kind) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if this job requires ANY resources: database, LLM, embeddings, decision, custom
     * rate limiters, or the shared HTTP connection gate. Every such job has a non-empty demand
     * and holds a permit while it runs, so it must have a timeout and must not block on a
     * child job's completion (deadlock prevention).
     *
     * @return true if any resources are required
     */
    public boolean requiresResources() {
        return requiresDatabase() || !modelBindings.isEmpty() || requiresCustomRateLimiters() || requiresHttpConnection();
    }

    /**
     * Gets whether this job can tolerate dependency failures.
     * If true, the job's execute method will be called even if some dependencies fail.
     * The job can then decide how to handle the failures.
     *
     * @return true if the job tolerates dependency failures
     */
    public boolean toleratesDependencyFailures() {
        return toleratesDependencyFailures;
    }

    /**
     * Sets whether this job can tolerate dependency failures.
     *
     * @param tolerates true to allow execution even with failed dependencies
     */
    public void setToleratesDependencyFailures(boolean tolerates) {
        this.toleratesDependencyFailures = tolerates;
    }

    /**
     * Explicitly declares that this job requires HTTP connections.
     * Use this for jobs that make HTTP calls without going through LLM/embeddings APIs.
     *
     * @param requires true if HTTP connections are needed
     */
    public void setRequiresHttpConnection(boolean requires) {
        this.explicitHttpConnection = requires;
    }

    /**
     * Checks if this job needs HTTP connections.
     * Returns true if: explicitly declared, OR uses LLM, OR uses embeddings,
     * OR any custom rate limiter requires HTTP.
     *
     * @return true if HTTP connections are needed
     */
    public boolean requiresHttpConnection() {
        if (explicitHttpConnection)
            return true;
        if (!modelBindings.isEmpty())
            return true;
        for (RateLimiter<?> limiter : customRateLimiters.keySet()) {
            if (limiter.requiresHttpConnection())
                return true;
        }
        return false;
    }

}
