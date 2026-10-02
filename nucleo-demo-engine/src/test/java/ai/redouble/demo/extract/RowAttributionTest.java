/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file's row names the model that read it and carries everything its calls cost: the
 * embeddings call that indexed the text adds to the row's tokens and cost and never names its
 * reader, so a file code read shows no model while a scan shows the vision model that read it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class RowAttributionTest {
    private static final String EMBEDDINGS = "embed-model";

    private static CostLedger.Call call(String jobId, String modelId, long inputTokens, double cost) {
        return call(jobId, modelId, inputTokens, cost, "USD");
    }

    private static CostLedger.Call call(String jobId, String modelId, long inputTokens, double cost, String currency) {
        return new CostLedger.Call(jobId, jobId, modelId, modelId + "-served", inputTokens, 0, 0, 0, 10, new Cost(cost, currency));
    }

    private static ExtractReport.FileOutcome row(String path, Tier tier) {
        ExtractReport.FileOutcome row = new ExtractReport.FileOutcome();
        row.setPath(path);
        row.setTier(tier);
        return row;
    }

    @Test
    void theEmbeddingAddsToTheRowsCostAndNeverNamesItsReader() {
        Map<String, ExtractReport.FileOutcome> rows = new LinkedHashMap<>();
        rows.put("notes.md", row("notes.md", Tier.DETERMINISTIC));
        rows.put("scan.pdf", row("scan.pdf", Tier.VISION));
        Map<String, String> jobsByPath = Map.of("embed-notes", "notes.md", "read-scan", "scan.pdf", "embed-scan", "scan.pdf");
        ExtractDirectoryDoer.attribute(List.of(
                call("embed-notes", EMBEDDINGS, 100, 0.00004),
                call("read-scan", "vision-model", 2000, 0.003),
                call("embed-scan", EMBEDDINGS, 50, 0.00002)), jobsByPath, rows, EMBEDDINGS);
        ExtractReport.FileOutcome code = rows.get("notes.md");
        assertNull(code.getModelId(), "code read the file: no model read it, whatever indexed its text");
        assertEquals(100L, code.getInputTokens(), "the indexing's tokens are the row's");
        assertEquals(0.00004, code.getCost(), 1e-12, "the indexing's cost is the row's");
        ExtractReport.FileOutcome scan = rows.get("scan.pdf");
        assertEquals("vision-model", scan.getModelId(), "the model that read the scan, not the one that indexed it");
        assertEquals("vision-model-served", scan.getServedModelId());
        assertEquals(2050L, scan.getInputTokens());
        assertEquals(0.00302, scan.getCost(), 1e-12);
    }

    @Test
    void aRowPricedInMoreThanOneCurrencyCarriesNoCostAndSaysSo() {
        Map<String, ExtractReport.FileOutcome> rows = new LinkedHashMap<>();
        rows.put("scan.pdf", row("scan.pdf", Tier.VISION));
        Map<String, String> jobsByPath = Map.of("read-scan", "scan.pdf", "embed-scan", "scan.pdf");
        ExtractDirectoryDoer.attribute(List.of(
                call("read-scan", "vision-model", 2000, 0.003, "USD"),
                call("embed-scan", EMBEDDINGS, 50, 0.00002, "EUR")), jobsByPath, rows, EMBEDDINGS);
        ExtractReport.FileOutcome scan = rows.get("scan.pdf");
        assertNull(scan.getCost(), "two currencies never sum: the row carries no cost");
        assertNotNull(scan.getNote());
        assertTrue(scan.getNote().contains("more than one currency"), "and the note says why: " + scan.getNote());
        assertEquals(2050L, scan.getInputTokens(), "the tokens still sum, they have one unit");
    }
}
