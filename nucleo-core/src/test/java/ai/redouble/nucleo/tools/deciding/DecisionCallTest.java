/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.deciding;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One decision as a job: the requirements are priced from the request, so a call without one
 * is refused before it is captured; the need is one decision binding, unpinned unless
 * {@code pinModel} named the entry; the timeout is a minute; and every call, answered or
 * failed, lands on the job's record with its answers or its reason, through the dispatcher
 * against the suite's fake decision model.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
class DecisionCallTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void clearTheModel() {
        FakeDecisionClient.seen.clear();
        FakeDecisionClient.policy = null;
    }

    private static DecisionRequest request() {
        return new DecisionRequest("a ticket about a failed payout", Map.of("ok", Noul.of("Is the ticket about a payout?")));
    }

    @Test
    void aCallWithoutItsRequestIsRefusedWhenTheRequirementsAreCaptured() {
        DecisionCall call = new DecisionCall(Job.workflow("test-user", "decision-call"));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, call::getRequirements);
        assertTrue(refusal.getMessage().contains("request"), "the requirements price the request, so it comes first: " + refusal.getMessage());
    }

    @Test
    void theRequirementsAreOneDecisionBindingAndTheTimeoutIsAMinute() {
        DecisionCall call = new DecisionCall(Job.workflow("test-user", "decision-call"), request());
        JobRequirements requirements = call.getRequirements();
        assertTrue(requirements.requiresDecision());
        assertFalse(requirements.requiresLlm());
        assertEquals(1, requirements.getModelBindings().size(), "one decision, one binding");
        ModelBinding binding = requirements.getModelBindings().get(0);
        assertTrue(binding.isDecision());
        assertFalse(binding.isPinned(), "resolved from the deployment's declaration unless pinned");
        assertTrue(requirements.isReadOnly(), "a decision reads and writes nothing");
        assertEquals(DecisionCall.DEFAULT_TIMEOUT, call.getTimeout(), "a minute: one round trip's worth, a hung endpoint's");
    }

    @Test
    void aDecisionCallCarriesNoToolNameSoNoModelCanCallIt() {
        assertNull(DecisionCall.class.getAnnotation(ai.redouble.nucleo.tools.ToolName.class),
                "a chat model asking a decision model is a caller's deliberate design, through a named tool of its own");
    }

    @Test
    void pinModelNamesTheExactEntry() {
        DecisionCall call = new DecisionCall(Job.workflow("test-user", "decision-call"), request());
        ModelSpec pinned = Models.spec("fake-decider");
        call.pinModel(pinned);
        ModelBinding binding = call.getRequirements().getModelBindings().get(0);
        assertTrue(binding.isPinned());
        assertSame(pinned, binding.getPinnedSpec());
        assertTrue(binding.isDecision());
    }

    @Test
    void theReservationIsTheWireBodyOfTheRequestWithNoOutput() {
        // priced as the wire will carry it: the body without the model field before the entry is
        // resolved, and with the pinned entry's wire id when one is named
        ModelSpec spec = Models.spec("fake-decider");
        DecisionCall unpinned = new DecisionCall(Job.workflow("test-user", "decision-call"), request());
        ModelBinding binding = unpinned.getRequirements().getModelBindings().get(0);
        binding.resolve(spec);
        ModelBinding body = ModelBinding.decision(SystemOneWire.encode(null, request()));
        body.resolve(spec);
        assertEquals(body.price(), binding.price(), "the wire body, counted under the resolved entry's tokenizer");
        assertEquals(0, binding.getReservedOutput(), "a decision generates nothing");
        DecisionCall pinned = new DecisionCall(Job.workflow("test-user", "decision-call"), request());
        pinned.pinModel(spec);
        ModelBinding pinnedBinding = pinned.getRequirements().getModelBindings().get(0);
        pinnedBinding.resolve(spec);
        ModelBinding pinnedBody = ModelBinding.decision(SystemOneWire.encode(spec.getWireModelId(), request()));
        pinnedBody.resolve(spec);
        assertEquals(pinnedBody.price(), pinnedBinding.price(), "with the pinned entry's wire id in the body");
    }

    @Test
    void anAnsweredCallLandsOnTheJobsRecordWithItsAnswers() throws Exception {
        FakeDecisionClient.policy = request -> Map.of("ok", new NoulAnswer(0.9));
        Identifiable workflow = Job.workflow("test-user", "decision-call-answered");
        List<LLMResponse<?>> recorded = new CopyOnWriteArrayList<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<JobCompletedEvent>() {
            @Override
            public Predicate<JobCompletedEvent> getPredicate() {
                return event -> workflow.getWorkflowId().equals(event.snapshot().getWorkflowId());
            }

            @Override
            public void observe(JobCompletedEvent event) {
                recorded.addAll(event.getLlmResponses());
            }
        }, JobCompletedEvent.class);
        try {
            DecisionResponse response = JobDispatcher.getInstance().submit(new DecisionCall(workflow, request())).get(30, TimeUnit.SECONDS);
            assertTrue(response.isSuccessful());
            assertEquals(0.9, response.noul("ok").probability(), 1e-9);
            assertEquals("fake-decider", response.getModel(), "the deployment's declared decision entry answered");
            assertEquals(1, FakeDecisionClient.seen.size(), "one round trip");
            waitFor(() -> !recorded.isEmpty());
            assertEquals(1, recorded.size(), "the call is on the job's record");
            assertInstanceOf(DecisionResponse.class, recorded.get(0));
            assertTrue(recorded.get(0).isSuccessful());
            assertEquals(0, recorded.get(0).getLastOutputTokens(), "a decision generates nothing");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void aFailedCallLandsOnTheJobsRecordWithItsReason() throws Exception {
        FakeDecisionClient.policy = request -> {
            throw new IllegalStateException("the fake endpoint is down");
        };
        Identifiable workflow = Job.workflow("test-user", "decision-call-failed");
        List<LLMResponse<?>> recorded = new CopyOnWriteArrayList<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<JobFailedEvent>() {
            @Override
            public Predicate<JobFailedEvent> getPredicate() {
                return event -> workflow.getWorkflowId().equals(event.snapshot().getWorkflowId());
            }

            @Override
            public void observe(JobFailedEvent event) {
                recorded.addAll(event.getLlmResponses());
            }
        }, JobFailedEvent.class);
        try {
            DecisionCall call = new DecisionCall(workflow, request());
            call.setUpstreamRetries(0);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> JobDispatcher.getInstance().submit(call).get(30, TimeUnit.SECONDS));
            assertTrue(chain(failure).contains("the fake endpoint is down"), "the reason surfaces: " + chain(failure));
            waitFor(() -> !recorded.isEmpty());
            assertEquals(1, recorded.size(), "the failed call is on the job's record too");
            assertFalse(recorded.get(0).isSuccessful());
            assertTrue(recorded.get(0).getReasonForFailure().contains("the fake endpoint is down"), recorded.get(0).getReasonForFailure());
        }
        finally {
            subscription.unsubscribe();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
