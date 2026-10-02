/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.tools;

import ai.redouble.nucleo.ext.lit.models.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;

import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * Searches bioRxiv and medRxiv for preprint articles via the official API.
 *
 * <p>The bioRxiv API exposes a date-window endpoint only; it has no
 * keyword-search endpoint. This tool fetches every preprint in the supplied
 * window and filters by keyword in memory. Callers must supply both a query
 * and a bounded date range - `@LLMRequired` makes both non-negotiable on the
 * tool's input schema. HTML scraping of the public web site is prohibited by
 * bioRxiv's text-and-data-mining policy and is not performed here.
 *
 * <p>API: {@code https://api.biorxiv.org/details/[server]/[dateFrom]/[dateTo]/[cursor]/json}
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-12)
 */
@DisplayName(value = "BioRxiv Search", action = "Searching BioRxiv Database")
@ToolName("search_biorxiv")
@ToolDescription(value = "Search bioRxiv and medRxiv preprints within a date window. Requires both a query and a dateFrom/dateTo range; results are filtered by keyword against titles and abstracts returned by the API.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class BioRxivSearchTool extends AbstractTool<BioRxivSearchInput, BioRxivSearchOutput> {

    private static final String API_BASE_URL = "https://api.biorxiv.org/details";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_ALLOWED_RESULTS = 100;
    private static final int API_PAGE_SIZE = 100;

    public BioRxivSearchTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(60));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setRequiresHttpConnection(true);
        return req;
    }

    @Override
    public BioRxivSearchOutput execute(JobResources resources, JobContext<BioRxivSearchOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        int maxResults = input.getMaxResults() != null ? Math.min(input.getMaxResults(), MAX_ALLOWED_RESULTS) : DEFAULT_MAX_RESULTS;
        String server = input.getServer() != null ? input.getServer().toLowerCase() : "both";
        validateDateFormat("dateFrom", input.getDateFrom());
        validateDateFormat("dateTo", input.getDateTo());
        try {
            CloseableHttpClient httpClient = resources.getHttpClient();
            List<BioRxivSearchOutput.BioRxivPreprint> allPreprints = new ArrayList<>();
            int totalCount = 0;
            context.publish("Searching bioRxiv API by date range, filtering by keyword", 20);
            List<String> servers = getServersToSearch(server);
            for (String srv : servers) {
                SearchResult result = searchByDateRange(httpClient, srv, input.getDateFrom(), input.getDateTo(), maxResults, input.getCategory());
                List<BioRxivSearchOutput.BioRxivPreprint> filtered = filterByKeyword(result.preprints, input.getQuery());
                allPreprints.addAll(filtered);
                totalCount += filtered.size();
            }
            context.publish("Sorting and limiting results", 80);
            allPreprints.sort((a, b) -> b.getDate().compareTo(a.getDate()));
            if (allPreprints.size() > maxResults) {
                allPreprints = allPreprints.subList(0, maxResults);
            }
            context.publish("Building output", 90);
            BioRxivSearchOutput output = new BioRxivSearchOutput();
            output.setPreprints(allPreprints);
            output.setTotalCount(totalCount);
            output.setQueryExecuted(input.getQuery());
            output.setTruncated(allPreprints.size() < totalCount);
            output.setServersSearched(server);
            context.publish("Complete", 100);
            return output;
        }
        catch (LLMReadableCheckedException e) {
            throw e;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    private List<String> getServersToSearch(String server) {
        if ("both".equals(server)) {
            return Arrays.asList("biorxiv", "medrxiv");
        }
        return Collections.singletonList(server);
    }

    private void validateDateFormat(String parameter, String date) throws InvalidInputException {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new InvalidInputException(parameter, date, "must be in YYYY-MM-DD format");
        }
    }

    private SearchResult searchByDateRange(CloseableHttpClient httpClient, String server, String dateFrom, String dateTo, int maxResults, String category) throws Exception {
        List<BioRxivSearchOutput.BioRxivPreprint> preprints = new ArrayList<>();
        int cursor = 0;
        int totalCount = 0;
        boolean hasMore = true;
        while (hasMore && preprints.size() < maxResults) {
            HttpGet request = new HttpGet(buildApiUrl(server, dateFrom, dateTo, cursor, category));
            request.setHeader("User-Agent", BioRxivFetchTool.USER_AGENT);
            HttpReply reply;
            try {
                reply = httpClient.execute(request, HttpReply.reader());
            }
            catch (Exception e) {
                // The call is made for the date window; the query is applied in memory afterwards, so
                // the window is the parameter the model can fix
                throw LLMReadableCheckedException.wrapWithContext(e, "bioRxiv", "dateFrom", dateFrom + ".." + dateTo,
                        "listing " + server + " preprints in the dateFrom..dateTo window");
            }
            if (reply.status() != 200) {
                throw new ExternalServiceException("bioRxiv", "API failed with HTTP " + reply.status());
            }
            ApiResponse apiResponse = parseApiResponse(reply.body(), server);
            if (cursor == 0) {
                totalCount = apiResponse.totalCount;
            }
            preprints.addAll(apiResponse.preprints);
            cursor += API_PAGE_SIZE;
            hasMore = apiResponse.preprints.size() == API_PAGE_SIZE && preprints.size() < maxResults;
        }
        return new SearchResult(preprints, totalCount);
    }

    private String buildApiUrl(String server, String dateFrom, String dateTo, int cursor, String category) {
        StringBuilder url = new StringBuilder(API_BASE_URL);
        url.append("/").append(server);
        url.append("/").append(dateFrom);
        url.append("/").append(dateTo);
        url.append("/").append(cursor);
        url.append("/json");
        if (category != null && !category.trim().isEmpty()) {
            String encodedCategory = URLEncoder.encode(category.replace(" ", "_"), StandardCharsets.UTF_8);
            url.append("?category=").append(encodedCategory);
        }
        return url.toString();
    }

    private ApiResponse parseApiResponse(String json, String server) throws Exception {
        JsonNode root = NucleoJsonSerializer.readTree(json);
        JsonNode collection = root.get("collection");
        List<BioRxivSearchOutput.BioRxivPreprint> preprints = new ArrayList<>();
        int totalCount = 0;
        if (root.has("messages")) {
            JsonNode messages = root.get("messages");
            if (messages.isArray() && messages.size() > 0) {
                JsonNode firstMessage = messages.get(0);
                if (firstMessage.has("total")) {
                    totalCount = firstMessage.get("total").asInt();
                }
            }
        }
        if (collection != null && collection.isArray()) {
            for (JsonNode node : collection) {
                BioRxivSearchOutput.BioRxivPreprint preprint = new BioRxivSearchOutput.BioRxivPreprint();
                preprint.setDoi(node.has("doi") ? node.get("doi").asText() : null);
                preprint.setTitle(node.has("title") ? node.get("title").asText() : null);
                preprint.setDate(node.has("date") ? node.get("date").asText() : null);
                preprint.setCategory(node.has("category") ? node.get("category").asText() : null);
                preprint.setServer(node.has("server") ? node.get("server").asText() : server);
                preprint.setVersion(node.has("version") ? node.get("version").asText() : null);
                preprint.setAbstractText(node.has("abstract") ? node.get("abstract").asText() : null);
                preprint.setCorrespondingAuthor(node.has("author_corresponding") ? node.get("author_corresponding").asText() : null);
                preprint.setCorrespondingInstitution(node.has("author_corresponding_institution") ? node.get("author_corresponding_institution").asText() : null);
                preprint.setPublishedDoi(node.has("published") ? node.get("published").asText() : null);
                if (node.has("authors")) {
                    String authorsStr = node.get("authors").asText();
                    List<String> authorList = parseAuthorString(authorsStr);
                    preprint.setAuthors(authorList);
                }
                else {
                    preprint.setAuthors(new ArrayList<>());
                }
                preprints.add(preprint);
            }
        }
        return new ApiResponse(preprints, totalCount);
    }

    private List<BioRxivSearchOutput.BioRxivPreprint> filterByKeyword(List<BioRxivSearchOutput.BioRxivPreprint> preprints, String query) {
        String lowerQuery = query.toLowerCase();
        return preprints.stream()
                .filter(p -> {
                    if (p.getTitle() != null && p.getTitle().toLowerCase().contains(lowerQuery)) {
                        return true;
                    }
                    if (p.getAbstractText() != null && p.getAbstractText().toLowerCase().contains(lowerQuery)) {
                        return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    private List<String> parseAuthorString(String authorsStr) {
        if (authorsStr == null || authorsStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] authors = authorsStr.split(";");
        return Arrays.stream(authors)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static class SearchResult {
        final List<BioRxivSearchOutput.BioRxivPreprint> preprints;
        final int totalCount;

        SearchResult(List<BioRxivSearchOutput.BioRxivPreprint> preprints, int totalCount) {
            this.preprints = preprints;
            this.totalCount = totalCount;
        }
    }

    private static class ApiResponse {
        final List<BioRxivSearchOutput.BioRxivPreprint> preprints;
        final int totalCount;

        ApiResponse(List<BioRxivSearchOutput.BioRxivPreprint> preprints, int totalCount) {
            this.preprints = preprints;
            this.totalCount = totalCount;
        }
    }
}
