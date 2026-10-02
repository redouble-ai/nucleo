/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A job's handle completes only after the dispatcher has finished with the job: when
 * {@code handle.get()} returns or throws, the job is no longer running and the LLM calls it
 * recorded are released from its context, on success and on failure alike, every time - while its
 * terminal event carries those calls. Repeated because the defect this pins was a race: the handle
 * used to complete before the teardown, so what a woken caller saw depended on which thread won.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class HandleCompletesAfterTeardownTest {
    private static final int RUNS = 200;

    @BeforeAll
    static void start() {
        JobDispatcher.getInstance().start();
    }

    public static class Question {
        private boolean fail;

        public boolean isFail() {
            return fail;
        }

        public void setFail(boolean fail) {
            this.fail = fail;
        }
    }

    public static class Answer {
        private String model;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /** Records one LLM call on its context, then answers or fails as asked. */
    static final class RecordingTool extends AbstractModelDependentTool<Question, Answer> {
        private ModelBinding binding;

        RecordingTool(Identifiable parent) {
            super(parent, Grade.SMALL);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            req.setReadOnly(true);
            binding = wireConversation(req, Depth.IMMEDIATE, OutputDeclaration.of(OutputSize.VERDICT), Answer.class, "answer");
            return req;
        }

        @Override
        public Answer execute(JobResources resources, JobContext<Answer> context) throws LLMReadableCheckedException {
            LLMResponse<Answer> response = new LLMResponse<>(request());
            response.setModel(binding.getModel().getId());
            response.setStartTime(Instant.now());
            response.setEndTime(Instant.now());
            context.addLlmResponse(response);
            if (input.isFail()) {
                throw new InvalidInputException("fail", "true", "a question the tool can answer");
            }
            Answer answer = new Answer();
            answer.setModel(binding.getModel().getId());
            return answer;
        }
    }

    /** The calls the job's terminal event carried, captured by a subscription opened before submit. */
    private static CompletableFuture<List<LLMResponse<?>>> terminalCalls(String jobId) {
        CompletableFuture<List<LLMResponse<?>>> calls = new CompletableFuture<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<>() {
            @Override
            public java.util.function.Predicate<JobEvent> getPredicate() {
                return event -> event instanceof TerminalEvent && event.snapshot() != null && jobId.equals(event.snapshot().getJobId());
            }

            @Override
            public void observe(JobEvent event) {
                calls.complete(((TerminalEvent) event).getLlmResponses());
            }
        }, JobEvent.class);
        calls.whenComplete((c, e) -> subscription.unsubscribe());
        return calls;
    }

    private static RecordingTool tool(boolean fail) {
        RecordingTool tool = new RecordingTool(Job.workflow("test-user", "teardown"));
        Question question = new Question();
        question.setFail(fail);
        tool.setInput(question);
        return tool;
    }

    @Test
    void aSucceededJobIsTornDownWhenItsHandleReturns() throws Exception {
        for (int run = 0; run < RUNS; run++) {
            RecordingTool tool = tool(false);
            CompletableFuture<List<LLMResponse<?>>> terminal = terminalCalls(tool.getId());
            JobHandle<Answer> handle = JobDispatcher.getInstance().submit(tool);
            assertNotNull(handle.get().getModel());
            assertTrue(handle.getContext().getLlmResponses().isEmpty(),
                    "the recorded calls are released before the handle completes, run " + run);
            assertFalse(JobDispatcher.getInstance().cancel(tool.getId(), "probe"),
                    "the job is no longer running when its handle completes, run " + run);
            assertEquals(1, terminal.get(5, TimeUnit.SECONDS).size(), "the terminal event carries the call, run " + run);
        }
    }

    @Test
    void aFailedJobIsTornDownWhenItsHandleThrows() throws Exception {
        for (int run = 0; run < RUNS; run++) {
            RecordingTool tool = tool(true);
            CompletableFuture<List<LLMResponse<?>>> terminal = terminalCalls(tool.getId());
            JobHandle<Answer> handle = JobDispatcher.getInstance().submit(tool);
            assertThrows(ExecutionException.class, handle::get);
            assertTrue(handle.getContext().getLlmResponses().isEmpty(),
                    "the recorded calls are released before the handle fails, run " + run);
            assertFalse(JobDispatcher.getInstance().cancel(tool.getId(), "probe"),
                    "the job is no longer running when its handle fails, run " + run);
            assertFalse(terminal.get(5, TimeUnit.SECONDS).isEmpty(), "the terminal event carries the call, run " + run);
        }
    }
}
