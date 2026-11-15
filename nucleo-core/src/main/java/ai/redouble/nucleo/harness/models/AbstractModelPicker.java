/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The stateful picker base: holds each spec's availability as observed by the probe and
 * refuses to serve a spec the observations say is down - the deployment's "turn off
 * additional models and treat them as deprecated" default. Subclasses implement
 * {@link #pick(Seat, Situation)} with their policy; {@link #provide(Seat, Situation)} is
 * the template that applies the turnoff on the way out. A policy that would rather pass a
 * turned-off entry over than be refused for it asks {@link #isTurnedOff(ModelSpec)}, or
 * {@link #whyNot(ModelSpec, Situation)} for every reason at once, before choosing, as
 * {@link DefaultModelPicker} does.
 *
 * <p>A seat with a {@link ModelRole} is served from the role's entry before {@code pick} is
 * asked, when that entry can serve the request ({@link #whyNot(ModelSpec, Situation)}: in
 * this catalog, open, its provider on the classpath with a credential, permitted by the
 * envelope, accepting the declared inputs, not turned off); otherwise one WARN per role and
 * reason, and {@code pick} serves the seat at the role's grade. The family invariant
 * ({@link #vetPick}) and the overload failover apply to a role's entry as to any pick.
 *
 * <p>The turnoff rule counts ONLY {@link ProbeOutcome.Classification#AVAILABILITY}
 * failures: a spec is off when it has an availability failure inside the window and no
 * success inside it. Throttling and auth failures never turn a model off (busy is not
 * down; a rotated key is a config incident that belongs in the report, not a silent
 * removal), and missing data never does - a cold picker turns nothing off.
 *
 * <p>Pinned bindings bypass this template deliberately: {@code ModelPickers.resolvePinned}
 * never consults the picker, so a probe can ping a turned-off spec (recovery detection)
 * and an explicitly pinned production job can still ride one. Turnoff is picker policy;
 * catalog deprecation is the gate's and refuses pins too.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public abstract class AbstractModelPicker implements ModelPicker {
    private static final Logger log = LoggerFactory.getLogger(AbstractModelPicker.class);
    protected static final Duration TURNOFF_WINDOW = Duration.ofHours(24);

    /** One spec's accumulated availability state. Immutable: replaced whole under {@code compute}, never mutated. */
    protected record SpecHealth(Instant lastSuccessAt, Instant lastFailureAt, long consecutiveFailures, ProbeOutcome lastOutcome) {}

    private final ConcurrentHashMap<String, SpecHealth> health = new ConcurrentHashMap<>();

    @Override
    public final ModelSpec provide(Seat seat, Situation situation) {
        ModelSpec picked = seat.role() != null ? forRole(seat, situation) : null;
        if (picked == null) {
            picked = pick(seat, situation);
        }
        if (picked != null) {
            vetPick(picked, seat, situation);
            SpecHealth h = health.get(picked.getId());
            if (isTurnedOff(picked, h)) {
                throw new UncorrectableRuntimeLLMException("Picker " + getClass().getSimpleName() + " picked "
                        + picked.getId() + " for seat " + seat.jobClass().getSimpleName()
                        + ", but probes report it unavailable (treated as deprecated): " + h.consecutiveFailures()
                        + " consecutive availability failure(s), last at " + h.lastFailureAt()
                        + (h.lastSuccessAt() != null ? ", last success " + h.lastSuccessAt() : ", never seen succeeding")
                        + ". Last error: " + h.lastOutcome().getErrorClass() + ": " + h.lastOutcome().getErrorMessage());
            }
            ModelSpec failover = overloadFailover(picked, situation);
            if (failover == null) {
                failover = throttleDiversion(picked, situation);
            }
            if (failover != null) {
                return failover;
            }
        }
        return picked;
    }

    /**
     * The role's entry when it can serve the request, else null with one WARN per role and
     * reason, and the request falls to {@link #pick} at the role's grade. An entry absent
     * from this deployment's catalog is one reason among the others: an application ships
     * to deployments whose catalogs it did not write.
     */
    private ModelSpec forRole(Seat seat, Situation situation) {
        ModelRole role = seat.role();
        ModelSpec entry = Models.findSpec(role.catalogId());
        String why;
        if (entry == null) {
            why = "this deployment's catalog has no entry " + role.catalogId();
        }
        else if (entry.kind() != seat.kind()) {
            // an entry the gate would refuse is an entry that cannot serve: the role falls back, it does not fail
            why = "it is " + entry.kind() + " entry and the seat is " + seat.kind();
        }
        else if (seat.kind() == ModelKind.LLM && entry.getGrade() == null) {
            why = "it carries no grade, and an entry on no rung serves no seat on the ladder";
        }
        else {
            why = whyNot(entry, situation);
        }
        if (why == null) {
            return entry;
        }
        if (roleWarned.add(role.name() + "/" + why)) {
            log.warn("Model role {} prefers {}, which cannot serve seat {} ({}): serving the role from grade {} instead.",
                    role.name(), role.catalogId(), seat.jobClass().getSimpleName(), why, role.grade());
        }
        return null;
    }

    /** Role and reason pairs already warned about, so a fan-out of one role's work warns once. */
    private final Set<String> roleWarned = ConcurrentHashMap.newKeySet();

    /**
     * Why the entry cannot serve a request under this situation, in words for a log line, or
     * null when it can: {@link #whyNot(ModelSpec)}, then the compliance envelope, then the
     * inputs the request declares ({@link Situation#getSends()}).
     */
    protected final String whyNot(ModelSpec candidate, Situation situation) {
        String why = whyNot(candidate);
        if (why != null) {
            return why;
        }
        if (situation.getEnvelope() != null && !situation.getEnvelope().permits(candidate)) {
            return "the compliance envelope " + situation.getEnvelope().getClass().getSimpleName() + " refuses it";
        }
        if (situation.getSends() != null) {
            for (Input input : situation.getSends()) {
                if (!candidate.accepts(input)) {
                    return "it does not accept " + input.name().toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    /**
     * Why the entry cannot be called by this deployment now, in words for a log line, or
     * null when it can: it is closed, this deployment cannot call it
     * ({@link #whyNotCallable}), or the availability probe has turned it off
     * ({@link #isTurnedOff(ModelSpec)}).
     */
    protected final String whyNot(ModelSpec candidate) {
        if (candidate.getStatus() != ModelStatus.OPEN) {
            return "it is " + candidate.getStatus().name().toLowerCase(Locale.ROOT);
        }
        String why = whyNotCallable(candidate);
        if (why != null) {
            return why;
        }
        if (isTurnedOff(candidate)) {
            return "the availability probe reports it down";
        }
        return null;
    }

    /**
     * Why this deployment cannot call the entry, in words for a log line, or null when it
     * can: {@link #whyNotProvisioned} (its provider on the classpath, its data-share posture
     * provisioned), then its provider's credential in the store. A test suite that makes no
     * calls and holds no credential answers null for everything.
     */
    protected String whyNotCallable(ModelSpec candidate) {
        String why = whyNotProvisioned(candidate);
        if (why != null) {
            return why;
        }
        return ClientProviders.get(candidate.getProviderKey()).configured() ? null
                : "its provider " + candidate.getProviderKey() + " has no credential";
    }

    /**
     * Why this classpath and configuration cannot serve the entry at all, before any
     * credential: no provider artifact on the classpath declares its provider, or it is
     * served only under provider data sharing and no Mantle project
     * ({@code ModelSettings.mantleLaxProject}) provides it. Null when it is provisioned.
     */
    protected String whyNotProvisioned(ModelSpec candidate) {
        if (ClientProviders.find(candidate.getProviderKey()) == null) {
            return "no provider artifact on the classpath declares " + candidate.getProviderKey();
        }
        if (candidate.requiresLax() && Settings.get(ModelSettings.class).mantleLaxProject == null) {
            return "it is served only under provider data sharing and no Mantle project provides it";
        }
        return null;
    }

    /**
     * The 529 default: when the route this pick would repeat just answered overloaded,
     * serve the SAME identity from a sibling Bedrock route instead - identical weights,
     * identical Anthropic surface, different pipe. Confined to Bedrock-hosted routes
     * (mantle <-> native <-> converse), never a direct endpoint; every already-overloaded
     * route of this execution is excluded, and a candidate must be undeprecated,
     * envelope-permitted, and not probe-dead. No candidate left means the pick stands
     * and the dispatcher's backoff continues. Non-sticky by design: the next fresh call
     * returns to the pick, and a still-drowning route pays one 529 round before failing
     * over again - the cache-repay economics of sticky failover wait for evidence from
     * real 529 storms.
     */
    private ModelSpec overloadFailover(ModelSpec picked, Situation situation) {
        List<Situation.Attempt> attempts = situation.getAttempts();
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        Situation.Attempt last = attempts.get(attempts.size() - 1);
        if (!(last.failure() instanceof OverloadRetryException)
                || !picked.getIdentity().equals(last.spec().getIdentity())) {
            return null;
        }
        Set<String> overloadedRoutes = new HashSet<>();
        for (Situation.Attempt attempt : attempts) {
            if (attempt.failure() instanceof OverloadRetryException
                    && picked.getIdentity().equals(attempt.spec().getIdentity())) {
                overloadedRoutes.add(attempt.spec().getProviderKey());
            }
        }
        if (!overloadedRoutes.contains(picked.getProviderKey())) {
            // The pick already routes around the overload - nothing to do
            return null;
        }
        for (ModelSpec candidate : Models.variants(picked.getIdentity())) {
            if (overloadedRoutes.contains(candidate.getProviderKey())
                    || !substitutable(candidate, situation)) {
                continue;
            }
            log.warn("529 failover: {} overloaded, serving {} (same identity {}, Bedrock sibling route)", picked.getId(), candidate.getId(), picked.getIdentity());
            return candidate;
        }
        return null;
    }

    /**
     * The deployment's failover route policy: may this candidate route substitute for
     * an overloaded sibling of the same identity? A POLICY question the framework
     * cannot answer - which endpoints are acceptable substitutes (in-account only?
     * never direct? anything the envelope permits?) is a deployment decision, so every
     * stateful picker must state it. Return false unconditionally to disable
     * substitution entirely. The framework enforces its own invariants separately
     * (undeprecated, not probe-dead, envelope-permitted) - this hook is route policy
     * only, and the callers add their situational exclusions on top (routes already
     * overloaded this execution, strictly-less-throttled for diversion).
     */
    protected abstract boolean eligibleForFailover(ModelSpec candidate, Situation situation);

    /** The framework's own substitution walls plus the deployment's route policy. */
    private boolean substitutable(ModelSpec candidate, Situation situation) {
        return candidate.getStatus() == ModelStatus.OPEN
                && !isTurnedOff(candidate, health.get(candidate.getId()))
                && (situation.getEnvelope() == null || situation.getEnvelope().permits(candidate))
                && eligibleForFailover(candidate, situation);
    }

    // Below this slowdown factor the route carries its own load; from here diversion
    // grows with the throttle, and at FULL_DIVERSION everything reroutes
    static final double DIVERSION_START = 2.0;
    static final double FULL_DIVERSION = 10.0;

    /**
     * The proportional half of overload relief: retries of a 529'd call already fail
     * over, but a drowning route needs NEW traffic taken off it too, or nothing
     * alleviates the pressure and every job pays the 529 tax. The adaptive limiter
     * already integrates the provider's own signals (529 weighted 3x, successes walk
     * it back), so its slowdown factor IS the intelligence: from 2x a growing share of
     * fresh picks diverts to the healthiest sibling Bedrock route - half at 2x, two
     * thirds at 3x, everything at 10x - and the share decays to zero as the route
     * recovers, with no state of our own to manage. A candidate must be strictly less
     * throttled than the pick (rerouting between two drowning routes relieves nothing)
     * and passes the same walls as every failover: Bedrock-hosted only, undeprecated,
     * envelope-permitted, not probe-dead.
     */
    private ModelSpec throttleDiversion(ModelSpec picked, Situation situation) {
        double slowdown = routeSlowdown(picked);
        double fraction = diversionFraction(slowdown);
        if (fraction <= 0.0) {
            return null;
        }
        if (fraction < 1.0 && ThreadLocalRandom.current().nextDouble() >= fraction) {
            return null;
        }
        ModelSpec best = null;
        double bestSlowdown = slowdown;
        for (ModelSpec candidate : Models.variants(picked.getIdentity())) {
            if (candidate.getId().equals(picked.getId()) || !substitutable(candidate, situation)) {
                continue;
            }
            double candidateSlowdown = routeSlowdown(candidate);
            if (candidateSlowdown < bestSlowdown) {
                best = candidate;
                bestSlowdown = candidateSlowdown;
            }
        }
        if (best != null) {
            log.info(String.format("throttle diversion: %s at %.1fx slowdown, serving %s (%.1fx) for this job",
                    picked.getId(), slowdown, best.getId(), bestSlowdown));
        }
        return best;
    }

    /** The diverted share of fresh picks for a route slowdown factor: 0 below 2x, 1 - 1/x between, all at 10x. */
    static double diversionFraction(double slowdown) {
        if (slowdown < DIVERSION_START) {
            return 0.0;
        }
        if (slowdown >= FULL_DIVERSION) {
            return 1.0;
        }
        return 1.0 - 1.0 / slowdown;
    }

    /**
     * The route's current slowdown factor from its adaptive limiter (1.0 = unthrottled;
     * the limiter's throttle coefficient stretches the refill window by this factor).
     * Overridable so tests drive the diversion without a live limiter.
     */
    protected double routeSlowdown(ModelSpec spec) {
        return 1.0 + RateLimiterRegistry.getInstance().getRateLimiter(spec).getThrottleCoefficient();
    }

    /**
     * The subclass's policy: seat + situation to spec, with no availability concern -
     * the template applies it. Never sees an embeddings seat: the embeddings model is
     * corpus configuration ({@link #embeddingsSpec()}), resolved and frozen outside
     * this template, so the turnoff policy can never remove or replace it.
     */
    protected abstract ModelSpec pick(Seat seat, Situation situation);

    /**
     * A picker FAMILY's invariant over its own answers, applied to every pick before
     * availability and failover ever see it - how an intermediate base (ZDR-only, ...)
     * constrains what its subclasses may pick without owning their policy. Throw to
     * refuse; the default accepts everything.
     */
    protected void vetPick(ModelSpec picked, Seat seat, Situation situation) {
    }

    @Override
    public ProbeOutcome recordProbe(ProbeOutcome outcome) {
        ProbeOutcome[] displaced = new ProbeOutcome[1];
        health.compute(outcome.getSpecId(), (id, prior) -> {
            displaced[0] = prior != null ? prior.lastOutcome() : null;
            Instant lastSuccess = prior != null ? prior.lastSuccessAt() : null;
            Instant lastFailure = prior != null ? prior.lastFailureAt() : null;
            long consecutive = prior != null ? prior.consecutiveFailures() : 0;
            switch (outcome.getStatus()) {
                case OK -> {
                    lastSuccess = outcome.getProbedAt();
                    consecutive = 0;
                }
                case FAILED -> {
                    if (outcome.getClassification() == ProbeOutcome.Classification.AVAILABILITY) {
                        lastFailure = outcome.getProbedAt();
                        consecutive++;
                    }
                }
                // EXCLUDED touches neither aggregate: a compliance refusal says nothing about availability
                case EXCLUDED -> {}
            }
            return new SpecHealth(lastSuccess, lastFailure, consecutive, outcome);
        });
        return displaced[0];
    }

    /**
     * Whether the probe's observations have turned the spec off, under {@link #isTurnedOff(ModelSpec, SpecHealth)}
     * over what this picker has recorded for it. For a policy that chooses among entries and
     * would rather skip a turned-off one than be refused for it by {@link #provide}.
     */
    protected final boolean isTurnedOff(ModelSpec spec) {
        return isTurnedOff(spec, health.get(spec.getId()));
    }

    /**
     * The default turnoff rule: an availability failure inside the window with no
     * success inside it. Overridable per deployment; {@code health} may be null (never
     * probed), which never turns a spec off.
     */
    protected boolean isTurnedOff(ModelSpec spec, SpecHealth health) {
        if (health == null || health.lastFailureAt() == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(TURNOFF_WINDOW);
        boolean failedInWindow = health.lastFailureAt().isAfter(cutoff);
        boolean succeededInWindow = health.lastSuccessAt() != null && health.lastSuccessAt().isAfter(cutoff);
        return failedInWindow && !succeededInWindow;
    }
}
