/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link AbstractThinker#executeTools} end to end through the in-process
 * {@link JobDispatcher} and asserts the tool_use/tool_result pairing invariant: a turn with
 * N tool_use blocks always produces a results turn with N tool_result blocks (1:1 by id),
 * no matter how the tools fail. This is the executeTools half of the fix; the parse half is
 * covered by {@link ThinkingResponseHandlerToolUseTest}.
 *
 * <p>Which scenarios fail without the fix: the serialization-throw and multi-tool
 * loop-continuation cases (a RuntimeException from result serialization escaped the old
 * {@code catch(ExecutionException)} and aborted the loop, so the results turn was never
 * appended). The unregistered-tool case fails without the parse-side fix. The
 * ExecutionException/RuntimeException/CancellationException single-tool cases arrive through
 * the handle in phase 2, where the same one error result answers them - they are regression
 * guards here, and the failed tool's notification is published from either phase
 * ({@link ParallelToolExecutionTest} pins that the phases run the tools alongside each other).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-15)
 */
public class ToolResultReconciliationTest {

    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "tool-result-reconciliation-test");

    @BeforeAll
    static void boot() {
        // Model resolution goes through the suite's TestModelPicker (TestConfigurator)
        JobDispatcher.getInstance().start();
    }

    // ======================== Scenarios ========================

    @Test
    void unregisteredToolIsAnsweredWithUnknownToolError() throws Exception {
        Map<String, ToolResultBlock> results = run(
                call("normal_tool", "id_normal"),
                call("ghost_tool", "id_ghost"));

        assertEquals(2, results.size(), "both tool_use ids must be answered");
        assertFalse(results.get("id_normal").isError(), "the registered tool succeeds");
        ToolResultBlock ghost = results.get("id_ghost");
        assertTrue(ghost.isError(), "the unregistered tool is answered with an error result");
        assertTrue(ghost.resultJson().contains("Unknown tool"),
                "the model is told the tool is unknown so it can recover: " + ghost.resultJson());
    }

    @Test
    void serializationFailureBecomesErrorResultAndLoopContinues() throws Exception {
        // The serialization-failing tool is FIRST: pre-fix its RuntimeException aborted the loop
        // and the two following tools were never answered. All three ids must now be present.
        Map<String, ToolResultBlock> results = run(
                call("exploding_tool", "id_boom"),
                call("normal_tool", "id_a"),
                call("normal_tool", "id_b"));

        assertEquals(Set.of("id_boom", "id_a", "id_b"), results.keySet(),
                "a serialization failure on the first tool must not orphan the rest of the turn");
        assertTrue(results.get("id_boom").isError(), "the serialization failure is an error result");
        assertFalse(results.get("id_a").isError());
        assertFalse(results.get("id_b").isError());
    }

    @Test
    void llmReadableToolFailureIsAnswered() throws Exception {
        Map<String, ToolResultBlock> results = run(call("llm_fail_tool", "id_llm"));
        assertEquals(1, results.size());
        assertTrue(results.get("id_llm").isError());
    }

    @Test
    void runtimeToolFailureIsAnswered() throws Exception {
        Map<String, ToolResultBlock> results = run(call("runtime_fail_tool", "id_rt"));
        assertEquals(1, results.size());
        assertTrue(results.get("id_rt").isError());
    }

    @Test
    void cancellationToolFailureIsAnswered() throws Exception {
        Map<String, ToolResultBlock> results = run(call("cancel_fail_tool", "id_cancel"));
        assertEquals(1, results.size());
        assertTrue(results.get("id_cancel").isError());
    }

    @Test
    void aFailedToolNotifiesAWarningWhenCorrectableAndAnErrorOtherwise() throws Exception {
        // what each failed tool's notification said, read off the bus the way a page reads it
        Map<String, UserNotificationEvent.Severity> severities = new ConcurrentHashMap<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<UserNotificationEvent>() {
            @Override
            public java.util.function.Predicate<UserNotificationEvent> getPredicate() {
                return event -> "Tool Failed".equals(event.title());
            }

            @Override
            public void observe(UserNotificationEvent event) {
                for (String tool : List.of("llm_fail_tool", "runtime_fail_tool")) {
                    if (event.message().contains(tool)) {
                        severities.put(tool, event.getSeverity());
                    }
                }
            }
        }, UserNotificationEvent.class);
        try {
            run(call("llm_fail_tool", "id_llm"), call("runtime_fail_tool", "id_rt"));
            // the bus delivers on its own threads
            for (int waited = 0; severities.size() < 2 && waited < 100; waited++) {
                Thread.sleep(50);
            }
            assertEquals(UserNotificationEvent.Severity.WARNING, severities.get("llm_fail_tool"),
                    "a correctable failure is the model's input to fix on its next turn: a warning");
            assertEquals(UserNotificationEvent.Severity.ERROR, severities.get("runtime_fail_tool"),
                    "a raw failure is a failure of the tool itself: an error");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void nullToolUseIdIsSkippedWithoutStrayTextAndOthersAnswered() throws Exception {
        Message turn = runTurn(
                call("normal_tool", "id_present"),
                call("normal_tool", null));

        Map<String, ToolResultBlock> results = resultsById(turn);
        assertEquals(1, results.size(), "the null-id call produces no result, the real id is still answered");
        assertTrue(results.containsKey("id_present"));
        assertFalse(hasStrayText(turn), "a null id must not inject a stray text block into a tool_result turn");
    }

    // ======================== Harness ========================

    private Map<String, ToolResultBlock> run(ToolCall... calls) throws Exception {
        return resultsById(runTurn(calls));
    }

    private Message runTurn(ToolCall... calls) throws Exception {
        TestThinker thinker = new TestThinker(TEST_ROOT);
        ExecuteToolsHarness harness = new ExecuteToolsHarness(thinker, Arrays.asList(calls));
        return JobDispatcher.getInstance().submit(harness).get();
    }

    private static ToolCall call(String toolName, String toolUseId) {
        ToolCall c = new ToolCall();
        c.setToolName(toolName);
        c.setToolUseId(toolUseId);
        // Registered tools need a typed input instance to pass submitToolCall's input-type check;
        // an unregistered tool is rejected by createTool before its input is read, so any value works.
        c.setInput(new StubInput());
        return c;
    }

    private static Map<String, ToolResultBlock> resultsById(Message message) {
        Map<String, ToolResultBlock> map = new LinkedHashMap<>();
        for (ContentBlock block : ((OutgoingMessage<?>)message).getContentBlocks()) {
            if (block instanceof ToolResultBlock tr) {
                map.put(tr.toolUseId(), tr);
            }
        }
        return map;
    }

    private static boolean hasStrayText(Message message) {
        for (ContentBlock block : ((OutgoingMessage<?>)message).getContentBlocks()) {
            if (block instanceof TextBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs executeTools inside a real job so it gets a live JobContext, then returns the
     * batched results turn (the last message executeTools appends) for inspection. A doer,
     * because it does what a doer does: submits tool jobs and waits - the submission door
     * refuses that from anything that is not an orchestrator.
     */
    private static class ExecuteToolsHarness extends AbstractDoer<Void, Message> {
        private final TestThinker thinker;
        private final List<ToolCall> toolCalls;

        ExecuteToolsHarness(TestThinker thinker, List<ToolCall> toolCalls) {
            super(TEST_ROOT, "execute-tools-harness");
            this.thinker = thinker;
            this.toolCalls = toolCalls;
        }

        @Override
        public Message execute(JobContext<Message> context) throws LLMReadableCheckedException {
            try {
                ConversationContext conversation = TestModels.conversation(TestModels.small());
                // Declare the palette up front, as a thinker does at conversation creation;
                // declarations never append messages, so the last message stays the results
                // turn under inspection
                conversation.addTools(thinker.buildToolDefinitionBlocks());
                thinker.executeTools(toolCalls, conversation, JobDispatcher.getInstance(), "test-user", context, 0L);
                List<Message> messages = conversation.getMessages();
                return messages.get(messages.size() - 1);
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    private static class TestThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
        TestThinker(Identifiable parent) {
            // Scripted: no model is ever called, but every thinker declares
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(NormalTool.class, ExplodingTool.class, LlmReadableFailTool.class,
                    RuntimeFailTool.class, CancelFailTool.class);
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            // Not used - the harness calls executeTools directly.
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }

    // ======================== Stub tools ========================

    public static class StubInput {
        public String value;
    }

    public static class StubOutput {
        public String result = "ok";
    }

    /** A getter that throws, simulating an artifact-with-no-ref serialization failure. */
    public static class ExplodingOutput {
        public String getBoom() {
            throw new IllegalStateException("serialization boom (simulates artifact with no ref assigned)");
        }
    }

    @ToolName("normal_tool")
    @ToolDescription("Test tool that returns a serializable output")
    public static class NormalTool extends AbstractTool<StubInput, StubOutput> {
        public NormalTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) {
            return new StubOutput();
        }
    }

    @ToolName("exploding_tool")
    @ToolDescription("Test tool whose output throws during serialization")
    public static class ExplodingTool extends AbstractTool<StubInput, ExplodingOutput> {
        public ExplodingTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public ExplodingOutput execute(JobResources resources, JobContext<ExplodingOutput> context) {
            return new ExplodingOutput();
        }
    }

    @ToolName("llm_fail_tool")
    @ToolDescription("Test tool that throws an LLM-readable correctable error")
    public static class LlmReadableFailTool extends AbstractTool<StubInput, StubOutput> {
        public LlmReadableFailTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) throws LLMReadableCheckedException {
            throw new InvalidInputException("value", "bad", "deliberate test failure");
        }
    }

    @ToolName("runtime_fail_tool")
    @ToolDescription("Test tool that throws a raw RuntimeException")
    public static class RuntimeFailTool extends AbstractTool<StubInput, StubOutput> {
        public RuntimeFailTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) {
            throw new RuntimeException("deliberate raw runtime failure");
        }
    }

    @ToolName("cancel_fail_tool")
    @ToolDescription("Test tool that throws a CancellationException")
    public static class CancelFailTool extends AbstractTool<StubInput, StubOutput> {
        public CancelFailTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) {
            throw new CancellationException("deliberate cancellation");
        }
    }
}
