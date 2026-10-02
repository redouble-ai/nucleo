/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.spring;

import ai.redouble.demo.*;
import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.spring.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.core.env.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

import java.nio.file.*;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The extractor over the shipped corpus with no credential in the environment: the
 * deterministic tier reads everything code can read, the two lying extensions are caught by
 * their bytes, and every file that needs a model is a row saying why it has no text. Under a
 * spend cap too small for any call, on a deployment that can call a model, those rows are
 * refusals at admission instead, before any call is made, which is the budget doing what it says.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExtractorTest {
    /** The shipped corpus wherever this checkout carries it - resolved the way the page prefills it. */
    @Autowired
    private DemoCorpus corpus;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private FileIndex index;
    @Autowired
    private NucleoRuntime runtime;

    @Test
    void codeReadsEverythingItCanAndTheRestSaysWhyItWaits() throws Exception {
        index.clear();
        mvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                        // serialized, never concatenated: a Windows path's backslashes are invalid JSON escapes
                        .content(NucleoJsonSerializer.write(Map.of("directory", corpus.path()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesSeen").value(30))
                // read by code, with text
                .andExpect(jsonPath("$.files[?(@.path=='meeting-notes-2026-02.md')].tier").value("DETERMINISTIC"))
                .andExpect(jsonPath("$.files[?(@.path=='meeting-notes-2026-02.md')].chars").value(hasItem(greaterThan(1000))))
                .andExpect(jsonPath("$.files[?(@.path=='product-spec.docx')].chars").value(hasItem(greaterThan(300))))
                .andExpect(jsonPath("$.files[?(@.path=='price-list.xlsx')].chars").value(hasItem(greaterThan(100))))
                .andExpect(jsonPath("$.files[?(@.path=='dealer-pitch.pptx')].chars").value(hasItem(greaterThan(100))))
                .andExpect(jsonPath("$.files[?(@.path=='invoice-0417.pdf')].chars").value(hasItem(greaterThan(300))))
                // the lying extension is an image by its bytes, so it went to the vision tier, not the reader
                .andExpect(jsonPath("$.files[?(@.path=='notes.txt')].tier").value("FAILED"))
                .andExpect(jsonPath("$.files[?(@.path=='notes.txt')].note").value(hasItem(containsString("VISION"))))
                // the model tiers could not run without a credential and say so
                .andExpect(jsonPath("$.files[?(@.path=='scanned-letter.pdf')].tier").value("FAILED"))
                .andExpect(jsonPath("$.files[?(@.path=='scanned-letter.pdf')].note").value(hasItem(containsString("AWS_REGION"))))
                .andExpect(jsonPath("$.files[?(@.path=='telemetry.dat')].tier").value("FAILED"))
                .andExpect(jsonPath("$.files[?(@.path=='telemetry.dat')].note").value(hasItem(containsString("CLASSIFIER"))))
                // a mixed PDF keeps the text of its readable page while its scan waits for a model
                .andExpect(jsonPath("$.files[?(@.path=='mixed-report.pdf')].tier").value("FAILED"))
                .andExpect(jsonPath("$.files[?(@.path=='mixed-report.pdf')].note").value(hasItem(containsString("VISION"))))
                // nothing to index: no embeddings model could be called either
                .andExpect(jsonPath("$.indexed").value(0))
                .andExpect(jsonPath("$.llmCalls").value(0));
    }

    /**
     * A grade's order serves only what can be called, so the cap is shown on a deployment that
     * holds a credential: a Bedrock one, made up and never sent, because the cap refuses every
     * model call at admission, before the call is made. The credential is a property source of
     * this test alone, taken off again whatever the outcome, so no other test sees it.
     */
    @Test
    void aCapTooSmallForAnyCallRefusesEveryModelTierAtAdmission() throws Exception {
        index.clear();
        environment.getPropertySources().addFirst(new MapPropertySource(CAP_CREDENTIALS, Map.of(
                "nucleo.credentials.aws-access-key-id.user", "AKIACAPTEST",
                "nucleo.credentials.aws-access-key-id", "never-sent",
                "nucleo.credentials.aws-region", "us-east-1")));
        try {
            mvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                            .content(NucleoJsonSerializer.write(Map.of(
                                    "directory", corpus.path(),
                                    "budgets", List.of(Map.of("amount", 0.000001, "currency", "USD"))))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.caps.USD").value(0.000001))
                    .andExpect(jsonPath("$.files[?(@.path=='scanned-letter.pdf')].tier").value("REFUSED"))
                    .andExpect(jsonPath("$.files[?(@.path=='scanned-letter.pdf')].note").value(hasItem(containsString("cap"))))
                    .andExpect(jsonPath("$.files[?(@.path=='telemetry.dat')].tier").value("REFUSED"))
                    .andExpect(jsonPath("$.files[?(@.path=='whiteboard.jpg')].tier").value("REFUSED"))
                    // the free tier is not gated: code read what it could
                    .andExpect(jsonPath("$.files[?(@.path=='warranty-policy.txt')].tier").value("DETERMINISTIC"))
                    .andExpect(jsonPath("$.byTier.REFUSED").value(greaterThanOrEqualTo(10)))
                    .andExpect(jsonPath("$.byTier.DETERMINISTIC").value(greaterThanOrEqualTo(15)))
                    .andExpect(jsonPath("$.llmCalls").value(0));
        }
        finally {
            environment.getPropertySources().remove(CAP_CREDENTIALS);
        }
    }

    private static final String CAP_CREDENTIALS = "extractor-test-cap-credentials";
    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void searchBeforeAnyExtractionIsRefusedPlainly() throws Exception {
        index.clear();
        mvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"headset creak\"}"))
                .andExpect(status().isConflict())
                .andExpect(status().reason(containsString("POST /extract")));
    }

    @Test
    void pricingBeforeAnyExtractionIsRefusedPlainly() throws Exception {
        index.clear();
        mvc.perform(post("/pricing").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(status().reason(containsString("POST /extract")));
    }

    @Test
    void decidedPricingBeforeAnyExtractionIsRefusedPlainly() throws Exception {
        index.clear();
        mvc.perform(post("/decide-prices").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(status().reason(containsString("POST /extract")));
    }

    @Test
    void decidedPricingRunsTheSameDoerOnTheSameIndex() throws Exception {
        // the same run as /pricing with the grouping tier swapped: with no credential the reading
        // tier fails the one document the same way, before any grouping is put to a decision model
        index.clear();
        Embedding embedding = new Embedding();
        embedding.setKey("price-list.xlsx");
        embedding.setModelId("test-embeddings");
        embedding.setVector(new float[]{1f, 0f});
        index.put("price-list.xlsx", "SKU | Model | Retail price (EUR)\nHBW-K1-M | Kestrel 1 gravel | 2049", embedding);
        mvc.perform(post("/decide-prices").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsRead").value(0))
                .andExpect(jsonPath("$.products").isEmpty())
                .andExpect(jsonPath("$.documents[0].path").value("price-list.xlsx"))
                .andExpect(jsonPath("$.documents[0].status").value("FAILED"))
                .andExpect(jsonPath("$.documents[0].note").value(containsString("AWS_REGION")))
                .andExpect(jsonPath("$.spend.llmCalls").value(0));
    }

    @Test
    void pricingReadsWhatTheExtractorIndexedAndSaysWhichDocumentsNoModelCouldRead() throws Exception {
        index.clear();
        // no credential: nothing is embedded, so nothing is indexed and the pricing run has nothing to read;
        // an index entry put by hand stands in for an extraction that ran with one
        Embedding embedding = new Embedding();
        embedding.setKey("price-list.xlsx");
        embedding.setModelId("test-embeddings");
        embedding.setVector(new float[]{1f, 0f});
        index.put("price-list.xlsx", "SKU | Model | Retail price (EUR)\nHBW-K1-M | Kestrel 1 gravel | 2049", embedding);
        Path output = Files.createTempFile("pricing", ".json");
        mvc.perform(post("/pricing").contentType(MediaType.APPLICATION_JSON)
                        .content(NucleoJsonSerializer.write(Map.of("output", output.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsRead").value(0))
                .andExpect(jsonPath("$.mentionsFound").value(0))
                .andExpect(jsonPath("$.products").isEmpty())
                .andExpect(jsonPath("$.documents[0].path").value("price-list.xlsx"))
                .andExpect(jsonPath("$.documents[0].status").value("FAILED"))
                .andExpect(jsonPath("$.documents[0].note").value(containsString("AWS_REGION")))
                .andExpect(jsonPath("$.outputFile").value(output.toString()))
                .andExpect(jsonPath("$.spend.llmCalls").value(0));
        Assertions.assertTrue(Files.readString(output).contains("\"documentsRead\""), "the report was written where asked");
        Files.delete(output);
    }

    @Test
    void benchmarkWithoutAQueryOrARunCountIsRefusedPlainly() throws Exception {
        mvc.perform(post("/benchmark").contentType(MediaType.APPLICATION_JSON).content("{\"runs\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("query")));
        mvc.perform(post("/benchmark").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"What is Kestrel 1?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("runs")));
    }

    @Test
    void benchmarkWithNoCallableModelIsRefusedBeforeTheStreamOpens() throws Exception {
        // no credential: no entry of any grade in the test catalog is configured, so the race has no candidate
        mvc.perform(post("/benchmark").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"What is Kestrel 1?\",\"runs\":2}"))
                .andExpect(status().isInternalServerError())
                .andExpect(status().reason(containsString("at least one open entry whose provider is configured")));
    }

    @Test
    void theLedgerIsTheDispatchersGate() {
        // the runtime wires one ledger for both jobs: what the report reads is what the gate judged
        CostLedger ledger = runtime.ledger();
        Assertions.assertNotNull(ledger);
        Assertions.assertTrue(ledger.caps("nobody").isEmpty());
        Assertions.assertEquals(0.0, ledger.spent("nobody", "USD").amount());
        Assertions.assertEquals(List.of(), List.copyOf(ledger.caps("nobody").keySet()));
    }
}
