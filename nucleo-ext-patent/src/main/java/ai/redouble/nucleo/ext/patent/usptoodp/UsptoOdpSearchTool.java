/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.ext.patent.ratelimiters.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;

/**
 * Searches US patents via the USPTO Open Data Portal (ODP) API.
 * Uses the opensearch-style POST DSL on the /patent/applications/search endpoint.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp_search")
@DisplayName(value = "USPTO ODP Search", action = "Searching US Patents")
@ToolDescription(value = "Search US patents via USPTO Open Data Portal. Supports search by title, assignee, inventor, CPC class, and date range. Returns bibliographic data for matching patents.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class UsptoOdpSearchTool extends AbstractTool<UsptoOdpSearchInput, UsptoOdpSearchOutput> {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpSearchTool.class);
    private static final int DEFAULT_MAX_RESULTS = 25;
    private static final int MAX_ALLOWED_RESULTS = 100;
    private static final UsptoOdpRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
    private final UsptoOdpClient client;
    public UsptoOdpSearchTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(60));
        this.client = new UsptoOdpClient();
    }
    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.requireRateLimiter(RATE_LIMITER, null);
        return req;
    }
    @Override
    public UsptoOdpSearchOutput execute(JobResources resources, JobContext<UsptoOdpSearchOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        ObjectNode body = buildSearchBody();
        if (body == null) {
            throw new InvalidInputException("searchCriteria", null,
                "At least one search criterion is required: titleSearch, assignee, inventorLastName, cpcClassification, dateFrom/dateTo");
        }
        context.publish("Searching USPTO ODP", 30);
        client.setHttpClient(resources.getHttpClient());
        JsonNode response = client.searchPostJson("patent/applications/search", body);
        UsptoOdpSearchOutput output = new UsptoOdpSearchOutput();
        if (response == null) {
            return output;
        }
        JsonNode dataBag = response.path("patentFileWrapperDataBag");
        if (!dataBag.isArray() || dataBag.isEmpty()) {
            return output;
        }
        List<PatentArtifact> patents = new ArrayList<>();
        for (JsonNode item : dataBag) {
            PatentArtifact patent = parseMetaDataToArtifact(item);
            if (patent != null) {
                patents.add(patent);
            }
        }
        output.setPatents(patents);
        Integer count = response.has("count") ? response.path("count").asInt() : patents.size();
        output.setTotalCount(count);
        log.info("USPTO ODP search returned {} patents (total: {})", patents.size(), count);
        context.publish("Complete", 100);
        return output;
    }
    private ObjectNode buildSearchBody() {
        List<String> qParts = new ArrayList<>();
        if (input.getTitleSearch() != null && !input.getTitleSearch().trim().isEmpty()) {
            qParts.add("applicationMetaData.inventionTitle:" + input.getTitleSearch().trim());
        }
        if (input.getAssignee() != null && !input.getAssignee().trim().isEmpty()) {
            qParts.add("applicationMetaData.applicantBag.applicantNameText:" + input.getAssignee().trim());
        }
        if (input.getInventorLastName() != null && !input.getInventorLastName().trim().isEmpty()) {
            qParts.add("applicationMetaData.inventorBag.lastName:" + input.getInventorLastName().trim());
        }
        if (input.getCpcClassification() != null && !input.getCpcClassification().trim().isEmpty()) {
            qParts.add("applicationMetaData.cpcClassificationBag:" + input.getCpcClassification().trim());
        }
        if (qParts.isEmpty() && input.getDateFrom() == null && input.getDateTo() == null) {
            return null;
        }
        ObjectNode body = NucleoJsonSerializer.createObjectNode();
        if (!qParts.isEmpty()) {
            body.put("q", String.join(" AND ", qParts));
        }

        // Filters
        ArrayNode filters = NucleoJsonSerializer.createArrayNode();
        if (input.getIncludeApplications() == null || !input.getIncludeApplications()) {
            ObjectNode grantedFilter = NucleoJsonSerializer.createObjectNode();
            grantedFilter.put("name", "applicationMetaData.publicationCategoryBag");
            ArrayNode grantedValues = NucleoJsonSerializer.createArrayNode();
            grantedValues.add("Granted/Issued");
            grantedFilter.set("value", grantedValues);
            filters.add(grantedFilter);
        }
        if (filters.size() > 0) {
            body.set("filters", filters);
        }

        // Range filters for dates
        if (input.getDateFrom() != null || input.getDateTo() != null) {
            ArrayNode rangeFilters = NucleoJsonSerializer.createArrayNode();
            ObjectNode dateRange = NucleoJsonSerializer.createObjectNode();
            dateRange.put("field", "applicationMetaData.filingDate");
            if (input.getDateFrom() != null) {
                dateRange.put("valueFrom", input.getDateFrom());
            }
            if (input.getDateTo() != null) {
                dateRange.put("valueTo", input.getDateTo());
            }
            rangeFilters.add(dateRange);
            body.set("rangeFilters", rangeFilters);
        }

        // Pagination
        int maxResults = input.getMaxResults() != null
                ? Math.min(input.getMaxResults(), MAX_ALLOWED_RESULTS)
                : DEFAULT_MAX_RESULTS;
        ObjectNode pagination = NucleoJsonSerializer.createObjectNode();
        pagination.put("offset", 0);
        pagination.put("limit", maxResults);
        body.set("pagination", pagination);

        // Sort by grant date descending
        ArrayNode sort = NucleoJsonSerializer.createArrayNode();
        ObjectNode sortField = NucleoJsonSerializer.createObjectNode();
        sortField.put("field", "applicationMetaData.grantDate");
        sortField.put("order", "Desc");
        sort.add(sortField);
        body.set("sort", sort);

        // Only fetch fields we need
        ArrayNode fields = NucleoJsonSerializer.createArrayNode();
        fields.add("applicationNumberText");
        fields.add("applicationMetaData");
        body.set("fields", fields);
        return body;
    }

    static PatentArtifact parseMetaDataToArtifact(JsonNode item) {
        JsonNode meta = item.path("applicationMetaData");
        if (meta.isMissingNode()) {
            return null;
        }
        PatentArtifact patent = new PatentArtifact();
        patent.setPatentNumber(meta.path("patentNumber").asText(null));
        patent.setTitle(meta.path("inventionTitle").asText(null));
        patent.setApplicationNumber(item.path("applicationNumberText").asText(null));
        patent.setFilingDate(meta.path("filingDate").asText(null));
        String grantDate = meta.path("grantDate").asText(null);
        String earliestPubDate = meta.path("earliestPublicationDate").asText(null);
        patent.setPublicationDate(grantDate != null ? grantDate : earliestPubDate);

        // Derive kind code from earliest publication number (e.g., "US20230366018A1" -> "A1")
        String earliestPubNum = meta.path("earliestPublicationNumber").asText(null);
        if (earliestPubNum != null) {
            String kindCode = extractKindCode(earliestPubNum);
            if (kindCode != null) {
                patent.setKindCode(kindCode);
            }
        }

        // CPC classifications
        JsonNode cpcBag = meta.path("cpcClassificationBag");
        if (cpcBag.isArray() && !cpcBag.isEmpty()) {
            List<String> cpcs = new ArrayList<>();
            for (JsonNode cpc : cpcBag) {
                cpcs.add(cpc.asText());
            }
            patent.setCpcClassifications(cpcs);
        }

        // Applicants
        JsonNode applicantBag = meta.path("applicantBag");
        if (applicantBag.isArray() && !applicantBag.isEmpty()) {
            List<String> applicants = new ArrayList<>();
            for (JsonNode a : applicantBag) {
                String name = a.path("applicantNameText").asText(null);
                if (name != null) {
                    applicants.add(name);
                }
            }
            if (!applicants.isEmpty()) {
                patent.setApplicants(applicants);
            }
        }

        // Inventors
        JsonNode inventorBag = meta.path("inventorBag");
        if (inventorBag.isArray() && !inventorBag.isEmpty()) {
            List<String> inventors = new ArrayList<>();
            for (JsonNode inv : inventorBag) {
                String name = inv.path("inventorNameText").asText(null);
                if (name != null) {
                    inventors.add(name);
                }
            }
            if (!inventors.isEmpty()) {
                patent.setInventors(inventors);
            }
        }
        patent.setSource("uspto-odp");
        patent.setJurisdiction("US");
        if (patent.getPatentNumber() != null) {
            patent.setUrl("https://patents.google.com/patent/US" + patent.getPatentNumber());
        }
        return patent;
    }
    private static String extractKindCode(String pubNumber) {
        if (pubNumber == null || pubNumber.length() < 3) {
            return null;
        }
        // Format: "US20230366018A1" or "US 2014-0167116 A1"
        String trimmed = pubNumber.replaceAll("[\\s-]", "");
        int i = trimmed.length() - 1;
        while (i >= 0 && Character.isLetter(trimmed.charAt(i))) {
            i--;
        }
        while (i >= 0 && Character.isDigit(trimmed.charAt(i))) {
            i--;
        }
        String suffix = trimmed.substring(i + 1);
        // Kind code is typically the trailing letter(s) + digit (e.g., "A1", "B2")
        int letterStart = -1;
        for (int j = suffix.length() - 1; j >= 0; j--) {
            if (Character.isLetter(suffix.charAt(j))) {
                letterStart = j;
            } else if (letterStart >= 0) {
                break;
            }
        }
        if (letterStart >= 0) {
            return suffix.substring(letterStart);
        }
        return null;
    }
}
