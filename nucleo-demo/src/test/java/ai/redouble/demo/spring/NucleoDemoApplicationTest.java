/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.spring;

import ai.redouble.demo.*;
import ai.redouble.demo.decide.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The process starts with no credential in the environment: the dispatcher runs, the three
 * provider artifacts are discovered with their catalog fragments, and a question is refused
 * with the runtime's own message rather than an unexplained failure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
@SpringBootTest
@AutoConfigureMockMvc
class NucleoDemoApplicationTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void thePageIsTheFrontDoor() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void statusReportsTheRuntimeEveryProviderAndTheCatalogFileItReads() throws Exception {
        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatcherRunning").value(true))
                .andExpect(jsonPath("$.providers[*].key", hasItems("anthropic-direct", "openai", "anthropic-bedrock")))
                .andExpect(jsonPath("$.providers[*].credential", everyItem(not(emptyString()))))
                // surefire names the test catalog with -Dnucleo.models, so the tests never read a models.json a discovery left here
                .andExpect(jsonPath("$.catalog.found").value(true))
                .andExpect(jsonPath("$.catalog.source", endsWith("src/test/resources/test-models.json")))
                .andExpect(jsonPath("$.catalog.orders.SMALL[0]", not(emptyString())))
                // per grade, the runtime's own answer for a text request: the entry serving it, or its refusal
                .andExpect(jsonPath("$.serving.SMALL").exists())
                .andExpect(jsonPath("$.servingRefusals.SMALL").exists())
                .andExpect(jsonPath("$.catalog.instructions", empty()))
                .andExpect(jsonPath("$.entries", not(empty())))
                // the shipped corpus rides the engine jar on the classpath; status reports the
                // absolute path DemoCorpus resolved it to, extracted here since the spring
                // module carries no corpus source of its own. Its 30-file content is pinned by
                // ExtractorTest; here it is enough that a real path was found
                .andExpect(jsonPath("$.corpus", not(emptyOrNullString())))
                // the day the corpus's price story is answered for, which the pricing steps open on
                .andExpect(jsonPath("$.corpusAsOf").value(DemoCorpus.AS_OF));
    }

    @Test
    void theDecisionAgentReportsItsPalette() throws Exception {
        mvc.perform(get("/decide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objective").value(PriceChangeFinder.OBJECTIVE))
                .andExpect(jsonPath("$.takes").value("folder"))
                .andExpect(jsonPath("$.answers").value("statement"))
                .andExpect(jsonPath("$.tools[0].name").value("list_folder"))
                .andExpect(jsonPath("$.tools[2].name").value("split_statements"));
    }

    @Test
    void aQuestionWithoutAnyCredentialIsRefusedWithTheRuntimesOwnMessage() throws Exception {
        mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is two plus two?\",\"context\":\"arithmetic\",\"grade\":\"SMALL\"}"))
                // MockMvc carries the refusal as the status reason; the running server renders
                // it into the error body (server.error.include-message: always)
                // the demo's catalog pins SMALL to a Bedrock entry, so with no credential the
                // refusal is AWS's own, naming the variable to set
                .andExpect(status().isInternalServerError())
                .andExpect(status().reason(containsString("AWS_REGION")));
    }
}
