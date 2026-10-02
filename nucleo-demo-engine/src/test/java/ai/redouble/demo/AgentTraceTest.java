/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.thinking.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lines an agent run writes for the page: every job under the run named with its id,
 * parent, type, tool name and turn; a model turn's completion carrying what the model
 * decided; a tool's first line carrying the arguments the turn decided on, paired by turn
 * and tool name; its completion carrying the result; the notes and the streamed answer;
 * nothing for a job outside the run; and the answer as the last line. The trace serializes
 * with a camelCase mapper, the host's, so the answer's fields are named as the page reads them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class AgentTraceTest {
    private static final String WORKFLOW = "wf-agent";
    private static final String AGENT = "job-agent";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> lines = new ArrayList<>();
    private final AgentTrace trace = new AgentTrace(this::write, WORKFLOW, AGENT, this::read, () -> { });

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void read(String line) {
        try {
            lines.add(json.readTree(line));
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void theRunsJobsAreWrittenWithTheirLineageDecisionsArgumentsAndResults() throws Exception {
        trace.observe(new JobScheduled(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null)));
        assertEquals("queued", lines.get(0).get("kind").asText());
        assertTrue(lines.get(0).get("job").get("own").asBoolean(), "the agent's own job is marked as such");
        assertEquals("THINKER", lines.get(0).get("job").get("type").asText());

        JobSnapshot turn = snapshot("job-llm-1", AGENT, LLMCall.class, JobType.LLM_CALL, "Thinking", 1L);
        trace.observe(new JobScheduled(turn));
        assertEquals(1, lines.get(1).get("job").get("iteration").asLong(), "the turn a job belongs to, as the orchestrator stamped it");
        assertEquals(AGENT, lines.get(1).get("job").get("parent").asText());

        ToolCall call = new ToolCall();
        call.setToolName("get_current_time");
        call.setToolUseId("tu-1");
        call.setInput(Map.of("zone", "UTC"));
        ThinkingResponse<DemoAnswer> decided = new ThinkingResponse<>();
        decided.setToolCalls(List.of(call));
        SimpleReasoning reasoning = new SimpleReasoning();
        reasoning.setThought("look up the date first");
        decided.setReasoning(reasoning);
        trace.observe(new JobCompletedEvent<>(turn, decided, 1, null, null));
        JsonNode completed = lines.get(2);
        assertEquals("completed", completed.get("kind").asText());
        assertFalse(completed.get("turn").get("finalAnswer").asBoolean());
        assertEquals("get_current_time", completed.get("turn").get("toolCalls").get(0).get("name").asText());
        assertEquals("UTC", completed.get("turn").get("toolCalls").get(0).get("input").get("zone").asText());
        assertTrue(completed.get("turn").get("reasoning").asText().contains("look up the date first"), completed.get("turn").get("reasoning").asText());
        assertNotNull(completed.get("measured"), "the run's totals ride on every terminal line");

        JobSnapshot tool = snapshot("job-tool-1", AGENT, CurrentTimeTool.class, JobType.TOOL, "Get Current Time", 1L);
        trace.observe(new JobScheduled(tool));
        JsonNode queued = lines.get(3);
        assertEquals("get_current_time", queued.get("job").get("tool").asText(), "the name the model calls the tool by, from its class");
        assertEquals("UTC", queued.get("input").get("zone").asText(), "the arguments the turn decided on, paired by turn and tool name");
        trace.observe(new JobCompletedEvent<>(tool, Map.of("humanReadable", "September 24, 2026"), 1, null, null));
        assertEquals("September 24, 2026", lines.get(4).get("result").get("humanReadable").asText(), "the result as the tool returned it");

        trace.observe(new UserNotificationEvent(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null),
                "Reasoning", "I have the date", UserNotificationEvent.Severity.INFO, null, null));
        assertEquals("note", lines.get(5).get("kind").asText());
        assertEquals("Reasoning", lines.get(5).get("title").asText());
        assertEquals("INFO", lines.get(5).get("severity").asText());

        trace.observe(new ContentStreamEvent(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null), StreamChunk.done("{\"answer\":\"98 days.\"}")));
        assertEquals("answer", lines.get(6).get("kind").asText());
        assertEquals("{\"answer\":\"98 days.\"}", lines.get(6).get("content").asText());

        trace.observe(new JobScheduled(snapshot("job-elsewhere", "job-other-root", CurrentTimeTool.class, JobType.TOOL, "Get Current Time", 1L)));
        assertEquals(7, lines.size(), "a job of the workflow that is not under the run writes nothing");

        DemoAnswer answer = new DemoAnswer();
        answer.setAnswer("98 days.");
        answer.setSkillsUsed(List.of("concise-answers"));
        trace.observe(new JobCompletedEvent<>(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null), answer, 1, null, null));
        JsonNode agentDone = lines.get(7);
        assertEquals("completed", agentDone.get("kind").asText(), "the agent's own completion is a line of its own");
        assertTrue(agentDone.get("job").get("own").asBoolean());
        JsonNode last = lines.get(8);
        assertEquals("workflow_complete", last.get("type").asText(), "and the stream ends right after it, from the bus, so nothing before it is lost");
        assertEquals("98 days.", last.get("answer").get("answer").asText());
        assertEquals("concise-answers", last.get("answer").get("skillsUsed").get(0).asText());
        assertEquals(9, lines.size());
        assertTrue(trace.awaitEnd(Duration.ZERO), "the stream has ended");
        trace.fail(new IllegalStateException("late"));
        assertEquals(9, lines.size(), "a holder of the handle cannot end a stream twice");
    }

    @Test
    void theRunsOwnFailureEndsTheStreamWithTheFailure() throws Exception {
        trace.observe(new JobScheduled(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null)));
        trace.observe(new JobFailedEvent(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null),
                new IllegalStateException("no model serves MEGA"), 1, null, null));
        assertEquals("failed", lines.get(1).get("kind").asText());
        assertEquals("workflow_failed", lines.get(2).get("type").asText());
        assertEquals("no model serves MEGA", lines.get(2).get("message").asText());
        assertTrue(trace.awaitEnd(Duration.ZERO));
    }

    @Test
    void onlyTheRunsWorkflowPassesThePredicate() {
        assertTrue(trace.getPredicate().test(new JobScheduled(snapshot(AGENT, null, DemoAgent.class, JobType.THINKER, "DemoAgent", null))));
        JobSnapshot other = new JobSnapshot("job-x", null, "wf-other", "u", DemoAgent.class, JobType.THINKER, "DemoAgent", null, null, null, 1,
                Instant.now(), null, null, List.of(), Map.of());
        assertFalse(trace.getPredicate().test(new JobScheduled(other)));
    }

    @Test
    void aRunThatSettledWithoutATerminalEventIsEndedByTheHandlesHolder() throws Exception {
        assertFalse(trace.awaitEnd(Duration.ZERO), "nothing has ended the stream");
        trace.fail(new IllegalStateException("no model serves MEGA"));
        assertEquals("workflow_failed", lines.get(0).get("type").asText());
        assertEquals("no model serves MEGA", lines.get(0).get("message").asText());
        assertTrue(trace.awaitEnd(Duration.ZERO));
    }

    private static JobSnapshot snapshot(String id, String parent, Class<? extends Job> jobClass, JobType type, String name, Long iteration) {
        Map<String, Object> metadata = iteration != null ? Map.of(AbstractOrchestrator.META_ITERATION, iteration) : Map.of();
        return new JobSnapshot(id, parent, WORKFLOW, "u", jobClass, type, name, null, null, null, 1, Instant.now(), null, null, List.of(), metadata);
    }
}
