/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;

/**
 * Searches EPO Open Patent Services for patents using CQL queries.
 * Supports raw CQL or structured field-based queries (title, applicant, inventor, etc.).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@MCP
@ToolName("epo_search")
@DisplayName(value = "EPO Search", action = "Searching EPO")
@ToolDescription(value = "Search EPO Open Patent Services for patents by title, applicant, inventor, classification, date, or raw CQL query. Returns matching patent numbers.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPOSearchTool extends AbstractEPOTool<EPOSearchInput, EPOSearchOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOSearchTool.class);
    private static final int DEFAULT_MAX_RESULTS = 25;
    private static final int MAX_ALLOWED_RESULTS = 100;
    public EPOSearchTool(Identifiable parent) {
        super(parent, EPOService.SEARCH, Duration.ofSeconds(60));
    }
    @Override
    public EPOSearchOutput execute(JobResources resources, JobContext<EPOSearchOutput> context) throws LLMReadableCheckedException {
        context.publish("Building search query", 5);
        String cql = buildCqlQuery();
        if (cql == null || cql.trim().isEmpty()) {
            throw new InvalidInputException("searchCriteria", null, "At least one search field or a cqlQuery must be provided");
        }
        int maxResults = input.getMaxResults() != null ? Math.min(input.getMaxResults(), MAX_ALLOWED_RESULTS) : DEFAULT_MAX_RESULTS;
        context.publish("Searching EPO OPS", 30);
        connect(resources);
        String path = "/rest-services/published-data/search?q=" + AbstractApiClient.encode(cql) + "&Range=1-" + maxResults;
        JsonNode response = client.searchGet(path);
        EPOSearchOutput output = new EPOSearchOutput();
        if (response == null) {
            output.setPatentNumbers(new ArrayList<>());
            output.setTotalCount(0);
            return output;
        }

        // Parse patent numbers from search results
        List<String> patentNumbers = new ArrayList<>();
        JsonNode searchResult = response.path("world-patent-data").path("biblio-search").path("search-result");
        JsonNode totalCountNode = response.path("world-patent-data").path("biblio-search").path("range");

        // Extract total count from range attribute
        int totalCount = 0;
        if (totalCountNode.has("total-count")) {
            totalCount = totalCountNode.path("total-count").asInt(0);
        }

        // Navigate to exchange-documents - can be single object or array
        JsonNode exchangeDocs = searchResult.path("exchange-documents");
        if (exchangeDocs.isMissingNode()) {
            exchangeDocs = searchResult;
        }
        if (exchangeDocs.isArray()) {
            for (JsonNode doc : exchangeDocs) {
                String patNum = extractPatentNumber(doc);
                if (patNum != null) {
                    patentNumbers.add(patNum);
                }
            }
        }
        else if (!exchangeDocs.isMissingNode()) {
            // Single result returned as object
            String patNum = extractPatentNumber(exchangeDocs);
            if (patNum != null) {
                patentNumbers.add(patNum);
            }
        }
        output.setPatentNumbers(patentNumbers);
        output.setTotalCount(totalCount > 0 ? totalCount : patentNumbers.size());
        log.info("EPO search returned {} results (total: {})", patentNumbers.size(), output.getTotalCount());
        context.publish("Complete", 100);
        return output;
    }
    private String buildCqlQuery() {
        if (input.getCqlQuery() != null && !input.getCqlQuery().trim().isEmpty()) {
            return input.getCqlQuery();
        }
        List<String> parts = new ArrayList<>();
        if (input.getTitle() != null && !input.getTitle().isEmpty()) {
            parts.add("ti=\"" + input.getTitle() + "\"");
        }
        if (input.getTitleAbstract() != null && !input.getTitleAbstract().isEmpty()) {
            parts.add("ta=\"" + input.getTitleAbstract() + "\"");
        }
        if (input.getApplicant() != null && !input.getApplicant().isEmpty()) {
            parts.add("pa=\"" + input.getApplicant() + "\"");
        }
        if (input.getInventor() != null && !input.getInventor().isEmpty()) {
            parts.add("in=\"" + input.getInventor() + "\"");
        }
        if (input.getPatentNumber() != null && !input.getPatentNumber().isEmpty()) {
            parts.add("pn=\"" + input.getPatentNumber() + "\"");
        }
        if (input.getIpcClass() != null && !input.getIpcClass().isEmpty()) {
            parts.add("ic=\"" + input.getIpcClass() + "\"");
        }
        if (input.getCpcClass() != null && !input.getCpcClass().isEmpty()) {
            parts.add("cpc=\"" + input.getCpcClass() + "\"");
        }
        if (input.getDateFrom() != null && !input.getDateFrom().isEmpty()) {
            String dateCompact = input.getDateFrom().replace("-", "");
            parts.add("pd>=" + dateCompact);
        }
        if (input.getDateTo() != null && !input.getDateTo().isEmpty()) {
            String dateCompact = input.getDateTo().replace("-", "");
            parts.add("pd<=" + dateCompact);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" AND ", parts);
    }
    private String extractPatentNumber(JsonNode doc) {
        JsonNode exchangeDoc = doc.path("exchange-document");
        if (exchangeDoc.isMissingNode()) {
            exchangeDoc = doc;
        }
        return EPOClient.docdbNumber(exchangeDoc);
    }
}
