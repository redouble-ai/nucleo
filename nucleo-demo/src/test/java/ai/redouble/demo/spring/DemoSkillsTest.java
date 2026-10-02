/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.spring;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Skills reach the demo two ways and both are visible from outside: the demo's own bundle
 * under {@code META-INF/skills/} in its resources, and a skilljar on the classpath (the
 * project's public one), found by the same loader. The agent's palette offers
 * {@code request_skill} with exactly those skills as its enumeration, beside
 * {@code request_tools} for its tool catalog. No credential is in the environment, so a run
 * streams to its failure, the runtime's own message as the last line.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoSkillsTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void theInventoryListsTheDemosOwnBundleAndTheSkilljarOnTheClasspath() throws Exception {
        mvc.perform(get("/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("concise-answers", "show-your-work")))
                .andExpect(jsonPath("$[?(@.name=='concise-answers')].bundleId", contains("ai.redouble.demo.concise-answers")))
                .andExpect(jsonPath("$[?(@.name=='concise-answers' || @.name=='show-your-work')].origin", contains("skillsjars", "skillsjars")))
                .andExpect(jsonPath("$[?(@.name=='show-your-work')].suggestedTools[*]", hasItems("get_current_time", "calculate_dates")))
                .andExpect(jsonPath("$[*].name", hasItems("delegation", "capability-tree")))
                .andExpect(jsonPath("$[?(@.name=='delegation')].bundleId", contains("ai.redouble.skills.delegation")))
                .andExpect(jsonPath("$[*].description", everyItem(not(emptyString()))));
    }

    @Test
    void theAgentOffersRequestSkillWithTheWholeInventory_andRequestToolsForItsToolCatalog() throws Exception {
        mvc.perform(get("/agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools", hasItems("get_current_time", "calculate_dates",
                        RequestSkillProvider.NAME, RequestToolsProvider.NAME)))
                .andExpect(jsonPath("$.skills", hasItems("concise-answers", "show-your-work", "delegation", "capability-tree")));
    }

    @Test
    void theRequestSkillSchemaTheModelSeesEnumeratesTheCatalog() throws Exception {
        DemoAgent agent = new DemoAgent(Job.workflow("test", "schema"));
        agent.palette();
        ToolProvider provider = agent.offered(RequestSkillProvider.NAME);
        assertNotNull(provider, "request_skill is on the palette");
        JsonNode oneOf = NucleoJsonSerializer.readTree(provider.schemaJson())
                .path("properties").path(RequestSkillProvider.SKILL_NAMES).path("items").path("oneOf");
        Set<String> offered = new HashSet<>();
        for (JsonNode entry : oneOf) {
            offered.add(entry.path("const").asText());
            assertFalse(entry.path("description").asText().isEmpty(), "each entry carries the skill's own description");
        }
        assertEquals(new HashSet<>(agent.skillCatalog()), offered,
                "what the schema enumerates is the catalog, no more and no less");
    }

    /**
     * The run streams, so the refusal is the stream's last line: the agent's own failed
     * event carries the picker's message naming what to provide, and the trace ends the
     * stream with it as {@code workflow_failed}.
     */
    @Test
    void aRunWithoutAnyCredentialIsRefusedWithTheRuntimesOwnMessage() throws Exception {
        MvcResult started = mvc.perform(post("/agent").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"How many days until the end of the year? Briefly.\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // the stream has no timeout of its own: wait here for the run's refusal to end it
        started.getAsyncResult(30_000);
        String lines = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String last = lines.strip().substring(lines.strip().lastIndexOf('\n') + 1);
        assertTrue(last.contains("\"workflow_failed\""), "the stream ends with the refusal: " + last);
        assertTrue(last.contains("AWS_REGION"), "the refusal names what to provide: " + last);
    }
}
