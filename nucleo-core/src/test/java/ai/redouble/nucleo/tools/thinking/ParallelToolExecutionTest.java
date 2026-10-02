/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A turn's tools run alongside each other: every call is submitted before any is awaited.
 * Two tools that each wait for the other at a barrier both return, which they cannot when
 * the turn runs them one after another. The exception is a tool that acts on the turn's own
 * conversation, which has completed before the next call is submitted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class ParallelToolExecutionTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "parallel-tool-execution-test");
    /** Both barrier tools of a turn meet here; a turn that runs them in sequence times the first one out. */
    static final CyclicBarrier MEETING = new CyclicBarrier(2);
    static volatile long awareDoneAt;
    static volatile long normalStartedAt;

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void twoToolsOfOneTurnRunAlongsideEachOther() throws Exception {
        MEETING.reset();
        Map<String, ToolResultBlock> results = run(call("barrier_tool", "id_a"), call("barrier_tool", "id_b"));
        assertEquals(2, results.size());
        assertFalse(results.get("id_a").isError(), "the first tool met the second at the barrier: " + results.get("id_a").resultJson());
        assertFalse(results.get("id_b").isError(), "the second tool met the first at the barrier: " + results.get("id_b").resultJson());
    }

    @Test
    void aToolThatWritesTheTurnsConversationCompletesBeforeTheNextIsSubmitted() throws Exception {
        awareDoneAt = 0;
        normalStartedAt = 0;
        Map<String, ToolResultBlock> results = run(call("aware_tool", "id_aware"), call("started_at_tool", "id_after"));
        assertEquals(2, results.size());
        assertFalse(results.get("id_aware").isError());
        assertFalse(results.get("id_after").isError());
        assertTrue(awareDoneAt > 0 && normalStartedAt > 0, "both tools ran");
        assertTrue(normalStartedAt >= awareDoneAt, "the conversation-aware tool had completed before the next call started");
    }

    private static Map<String, ToolResultBlock> run(ToolCall... calls) throws Exception {
        TestThinker thinker = new TestThinker(TEST_ROOT);
        ExecuteToolsHarness harness = new ExecuteToolsHarness(thinker, Arrays.asList(calls));
        Message turn = JobDispatcher.getInstance().submit(harness).get();
        Map<String, ToolResultBlock> map = new LinkedHashMap<>();
        for (ContentBlock block : ((OutgoingMessage<?>) turn).getContentBlocks()) {
            if (block instanceof ToolResultBlock tr) {
                map.put(tr.toolUseId(), tr);
            }
        }
        return map;
    }

    private static ToolCall call(String toolName, String toolUseId) {
        ToolCall c = new ToolCall();
        c.setToolName(toolName);
        c.setToolUseId(toolUseId);
        c.setInput(new StubInput());
        return c;
    }

    /** Runs executeTools inside a real job so it gets a live JobContext, then returns the batched results turn. */
    private static class ExecuteToolsHarness extends AbstractDoer<Void, Message> {
        private final TestThinker thinker;
        private final List<ToolCall> toolCalls;

        ExecuteToolsHarness(TestThinker thinker, List<ToolCall> toolCalls) {
            super(TEST_ROOT, "parallel-tools-harness");
            this.thinker = thinker;
            this.toolCalls = toolCalls;
        }

        @Override
        public Message execute(JobContext<Message> context) throws LLMReadableCheckedException {
            try {
                ConversationContext conversation = TestModels.conversation(TestModels.small());
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
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(BarrierTool.class, AwareTool.class, StartedAtTool.class);
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

    public static class StubInput {
        public String value;
    }

    public static class StubOutput {
        public String result = "ok";
    }

    private static JobRequirements plain() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        return req;
    }

    @ToolName("barrier_tool")
    @ToolDescription("Test tool that waits for its sibling at a barrier")
    public static class BarrierTool extends AbstractTool<StubInput, StubOutput> {
        public BarrierTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            return plain();
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) throws LLMReadableCheckedException {
            try {
                MEETING.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new SystemException("barrier_tool", "the sibling never arrived at the barrier: " + e, e);
            }
            return new StubOutput();
        }
    }

    @ToolName("aware_tool")
    @ToolDescription("Test tool that acts on the turn's conversation")
    public static class AwareTool extends AbstractTool<StubInput, StubOutput> implements ConversationAware {
        private ConversationContext conversation;

        public AwareTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public void setConversation(ConversationContext conversation) {
            this.conversation = conversation;
        }

        @Override
        public ConversationContext getConversation() {
            return conversation;
        }

        @Override
        public JobRequirements getRequirements() {
            return plain();
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) throws LLMReadableCheckedException {
            assertNotNull(conversation, "the thinker hands its conversation over before the tool runs");
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException e) {
                throw new SystemException("aware_tool", "interrupted", e);
            }
            awareDoneAt = System.nanoTime();
            return new StubOutput();
        }
    }

    @ToolName("started_at_tool")
    @ToolDescription("Test tool that records when it started")
    public static class StartedAtTool extends AbstractTool<StubInput, StubOutput> {
        public StartedAtTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            return plain();
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) {
            normalStartedAt = System.nanoTime();
            return new StubOutput();
        }
    }
}
