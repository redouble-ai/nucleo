/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The acceptance matrix of framework enforcement. Two seats, both covered here:
 * the dispatch path enforcing the job-based guardrail kinds (admission, content,
 * auth) on every route, and the submission door enforcing the scope rung and the
 * submission rules - only orchestrators submit, parents match threads, a flow's
 * sealed guard judges every claim-carrying child, and inheritance happens at the
 * one door every submission uses. Fixtures use the shipped reference guardrails
 * and the reference tenant axis wherever one fits.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public class GuardrailEnforcementTest {

    @BeforeAll
    static void startDispatcher() {
        // the delegation fixtures run real thinkers offline: a conversation is obtained
        // but no LLM call is ever made; resolution goes through the suite's TestModelPicker
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root(String user) {
        return Job.workflow(user, "guardrail-enforcement-test");
    }

    // ---- guardrail fixtures (job-based kinds) ----

    static class RefusingInputGuard extends AbstractContentGuardrail<Object> {
        RefusingInputGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) throws GuardrailException {
            throw new GuardrailException("input refused by fixture");
        }
    }

    /** Refuses, and keeps the context of its own run so the test can read what it recorded after. */
    static class ContextKeepingRefusingGuard extends AbstractContentGuardrail<Object> {
        final AtomicReference<JobContext<Void>> ownContext = new AtomicReference<>();

        ContextKeepingRefusingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void preExecute(JobContext<Void> context) {
            super.preExecute(context);
            ownContext.set(context);
        }

        @Override
        public void validate(Object target) throws GuardrailException {
            throw new GuardrailException("input refused by fixture");
        }
    }

    static class CrashingGuard extends AbstractContentGuardrail<Object> {
        CrashingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            throw new IllegalStateException("guard infrastructure down");
        }
    }

    static class CountingGuard extends AbstractContentGuardrail<Object> {
        static final AtomicInteger RUNS = new AtomicInteger();

        CountingGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            RUNS.incrementAndGet();
        }
    }

    static class SecretRefusingOutputGuard extends AbstractContentGuardrail<Object> {
        SecretRefusingOutputGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        public Direction direction() {
            return Direction.OUTPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) throws GuardrailException {
            if (String.valueOf(target).contains("secret")) {
                throw new GuardrailException("output refused by fixture");
            }
        }
    }

    /** A guard carrying its own tenant claim: the door judges it against the gated flow before it runs. */
    static class TenantClaimingGuard extends AbstractContentGuardrail<Object> implements TenantScoped {
        static final AtomicInteger RUNS = new AtomicInteger();
        private final String claimedTenant;

        TenantClaimingGuard(Identifiable parent, String claimedTenant) {
            super(parent);
            this.claimedTenant = claimedTenant;
        }

        @Override
        public String getTenantId() {
            return claimedTenant;
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            RUNS.incrementAndGet();
        }
    }

    /**
     * A guard that is a submission authority: its verdict comes from a child it submits
     * through the public door, so the child is judged against the guard's sealed binding.
     */
    static class SpawningGuard extends AbstractContentGuardrail<Object> implements ScopeAuthority {
        private final String childClaim;

        SpawningGuard(Identifiable parent, String childClaim) {
            super(parent);
            this.childClaim = childClaim;
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) throws GuardrailException {
            TenantTool child = new TenantTool(this);
            child.setInput(new TenantInput(childClaim));
            try {
                JobDispatcher.getInstance().submit(child).get();
            }
            catch (ExecutionException e) {
                GuardrailException refusal = (GuardrailException) refusalIn(e);
                assertNotNull(refusal, "the only failure a tenant child can produce here is a scope refusal: " + e);
                throw refusal;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncorrectableRuntimeLLMException("interrupted while awaiting the judge child");
            }
        }
    }

    /** Refuses the first N candidates it sees, then passes. */
    static class RefusingValidationGuard extends AbstractValidationGuardrail<Object> {
        static final AtomicInteger RUNS = new AtomicInteger();
        private final int refuseFirst;

        RefusingValidationGuard(Identifiable parent, int refuseFirst) {
            super(parent);
            this.refuseFirst = refuseFirst;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) throws GuardrailException {
            if (RUNS.incrementAndGet() <= refuseFirst) {
                throw new GuardrailException("candidate answer refused by fixture");
            }
        }
    }

    static class CrashingValidationGuard extends AbstractValidationGuardrail<Object> {
        CrashingValidationGuard(Identifiable parent) {
            super(parent);
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            throw new IllegalStateException("validation infrastructure down");
        }
    }

    /** A validation guard with its own tenant claim - judged at the internal door like any scoped guard. */
    static class TenantClaimingValidationGuard extends AbstractValidationGuardrail<Object> implements TenantScoped {
        static final AtomicInteger RUNS = new AtomicInteger();
        private final String claimedTenant;

        TenantClaimingValidationGuard(Identifiable parent, String claimedTenant) {
            super(parent);
            this.claimedTenant = claimedTenant;
        }

        @Override
        public String getTenantId() {
            return claimedTenant;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            RUNS.incrementAndGet();
        }
    }

    /** Minimal guarded leaf: guards supplied per instance, execution recorded. */
    static class FixtureTool extends AbstractTool<String, String> {
        final List<ContentGuardrail<?>> contentGuards = new ArrayList<>();
        final List<AuthGuardrail<?>> authGuards = new ArrayList<>();
        final List<AdmissionGuardrail> admissionGuards = new ArrayList<>();
        final AtomicBoolean executed = new AtomicBoolean(false);
        private boolean failFirstAttemptWithRateLimit;

        FixtureTool(Identifiable parent) {
            super(parent);
        }

        void failFirstAttemptWithRateLimit() {
            this.failFirstAttemptWithRateLimit = true;
        }

        @Override
        public List<ContentGuardrail<?>> declareContentGuardrails() {
            return List.copyOf(contentGuards);
        }

        @Override
        public List<AuthGuardrail<?>> declareAuthGuardrails() {
            return List.copyOf(authGuards);
        }

        @Override
        public List<AdmissionGuardrail> declareAdmissionGuardrails() {
            return List.copyOf(admissionGuards);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            req.setReadOnly(true);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws LLMReadableCheckedException {
            if (failFirstAttemptWithRateLimit && executed.compareAndSet(false, true)) {
                throw new RateLimitRetryException("fixture rate limit", null, 1, null, null, null);
            }
            executed.set(true);
            return "ran:" + context.getMetadata(GuardrailEnforcer.OBS_GUARDS_INPUT) + ":" + getInput();
        }
    }

    // ---- scope fixtures (the tenant axis, marker-declared) ----

    record TenantInput(String tenant) implements TenantScoped {
        @Override
        public String getTenantId() {
            return tenant;
        }
    }

    static class TenantTool extends AbstractTool<TenantInput, String> {
        TenantTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            req.setReadOnly(true);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return getInput().getTenantId();
        }
    }

    /** A tenant-bound doer that submits one guarded leaf, the guard built against that leaf. */
    static class GuardedToolDoer extends AbstractDoer<String, String> implements TenantScoped {
        private final String boundTenant;
        private final Function<FixtureTool, ContentGuardrail<?>> guardFor;
        final AtomicReference<FixtureTool> spawned = new AtomicReference<>();

        GuardedToolDoer(Identifiable parent, String boundTenant, Function<FixtureTool, ContentGuardrail<?>> guardFor) {
            super(parent);
            this.boundTenant = boundTenant;
            this.guardFor = guardFor;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                FixtureTool tool = new FixtureTool(this);
                tool.contentGuards.add(guardFor.apply(tool));
                tool.setInput("x");
                spawned.set(tool);
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** Scope authority by marker alone: implementing TenantScoped IS the declaration. */
    static class TenantAuthorityDoer extends AbstractDoer<String, String> implements TenantScoped {
        private final String boundTenant;
        private final String claimedTenant;
        private final boolean viaIntermediateDoer;

        TenantAuthorityDoer(Identifiable parent, String boundTenant, String claimedTenant, boolean viaIntermediateDoer) {
            super(parent);
            this.boundTenant = boundTenant;
            this.claimedTenant = claimedTenant;
            this.viaIntermediateDoer = viaIntermediateDoer;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                if (viaIntermediateDoer) {
                    return submitInStep(new PassThroughDoer(this, claimedTenant)).get();
                }
                TenantTool tool = new TenantTool(this);
                tool.setInput(new TenantInput(claimedTenant));
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** No declarations of its own: proves inheritance crosses undeclared levels. */
    static class PassThroughDoer extends AbstractDoer<String, String> {
        private final String claimedTenant;

        PassThroughDoer(Identifiable parent, String claimedTenant) {
            super(parent);
            this.claimedTenant = claimedTenant;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                TenantTool tool = new TenantTool(this);
                tool.setInput(new TenantInput(claimedTenant));
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** Authority fanning out N concurrent claiming children; reports pass/refusal counts. */
    static class ParallelClaimsDoer extends AbstractDoer<String, String> implements TenantScoped {
        private final String boundTenant;
        private final List<String> claims;

        ParallelClaimsDoer(Identifiable parent, String boundTenant, List<String> claims) {
            super(parent);
            this.boundTenant = boundTenant;
            this.claims = claims;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            List<JobHandle<String>> handles = new ArrayList<>();
            for (String claim : claims) {
                TenantTool tool = new TenantTool(this);
                tool.setInput(new TenantInput(claim));
                handles.add(submitInStep(tool));
            }
            int ok = 0;
            int refused = 0;
            for (JobHandle<String> handle : handles) {
                try {
                    handle.get();
                    ok++;
                }
                catch (Exception e) {
                    if (refusalIn(e) == null) {
                        throw LLMReadableCheckedException.unwrap(e);
                    }
                    refused++;
                }
            }
            return "ok:" + ok + " refused:" + refused;
        }
    }

    /** Outer authority whose subtree contains an inner authority with a DIFFERENT binding. */
    static class OuterAuthorityDoer extends AbstractDoer<String, String> implements TenantScoped {
        OuterAuthorityDoer(Identifiable parent) {
            super(parent);
        }

        @Override
        public String getTenantId() {
            return "t1";
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                return submitInStep(new TenantAuthorityDoer(this, "t2", "t2", false)).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** Tries to shed its inherited guard mid-run, then submits a drifting claim. */
    static class SheddingDoer extends AbstractDoer<String, String> {
        private final String claimedTenant;
        final AtomicBoolean shedRefused = new AtomicBoolean(false);

        SheddingDoer(Identifiable parent, String claimedTenant) {
            super(parent);
            this.claimedTenant = claimedTenant;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                setScopeGuard(null);
            }
            catch (IllegalStateException expected) {
                shedRefused.set(true);
            }
            try {
                TenantTool tool = new TenantTool(this);
                tool.setInput(new TenantInput(claimedTenant));
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    static class OuterOfSheddingDoer extends AbstractDoer<String, String> implements TenantScoped {
        final SheddingDoer shedder;

        OuterOfSheddingDoer(Identifiable parent) {
            super(parent);
            this.shedder = new SheddingDoer(this, "t9");
        }

        @Override
        public String getTenantId() {
            return "t1";
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                return submitInStep(shedder).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** Non-Scoped doer given a guard by explicit assignment before dispatch. */
    static class AssignedGuardDoer extends AbstractDoer<String, String> {
        private final String claimedTenant;

        AssignedGuardDoer(Identifiable parent, String claimedTenant) {
            super(parent);
            this.claimedTenant = claimedTenant;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                TenantTool tool = new TenantTool(this);
                tool.setInput(new TenantInput(claimedTenant));
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** A resource-holding-shaped plain job that tries to submit - the caller rule's prey. */
    static class RogueSubmittingJob extends AbstractJob<String> {
        RogueSubmittingJob(Identifiable parent) {
            super(parent, "rogue-submitter");
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            FixtureTool tool = new FixtureTool(this);
            tool.setInput("x");
            return JobDispatcher.getInstance().submit(tool).get();
        }
    }

    /** Authority whose scope() blows up - must become a refused submission, not an escape. */
    static class ThrowingScopeDoer extends AbstractDoer<String, String> implements TenantScoped {
        ThrowingScopeDoer(Identifiable parent) {
            super(parent);
        }

        @Override
        public String getTenantId() {
            throw new IllegalStateException("scope resolution blew up");
        }

        @Override
        public String execute(JobContext<String> context) {
            return "never";
        }
    }

    /** Orchestrator resolving a prompt mid-run: root-style establishment from a job thread. */
    static class PromptUsingDoer extends AbstractDoer<String, String> {
        PromptUsingDoer(Identifiable parent) {
            super(parent);
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            return Prompts.of("a perfectly ordinary prompt").content().asText();
        }
    }

    /** Doer parked on a latch so the test can address it as a live job from outside. */
    static class LatchedDoer extends AbstractDoer<String, String> {
        final CountDownLatch release = new CountDownLatch(1);

        LatchedDoer(Identifiable parent) {
            super(parent);
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                release.await();
                return "released";
            }
            catch (InterruptedException e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    // ---- refined-axis fixtures (a scope hierarchy judged at the door) ----

    static class RegionScope implements Scope {
        final String region;

        RegionScope(String region) {
            this.region = region;
        }

        @Override
        public boolean matches(Scope candidate) {
            return region.equals(((RegionScope)candidate).region);
        }

        @Override
        public boolean equals(Object o) {
            return o != null && getClass() == o.getClass() && region.equals(((RegionScope)o).region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getClass(), region);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[" + region + "]";
        }
    }

    static class CityScope extends RegionScope {
        final String city;

        CityScope(String region, String city) {
            super(region);
            this.city = city;
        }

        @Override
        public boolean matches(Scope candidate) {
            if (!super.matches(candidate)) {
                return false;
            }
            return !(candidate instanceof CityScope other) || city.equals(other.city);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && city.equals(((CityScope)o).city);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), city);
        }

        @Override
        public String toString() {
            return "CityScope[" + region + "/" + city + "]";
        }
    }

    static class DistrictScope extends RegionScope {
        final String district;

        DistrictScope(String region, String district) {
            super(region);
            this.district = district;
        }

        @Override
        public boolean matches(Scope candidate) {
            if (!super.matches(candidate)) {
                return false;
            }
            return !(candidate instanceof DistrictScope other) || district.equals(other.district);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && district.equals(((DistrictScope)o).district);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), district);
        }

        @Override
        public String toString() {
            return "DistrictScope[" + region + "/" + district + "]";
        }
    }

    interface RegionScoped extends Scoped {
        String getRegion();

        @Override
        default Scope scope() {
            return new RegionScope(getRegion());
        }
    }

    interface CityScoped extends RegionScoped {
        String getCity();

        @Override
        default Scope scope() {
            return new CityScope(getRegion(), getCity());
        }
    }

    interface DistrictScoped extends RegionScoped {
        String getDistrict();

        @Override
        default Scope scope() {
            return new DistrictScope(getRegion(), getDistrict());
        }
    }

    record RegionInput(String region) implements RegionScoped {
        @Override
        public String getRegion() {
            return region;
        }
    }

    record CityInput(String region, String city) implements CityScoped {
        @Override
        public String getRegion() {
            return region;
        }

        @Override
        public String getCity() {
            return city;
        }
    }

    record DistrictInput(String region, String district) implements DistrictScoped {
        @Override
        public String getRegion() {
            return region;
        }

        @Override
        public String getDistrict() {
            return district;
        }
    }

    /** Leaf taking any claim-carrying input, so one tool serves every axis level. */
    static class ClaimTool extends AbstractTool<Scoped, String> {
        ClaimTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            req.setReadOnly(true);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return String.valueOf(getInput());
        }
    }

    /** Parent-axis authority submitting one claim-carrying tool call. */
    static class RegionAuthorityDoer extends AbstractDoer<String, String> implements RegionScoped {
        private final String boundRegion;
        private final Scoped claim;

        RegionAuthorityDoer(Identifiable parent, String boundRegion, Scoped claim) {
            super(parent);
            this.boundRegion = boundRegion;
            this.claim = claim;
        }

        @Override
        public String getRegion() {
            return boundRegion;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                ClaimTool tool = new ClaimTool(this);
                tool.setInput(claim);
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** Refined-axis authority as the workflow root: no parent-level ancestor to lean on. */
    static class CityAuthorityDoer extends AbstractDoer<String, String> implements CityScoped {
        private final String boundRegion;
        private final String boundCity;
        private final Scoped claim;

        CityAuthorityDoer(Identifiable parent, String boundRegion, String boundCity, Scoped claim) {
            super(parent);
            this.boundRegion = boundRegion;
            this.boundCity = boundCity;
            this.claim = claim;
        }

        @Override
        public String getRegion() {
            return boundRegion;
        }

        @Override
        public String getCity() {
            return boundCity;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                ClaimTool tool = new ClaimTool(this);
                tool.setInput(claim);
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    // ---- delegation fixtures (a REAL SubThinker spawned by a scoped thinker) ----

    /**
     * Spawns a real {@link SubThinker} through the same submission the tool path
     * uses. Unscoped itself - the control: with no scope to inherit, the delegate's
     * claim is nobody's business and passes. Its thinking loop is replaced so the
     * fixture runs offline; what is under test is the delegation edge, not the LLM loop.
     */
    static class SpawningThinker extends SingleObjectiveThinker<VoidThinkerInput, VoidThinkerOutput> {
        protected final String claimedTenant;
        final AtomicReference<ScopeGuard> delegateGuard = new AtomicReference<>();
        /** How many further levels the delegate itself should spawn. */
        int furtherDelegations = 0;

        SpawningThinker(Identifiable parent, String claimedTenant) {
            this(parent, claimedTenant, false);
        }

        SpawningThinker(Identifiable parent, String claimedTenant, boolean readOnly) {
            // Scripted: no model is ever called, but every thinker declares
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), readOnly);
            setAnswerHandler(new PojoResponseHandler<>(VoidThinkerOutput.class));
            this.claimedTenant = claimedTenant;
        }

        /** Hook for assertions about the delegate, called after its dispatch sealed it. */
        void onDelegateDispatched(ProbeSubThinker delegate) {
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected String getSystemPromptText() {
            return "delegation fixture";
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) throws LLMReadableCheckedException {
            ProbeSubThinker delegate = new ProbeSubThinker(this, claimedTenant, furtherDelegations);
            SubThinkerInput input = new SubThinkerInput();
            input.setQuery("probe the delegation edge");
            delegate.setInput(input);
            try {
                submitInIteration(1, delegate).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
            finally {
                // read after dispatch: the door seals the effective guard onto the child
                delegateGuard.set(delegate.getScopeGuard());
                onDelegateDispatched(delegate);
            }
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return new VoidThinkerOutput();
        }
    }

    /** The same spawn, from a thinker that IS tenant-bound. */
    static class ScopedSpawningThinker extends SpawningThinker implements TenantScoped {
        private final String boundTenant;

        ScopedSpawningThinker(Identifiable parent, String boundTenant, String claimedTenant) {
            super(parent, claimedTenant);
            this.boundTenant = boundTenant;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }
    }

    /** Input for the annotated fixture tools a delegate inherits through providers. */
    public static class WriteInput {
        public String value;
    }

    /** An annotated tool that changes something - the sweep must withhold it. */
    @ToolName("fixture_writer")
    @ToolDescription("Test tool that changes something")
    public static class WriterTool extends AbstractTool<WriteInput, String> {
        public WriterTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "wrote";
        }
    }

    /** A real SubThinker whose loop makes one claim-carrying tool call. */
    static class ProbeSubThinker extends SubThinker {
        private final String claimedTenant;
        private final int furtherDelegations;
        final AtomicBoolean sawReadOnlyBinding = new AtomicBoolean(false);
        final List<String> offeredTools = new CopyOnWriteArrayList<>();
        final AtomicReference<ProbeSubThinker> nested = new AtomicReference<>();

        ProbeSubThinker(Identifiable parent, String claimedTenant) {
            this(parent, claimedTenant, 0);
        }

        ProbeSubThinker(Identifiable parent, String claimedTenant, int furtherDelegations) {
            super(parent);
            this.claimedTenant = claimedTenant;
            this.furtherDelegations = furtherDelegations;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<SubThinkerOutput> context) throws LLMReadableCheckedException {
            // read mid-execution, with the guard the door sealed
            sawReadOnlyBinding.set(isForceReadOnly());
            try {
                if (furtherDelegations > 0) {
                    // delegate onward BEFORE this level's sweep, so the deeper level
                    // inherits the registry as it stands and has to withhold for itself
                    ProbeSubThinker deeper = new ProbeSubThinker(this, claimedTenant, furtherDelegations - 1);
                    SubThinkerInput deeperInput = new SubThinkerInput();
                    deeperInput.setQuery("probe one level deeper");
                    deeper.setInput(deeperInput);
                    nested.set(deeper);
                    submitInIteration(1, deeper).get();
                }
                else {
                    TenantTool tool = new TenantTool(this);
                    tool.setInput(new TenantInput(claimedTenant));
                    submitInIteration(1, tool).get();
                }
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
            finally {
                for (ContentBlocks.ToolDefinitionBlock block : buildToolDefinitionBlocks()) {
                    offeredTools.add(block.name());
                }
            }
        }

        @Override
        protected SubThinkerOutput getResult() {
            SubThinkerOutput output = new SubThinkerOutput();
            output.setSummary("probe complete");
            return output;
        }
    }

    /** A read-only-bound thinker that spawns a delegate and reports what it inherited. */
    static class ReadOnlySpawningThinker extends SpawningThinker {
        final AtomicBoolean delegateWasReadOnly = new AtomicBoolean(false);

        ReadOnlySpawningThinker(Identifiable parent, String claimedTenant) {
            super(parent, claimedTenant, true);
        }

        @Override
        void onDelegateDispatched(ProbeSubThinker delegate) {
            delegateWasReadOnly.set(delegate.sawReadOnlyBinding.get());
        }
    }

    /**
     * Bound on BOTH axes at once - tenant-scoped and read-only - and holding a
     * mutating tool the delegate inherits through the provider copy.
     */
    static class DoublyBoundSpawningThinker extends SpawningThinker implements TenantScoped {
        private final String boundTenant;
        final AtomicReference<ProbeSubThinker> delegate = new AtomicReference<>();

        DoublyBoundSpawningThinker(Identifiable parent, String boundTenant, String claimedTenant) {
            super(parent, claimedTenant, true);
            this.boundTenant = boundTenant;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(WriterTool.class);
        }

        @Override
        void onDelegateDispatched(ProbeSubThinker spawned) {
            delegate.set(spawned);
        }
    }

    /** The same double binding without the read-only half - the control for the sweep. */
    static class TenantOnlySpawningThinker extends SpawningThinker implements TenantScoped {
        private final String boundTenant;
        final AtomicReference<ProbeSubThinker> delegate = new AtomicReference<>();

        TenantOnlySpawningThinker(Identifiable parent, String boundTenant, String claimedTenant) {
            super(parent, claimedTenant, false);
            this.boundTenant = boundTenant;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(WriterTool.class);
        }

        @Override
        void onDelegateDispatched(ProbeSubThinker spawned) {
            delegate.set(spawned);
        }
    }

    /**
     * A goal-directed thinker whose LLM turns are scripted: the real loop runs, only the
     * model is replaced. Validation guards are supplied per instance.
     */
    static class ScriptedThinker extends SingleObjectiveThinker<VoidThinkerInput, VoidThinkerOutput> {
        final Deque<ThinkingResponse<VoidThinkerOutput>> script = new ArrayDeque<>();
        final List<ValidationGuardrail<? super VoidThinkerOutput>> validationGuards = new ArrayList<>();
        final AtomicInteger turns = new AtomicInteger();

        ScriptedThinker(Identifiable parent) {
            // Scripted: no model is ever called, but every thinker declares
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
            // The answer's type, which the loop needs to build its response handler even when
            // the model turn itself is scripted.
            setAnswerHandler(new PojoResponseHandler<>(VoidThinkerOutput.class));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected String getSystemPromptText() {
            return "validation seat fixture";
        }

        @Override
        protected List<ValidationGuardrail<? super VoidThinkerOutput>> declareValidationGuardrails() {
            return List.copyOf(validationGuards);
        }

        @Override
        protected ThinkingResponse<VoidThinkerOutput> submitThinkingCall(ConversationContext conversation, JobContext<VoidThinkerOutput> context, int iteration) {
            turns.incrementAndGet();
            return script.pop();
        }

        static ThinkingResponse<VoidThinkerOutput> finalTurn() {
            ThinkingResponse<VoidThinkerOutput> response = new ThinkingResponse<>();
            response.setFinalAnswer(true);
            response.setAnswer(new VoidThinkerOutput());
            return response;
        }
    }

    static class TenantBoundScriptedThinker extends ScriptedThinker implements TenantScoped {
        private final String boundTenant;

        TenantBoundScriptedThinker(Identifiable parent, String boundTenant) {
            super(parent);
            this.boundTenant = boundTenant;
        }

        @Override
        public String getTenantId() {
            return boundTenant;
        }
    }

    /** The one agent class the admission fixture allows. */
    static class AllowedDoer extends AbstractDoer<String, String> {
        AllowedDoer(Identifiable parent) {
            super(parent);
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                FixtureTool tool = new FixtureTool(this);
                tool.admissionGuards.add(new AgentClassAllowListAdmissionGuardrail(tool, Set.of(AllowedDoer.class.getName())));
                tool.setInput("x");
                return submitInStep(tool).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    private static Throwable refusalIn(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof GuardrailException) {
                return t;
            }
        }
        return null;
    }

    private static Throwable findIn(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return t;
            }
        }
        return null;
    }

    // ---- job-based kinds at the dispatch path ----

    @Test
    void doerDirectDispatchEnforcesDeclaredInputGuard() {
        FixtureTool tool = new FixtureTool(root("test-user"));
        tool.contentGuards.add(new RefusingInputGuard(tool));
        tool.setInput("x");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get());
        assertNotNull(refusalIn(e), "expected a GuardrailException refusal in " + e);
        assertFalse(tool.executed.get(), "input refusal must precede execution");
    }

    @Test
    void aRefusingGuardRecordsFailOnItsOwnRun() {
        FixtureTool tool = new FixtureTool(root("test-user"));
        ContextKeepingRefusingGuard guard = new ContextKeepingRefusingGuard(tool);
        tool.contentGuards.add(guard);
        tool.setInput("x");
        assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(tool).get());
        JobContext<Void> own = guard.ownContext.get();
        assertNotNull(own, "the guard ran as its own job");
        assertEquals("FAIL", own.getMetadata("obs.output"), "a refusal is recorded as the guard's verdict");
        assertEquals("CONTENT_INPUT", own.getMetadata(AbstractGuardrail.OBS_GATES_PHASE));
        assertNotNull(own.getMetadata(AbstractGuardrail.OBS_GATES_JOB_ID), "the gated job's id travels with the guard's row");
    }

    @Test
    void outputRefusalSuppressesDelivery() {
        FixtureTool tool = new FixtureTool(root("test-user"));
        tool.contentGuards.add(new SecretRefusingOutputGuard(tool));
        tool.setInput("secret");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get());
        assertNotNull(refusalIn(e), "expected a GuardrailException refusal in " + e);
        assertTrue(tool.executed.get(), "output guards run after execution");
    }

    @Test
    void admissionRefusesDirectCallAndAdmitsAllowedAgent() throws Exception {
        FixtureTool direct = new FixtureTool(root("test-user"));
        direct.admissionGuards.add(new AgentClassAllowListAdmissionGuardrail(direct, Set.of(AllowedDoer.class.getName())));
        direct.setInput("x");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(direct).get());
        Throwable refusal = refusalIn(e);
        assertNotNull(refusal, "expected admission refusal for a direct call: " + e);
        assertTrue(refusal.getMessage().contains("not available through a direct call"),
                "the framework delivered no agent class for a call under the workflow root: " + refusal.getMessage());
        String viaAllowed = JobDispatcher.getInstance().submit(new AllowedDoer(root("test-user"))).get();
        assertTrue(viaAllowed.startsWith("ran:"), viaAllowed);
    }

    @Test
    void principalAllowListJudgesTheWorkflowPrincipal() throws Exception {
        FixtureTool denied = new FixtureTool(root("test-user"));
        denied.authGuards.add(new PrincipalAllowListGuardrail(denied, Set.of("alice")));
        denied.setInput("x");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(denied).get());
        assertNotNull(refusalIn(e), "expected auth refusal for test-user: " + e);
        FixtureTool allowed = new FixtureTool(root("alice"));
        allowed.authGuards.add(new PrincipalAllowListGuardrail(allowed, Set.of("alice")));
        allowed.setInput("x");
        assertEquals("ran:1:x", JobDispatcher.getInstance().submit(allowed).get());
    }

    @Test
    void guardInfrastructureFailureFailsClosedAsItself() {
        FixtureTool tool = new FixtureTool(root("test-user"));
        tool.contentGuards.add(new CrashingGuard(tool));
        tool.setInput("x");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get());
        assertNull(refusalIn(e), "an infrastructure failure must never surface as a refusal");
        assertNotNull(findIn(e, IllegalStateException.class), "the guard's own failure must be preserved: " + e);
        assertFalse(tool.executed.get());
    }

    @Test
    void zeroGuardExecutionIsAnAuditedCount() throws Exception {
        FixtureTool tool = new FixtureTool(root("test-user"));
        tool.setInput("x");
        assertEquals("ran:0:x", JobDispatcher.getInstance().submit(tool).get());
    }

    @Test
    void guardsRunOncePerSubmissionAcrossTransparentRetries() throws Exception {
        CountingGuard.RUNS.set(0);
        FixtureTool tool = new FixtureTool(root("test-user"));
        tool.contentGuards.add(new CountingGuard(tool));
        tool.failFirstAttemptWithRateLimit();
        tool.setInput("x");
        String result = JobDispatcher.getInstance().submit(tool).get();
        assertTrue(result.startsWith("ran:1:"), result);
        assertEquals(1, CountingGuard.RUNS.get(), "guards must not re-run on a transparent retry");
    }

    // ---- the scope rung at the submission door ----

    @Test
    void markerAloneDeclaresTheScopeAndRefusesDriftedClaims() throws Exception {
        assertEquals("t1", JobDispatcher.getInstance().submit(new TenantAuthorityDoer(root("test-user"), "t1", "t1", false)).get());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(new TenantAuthorityDoer(root("test-user"), "t1", "t2", false)).get());
        Throwable refusal = refusalIn(e);
        assertNotNull(refusal, "expected a scope refusal in " + e);
        assertTrue(refusal.getMessage().contains("t1") && refusal.getMessage().contains("t2"), refusal.getMessage());
    }

    @Test
    void inheritanceCrossesUndeclaredLevels() {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(new TenantAuthorityDoer(root("test-user"), "t1", "t2", true)).get());
        assertNotNull(refusalIn(e), "the authority's scope must reach the grandchild: " + e);
    }

    @Test
    void innerAuthorityWithConflictingScopeDiesAtEstablishment() {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(new OuterAuthorityDoer(root("test-user"))).get());
        Throwable refusal = refusalIn(e);
        assertNotNull(refusal, "the inner authority's own scope must be judged at its submission: " + e);
        assertTrue(refusal.getMessage().contains("t1") && refusal.getMessage().contains("t2"), refusal.getMessage());
    }

    @Test
    void shedAttemptThrowsAndTheCapturedGuardStillEnforces() {
        OuterOfSheddingDoer outer = new OuterOfSheddingDoer(root("test-user"));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(outer).get());
        assertTrue(outer.shedder.shedRefused.get(), "setScopeGuard after dispatch must throw");
        assertNotNull(refusalIn(e), "the inherited guard must survive the shed attempt: " + e);
    }

    @Test
    void explicitAssignmentBeforeDispatchGuardsANonScopedDoer() throws Exception {
        AssignedGuardDoer ok = new AssignedGuardDoer(root("test-user"), "t1");
        ok.setScopeGuard(new ScopeGuard(new TenantScope("t1")));
        assertEquals("t1", JobDispatcher.getInstance().submit(ok).get());
        AssignedGuardDoer drift = new AssignedGuardDoer(root("test-user"), "t2");
        drift.setScopeGuard(new ScopeGuard(new TenantScope("t1")));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(drift).get());
        assertNotNull(refusalIn(e), "an assigned guard must enforce like a marker-declared one: " + e);
    }

    @Test
    void concurrentFanoutChecksEveryWorkerAgainstTheSealedGuard() throws Exception {
        List<String> claims = List.of("t1", "t9", "t1", "t9", "t1", "t1", "t9", "t1");
        assertEquals("ok:5 refused:3", JobDispatcher.getInstance().submit(new ParallelClaimsDoer(root("test-user"), "t1", claims)).get());
    }

    @Test
    void concurrentWorkflowsKeepTheirGuardsApart() throws Exception {
        JobHandle<String> a = JobDispatcher.getInstance().submit(
                new ParallelClaimsDoer(root("test-user"), "tA", List.of("tA", "tA", "tA", "tA")));
        JobHandle<String> b = JobDispatcher.getInstance().submit(
                new ParallelClaimsDoer(root("test-user"), "tB", List.of("tB", "tB", "tB", "tB")));
        assertEquals("ok:4 refused:0", a.get());
        assertEquals("ok:4 refused:0", b.get());
    }

    @Test
    void parentBoundAuthorityJudgesRefinedClaimsAtTheSharedAxis() throws Exception {
        assertEquals("CityInput[region=emea, city=berlin]", JobDispatcher.getInstance()
                .submit(new RegionAuthorityDoer(root("test-user"), "emea", new CityInput("emea", "berlin"))).get());
        ExecutionException e = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance()
                .submit(new RegionAuthorityDoer(root("test-user"), "emea", new CityInput("apac", "tokyo"))).get());
        Throwable refusal = refusalIn(e);
        assertNotNull(refusal, "expected the door to judge the child claim on the region axis: " + e);
        assertTrue(refusal.getMessage().contains("emea") && refusal.getMessage().contains("apac"), refusal.getMessage());
    }

    @Test
    void refinedBoundAuthorityJudgesParentClaims() throws Exception {
        assertEquals("RegionInput[region=emea]", JobDispatcher.getInstance()
                .submit(new CityAuthorityDoer(root("test-user"), "emea", "berlin", new RegionInput("emea"))).get());
        ExecutionException e = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance()
                .submit(new CityAuthorityDoer(root("test-user"), "emea", "berlin", new RegionInput("apac"))).get());
        assertNotNull(refusalIn(e), "a plain parent-level claim must still be pinned to the shared axis: " + e);
    }

    @Test
    void refinedBoundAuthorityJudgesSiblingClaimsOnTheSharedAxis() throws Exception {
        assertEquals("DistrictInput[region=emea, district=mitte]", JobDispatcher.getInstance()
                .submit(new CityAuthorityDoer(root("test-user"), "emea", "berlin", new DistrictInput("emea", "mitte"))).get());
        ExecutionException e = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance()
                .submit(new CityAuthorityDoer(root("test-user"), "emea", "berlin", new DistrictInput("apac", "shibuya"))).get());
        assertNotNull(refusalIn(e),
                "a sibling axis shares the parent level - its claim must be judged there even with no parent-bound ancestor: " + e);
    }

    @Test
    void delegationCopiesTheSpawningThinkersScopeOntoTheSubAgent() throws Exception {
        ScopedSpawningThinker parent = new ScopedSpawningThinker(root("test-user"), "t1", "t1");
        parent.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(parent).get();
        ScopeGuard delegateGuard = parent.delegateGuard.get();
        assertNotNull(delegateGuard, "a delegate spawned by a scoped thinker must be sealed with a guard");
        assertTrue(delegateGuard.getScopes().contains(new TenantScope("t1")),
                "the spawning thinker's scope must reach the delegate: " + delegateGuard.getScopes());
    }

    @Test
    void delegateCannotDriftOutOfTheSpawningThinkersScope() throws Exception {
        // control: the identical delegate call, spawned by an UNSCOPED thinker, passes -
        // so the refusal below is caused by the inherited scope and nothing else
        SpawningThinker unscoped = new SpawningThinker(root("test-user"), "t9");
        unscoped.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(unscoped).get();
        assertNull(unscoped.delegateGuard.get(), "an unscoped spawner has no guard to pass on");

        ScopedSpawningThinker parent = new ScopedSpawningThinker(root("test-user"), "t1", "t9");
        parent.setInput(new VoidThinkerInput());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(parent).get());
        assertNotNull(refusalIn(e), "a delegate's drifting claim must be refused like the parent's own: " + e);
    }

    @Test
    void delegationCarriesTheReadOnlyBindingTheSameWayAsScope() throws Exception {
        // control: an unbound spawner produces an unbound delegate
        SpawningThinker unbound = new SpawningThinker(root("test-user"), "t1");
        unbound.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(unbound).get();

        ReadOnlySpawningThinker readOnly = new ReadOnlySpawningThinker(root("test-user"), "t1");
        readOnly.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(readOnly).get();
        assertTrue(readOnly.delegateWasReadOnly.get(),
                "a delegate of a read-only flow must itself be read-only - the binding travels like the scope");
        assertTrue(readOnly.delegateGuard.get().getScopes().contains(new ReadOnlyScope()),
                "the binding rides the sealed guard: " + readOnly.delegateGuard.get().getScopes());
    }

    @Test
    void delegateInheritsBothBindingsAtOnceAndAppliesThem() throws Exception {
        DoublyBoundSpawningThinker parent = new DoublyBoundSpawningThinker(root("test-user"), "t1", "t1");
        parent.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(parent).get();

        ProbeSubThinker delegate = parent.delegate.get();
        assertNotNull(delegate);
        List<Scope> inherited = parent.delegateGuard.get().getScopes();
        assertTrue(inherited.contains(new TenantScope("t1")), "the tenant axis must reach the delegate: " + inherited);
        assertTrue(inherited.contains(new ReadOnlyScope()), "the read-only binding must reach it too: " + inherited);
        assertTrue(delegate.sawReadOnlyBinding.get(), "the delegate must know it is read-only while running");
        // capabilities were copied AND the inherited binding applies to them: the
        // mutating tool the parent held is inherited through the provider copy and
        // then withheld from the delegate's own offer
        assertFalse(delegate.offeredTools.contains("fixture_writer"),
                "a delegate of a read-only flow must not offer the mutating tool it inherited: " + delegate.offeredTools);
    }

    @Test
    void delegateOffersTheInheritedMutatingToolWhenNothingBindsIt() throws Exception {
        // control for the sweep above: same spawn, same inherited tool, no read-only
        // binding - so the tool IS offered, and the withholding above is attributable
        // to the inherited binding rather than to the copy losing the tool
        TenantOnlySpawningThinker parent = new TenantOnlySpawningThinker(root("test-user"), "t1", "t1");
        parent.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(parent).get();

        ProbeSubThinker delegate = parent.delegate.get();
        assertFalse(delegate.sawReadOnlyBinding.get(), "nothing bound this delegate read-only");
        assertTrue(delegate.offeredTools.contains("fixture_writer"),
                "the delegate must have inherited the tool at all: " + delegate.offeredTools);
        assertTrue(parent.delegateGuard.get().getScopes().contains(new TenantScope("t1")),
                "the tenant axis still travels on its own");
    }

    @Test
    void bothBindingsSurviveASecondDelegationHop() throws Exception {
        // control first: unbound, two hops - the mutating tool DOES reach the deeper
        // delegate, so the withholding asserted below is the binding's doing and not a
        // provider copy that ran dry at depth
        TenantOnlySpawningThinker unbound = new TenantOnlySpawningThinker(root("test-user"), "t1", "t1");
        unbound.furtherDelegations = 1;
        unbound.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(unbound).get();
        ProbeSubThinker unboundGrandDelegate = unbound.delegate.get().nested.get();
        assertNotNull(unboundGrandDelegate, "the control must reach two hops as well");
        assertTrue(unboundGrandDelegate.offeredTools.contains("fixture_writer"),
                "the tool must survive two provider copies when nothing withholds it: "
                        + unboundGrandDelegate.offeredTools);

        DoublyBoundSpawningThinker parent = new DoublyBoundSpawningThinker(root("test-user"), "t1", "t1");
        parent.furtherDelegations = 1;
        parent.setInput(new VoidThinkerInput());
        JobDispatcher.getInstance().submit(parent).get();

        ProbeSubThinker delegate = parent.delegate.get();
        ProbeSubThinker grandDelegate = delegate.nested.get();
        assertNotNull(grandDelegate, "the delegate must itself have delegated");
        assertNotSame(delegate, grandDelegate);

        List<Scope> inherited = grandDelegate.getScopeGuard().getScopes();
        assertTrue(inherited.contains(new TenantScope("t1")),
                "the tenant axis must survive the second hop: " + inherited);
        assertTrue(inherited.contains(new ReadOnlyScope()),
                "so must the read-only binding: " + inherited);
        assertTrue(grandDelegate.sawReadOnlyBinding.get(), "the deeper delegate must know it is read-only");
        assertFalse(grandDelegate.offeredTools.contains("fixture_writer"),
                "a delegate of a delegate must withhold the mutating tool it inherited: " + grandDelegate.offeredTools);
        // merging is idempotent by value, so a hop adds no duplicate members
        assertEquals(inherited.size(), parent.delegateGuard.get().getScopes().size(),
                "a further hop must not grow the guard: " + inherited);
    }

    @Test
    void aDeeperDelegateStillCannotDrift() {
        DoublyBoundSpawningThinker parent = new DoublyBoundSpawningThinker(root("test-user"), "t1", "t9");
        parent.furtherDelegations = 1;
        parent.setInput(new VoidThinkerInput());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(parent).get());
        assertNotNull(refusalIn(e), "the drifting claim two hops down must still be refused: " + e);
    }

    // ---- the submission rules ----

    @Test
    void nonOrchestratorSubmittingIsRefused() {
        RogueSubmittingJob rogue = new RogueSubmittingJob(root("test-user"));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(rogue).get());
        Throwable cause = findIn(e, SystemException.class);
        assertNotNull(cause, "expected the submission-authority refusal: " + e);
        assertTrue(cause.getMessage().contains("Only orchestrators"), cause.getMessage());
    }

    @Test
    void outsideThreadCannotInjectUnderALiveJob() throws Exception {
        LatchedDoer parked = new LatchedDoer(root("test-user"));
        JobHandle<String> parkedHandle = JobDispatcher.getInstance().submit(parked);
        try {
            FixtureTool injected = new FixtureTool(parked);
            injected.setInput("x");
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> JobDispatcher.getInstance().submit(injected).get());
            Throwable cause = findIn(e, SystemException.class);
            assertNotNull(cause, "expected the parent/thread consistency refusal: " + e);
        }
        finally {
            parked.release.countDown();
        }
        assertEquals("released", parkedHandle.get());
    }

    @Test
    void throwingScopeBecomesARefusedSubmissionNotAnEscape() {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(new ThrowingScopeDoer(root("test-user"))).get());
        Throwable cause = findIn(e, SystemException.class);
        assertNotNull(cause, "scope() failure must be captured as the refusal's cause: " + e);
        assertNotNull(findIn(cause, IllegalStateException.class), "the original failure must be preserved: " + e);
    }

    @Test
    void delayedSubmissionCreatesRootsOnly() throws Exception {
        FixtureTool rootTool = new FixtureTool(root("test-user"));
        rootTool.setInput("x");
        assertEquals("ran:0:x", JobDispatcher.getInstance().submitWithDelay(rootTool, Duration.ofMillis(20)).get().get());
        LatchedDoer parked = new LatchedDoer(root("test-user"));
        JobHandle<String> parkedHandle = JobDispatcher.getInstance().submit(parked);
        try {
            FixtureTool injected = new FixtureTool(parked);
            injected.setInput("x");
            JobHandle<String> delayed = JobDispatcher.getInstance().submitWithDelay(injected, Duration.ofMillis(20)).get();
            ExecutionException e = assertThrows(ExecutionException.class, delayed::get);
            assertNotNull(findIn(e, SystemException.class),
                    "a delayed submission fires on a fresh thread and must not inject under a live job: " + e);
        }
        finally {
            parked.release.countDown();
        }
        assertEquals("released", parkedHandle.get());
    }

    @Test
    void promptResolutionOnAJobThreadPassesTheDoor() throws Exception {
        assertEquals("a perfectly ordinary prompt",
                JobDispatcher.getInstance().submit(new PromptUsingDoer(root("test-user"))).get());
    }

    @Test
    void outsideJobSubmissionAndGetRemainLegal() throws Exception {
        // A plain caller outside any job submits and awaits freely; the deadlock rule
        // itself (resource-holding get refused, orchestrator get legal) is pinned by
        // ResourceContractTest.
        FixtureTool tool = new FixtureTool(root("test-user"));
        tool.setInput("x");
        assertEquals("ran:0:x", JobDispatcher.getInstance().submit(tool).get());
    }

    // ---- guard jobs at the internal door: judged and sealed against the gated flow ----

    @Test
    void scopedGuardIsJudgedAgainstTheFlowThatGatesTheTool() throws Exception {
        TenantClaimingGuard.RUNS.set(0);
        GuardedToolDoer matching = new GuardedToolDoer(root("test-user"), "acme", tool -> new TenantClaimingGuard(tool, "acme"));
        assertEquals("ran:1:x", JobDispatcher.getInstance().submit(matching).get());
        assertEquals(1, TenantClaimingGuard.RUNS.get(), "a guard whose claim matches the flow runs");

        GuardedToolDoer drifting = new GuardedToolDoer(root("test-user"), "acme", tool -> new TenantClaimingGuard(tool, "globex"));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(drifting).get());
        assertNotNull(findIn(e, SystemException.class), "a guard refused at the door is a code error of the gated job: " + e);
        assertNotNull(refusalIn(e), "the door's refusal rides as the cause: " + e);
        assertEquals(1, TenantClaimingGuard.RUNS.get(), "a guard refused at the door never runs");
        assertFalse(drifting.spawned.get().executed.get(), "the gated tool fails closed before executing");
    }

    @Test
    void authorityGuardIsSealedWithTheFlowAndItsChildrenInherit() throws Exception {
        AtomicReference<SpawningGuard> guard = new AtomicReference<>();
        GuardedToolDoer matching = new GuardedToolDoer(root("test-user"), "acme", tool -> {
            guard.set(new SpawningGuard(tool, "acme"));
            return guard.get();
        });
        assertEquals("ran:1:x", JobDispatcher.getInstance().submit(matching).get());
        assertNotNull(guard.get().getScopeGuard(), "an authority guard is sealed at the internal door");
        assertTrue(guard.get().getScopeGuard().getScopes().contains(new TenantScope("acme")),
                "the seal is the gated flow's binding: " + guard.get().getScopeGuard().getScopes());

        GuardedToolDoer drifting = new GuardedToolDoer(root("test-user"), "acme", tool -> new SpawningGuard(tool, "globex"));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(drifting).get());
        assertNotNull(refusalIn(e), "the guard's child drifting out of the inherited scope is refused: " + e);
        assertFalse(drifting.spawned.get().executed.get());
    }

    // ---- the validation rung: a refusal goes back to the producer, not to its caller ----

    @Test
    void validationRefusalIsFedBackAndTheCorrectedAnswerIsDelivered() throws Exception {
        RefusingValidationGuard.RUNS.set(0);
        ScriptedThinker thinker = new ScriptedThinker(root("test-user"));
        thinker.validationGuards.add(new RefusingValidationGuard(thinker, 1));
        thinker.script.add(ScriptedThinker.finalTurn());
        thinker.script.add(ScriptedThinker.finalTurn());
        thinker.setInput(new VoidThinkerInput());
        JobHandle<VoidThinkerOutput> handle = JobDispatcher.getInstance().submit(thinker);
        assertNotNull(handle.get(), "the second candidate passes and is delivered");
        assertEquals(2, thinker.turns.get(), "the refusal costs one iteration and the model answers again");
        assertEquals(2, RefusingValidationGuard.RUNS.get(), "every candidate is validated");
        assertEquals(1, handle.getContext().getMetadata(GuardrailEnforcer.OBS_GUARDS_VALIDATION));
    }

    @Test
    void validationRefusalsExhaustTheIterationBudgetToANullAnswer() throws Exception {
        RefusingValidationGuard.RUNS.set(0);
        ScriptedThinker thinker = new ScriptedThinker(root("test-user"));
        thinker.validationGuards.add(new RefusingValidationGuard(thinker, Integer.MAX_VALUE));
        thinker.setMaxIterations(3);
        for (int i = 0; i < 3; i++) {
            thinker.script.add(ScriptedThinker.finalTurn());
        }
        thinker.setInput(new VoidThinkerInput());
        assertNull(JobDispatcher.getInstance().submit(thinker).get(), "no candidate was accepted");
        assertEquals(3, thinker.turns.get());
        assertEquals(3, RefusingValidationGuard.RUNS.get());
    }

    @Test
    void validationInfrastructureFailureFailsTheProducerClosed() {
        ScriptedThinker thinker = new ScriptedThinker(root("test-user"));
        thinker.validationGuards.add(new CrashingValidationGuard(thinker));
        thinker.script.add(ScriptedThinker.finalTurn());
        thinker.setInput(new VoidThinkerInput());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(thinker).get());
        assertNull(refusalIn(e), "a crash is never a refusal: " + e);
        assertNotNull(findIn(e, SystemException.class), "the crash fails the thinker as itself: " + e);
        assertEquals(1, thinker.turns.get(), "no correction turn follows an infrastructure failure");
    }

    @Test
    void driftingScopedValidationGuardIsACodeErrorNotAVerdict() {
        TenantClaimingValidationGuard.RUNS.set(0);
        TenantBoundScriptedThinker thinker = new TenantBoundScriptedThinker(root("test-user"), "acme");
        thinker.validationGuards.add(new TenantClaimingValidationGuard(thinker, "globex"));
        thinker.script.add(ScriptedThinker.finalTurn());
        thinker.script.add(ScriptedThinker.finalTurn());
        thinker.setInput(new VoidThinkerInput());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(thinker).get());
        assertNotNull(findIn(e, SystemException.class), "a guard refused at the door fails the producer closed: " + e);
        assertNotNull(refusalIn(e), "with the door's refusal as the cause: " + e);
        assertEquals(0, TenantClaimingValidationGuard.RUNS.get(), "the guard never ran");
        assertEquals(1, thinker.turns.get(), "no correction turn was fed back");
    }
}
