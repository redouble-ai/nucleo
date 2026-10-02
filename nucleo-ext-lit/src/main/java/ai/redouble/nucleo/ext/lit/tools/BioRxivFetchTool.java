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

import java.time.*;
import java.util.*;

/**
 * Fetches detailed preprint information from bioRxiv/medRxiv by DOI.
 *
 * <p>Retrieves complete preprint metadata including full abstracts,
 * authors, funding information, and publication status.
 * Use this tool when you have a specific DOI and need comprehensive details.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-12)
 */
@DisplayName(value = "BioRxiv Fetch", action = "Fetching Complete BioRxiv Preprint")
@ToolName("fetch_biorxiv_preprint")
@ToolDescription(value = "Fetch complete details for a specific bioRxiv/medRxiv preprint by DOI. Returns full metadata including abstract, authors, funding, and publication status.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class BioRxivFetchTool extends AbstractTool<BioRxivFetchInput, BioRxivFetchOutput> {

    private static final String API_BASE_URL = "https://api.biorxiv.org/details";
    /** The identity bioRxiv asks API callers to send; the search tool sends the same one. */
    static final String USER_AGENT = "Redouble-AI-Platform/1.0 (mailto:research@redouble.ai)";

    public BioRxivFetchTool(Identifiable parent) {
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
    public BioRxivFetchOutput execute(JobResources resources, JobContext<BioRxivFetchOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        if (input.getDoi() == null || input.getDoi().trim().isEmpty()) {
            throw new InvalidInputException("doi", null, "is required");
        }
        String doi = input.getDoi().trim();
        if (!doi.startsWith("10.1101/")) {
            throw new InvalidInputException("doi", input.getDoi(), "Invalid bioRxiv/medRxiv DOI format. Expected: 10.1101/...");
        }
        String server = input.getServer();
        if (server == null || server.trim().isEmpty()) {
            server = "biorxiv";
        }
        server = server.toLowerCase();
        if (!server.equals("biorxiv") && !server.equals("medrxiv")) {
            throw new InvalidInputException("server", input.getServer(), "must be 'biorxiv' or 'medrxiv'");
        }
        try {
            context.publish("Fetching preprint from bioRxiv API", 20);
            HttpGet request = new HttpGet(buildApiUrl(server, doi));
            request.setHeader("User-Agent", USER_AGENT);
            HttpReply reply;
            try {
                reply = resources.getHttpClient().execute(request, HttpReply.reader());
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.wrapWithContext(e, "bioRxiv", "doi", doi, "fetching the preprint");
            }
            if (reply.status() != 200) {
                throw new ExternalServiceException("bioRxiv", "API failed with HTTP " + reply.status() + " for DOI: " + doi);
            }
            context.publish("Parsing response", 60);
            BioRxivFetchOutput.DetailedPreprint preprint = parseApiResponse(reply.body(), server, doi);
            if (preprint == null) {
                throw new ResourceNotFoundException("preprint", doi);
            }
            context.publish("Building output", 90);
            BioRxivFetchOutput output = new BioRxivFetchOutput();
            output.setPreprint(preprint);
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

    private String buildApiUrl(String server, String doi) {
        StringBuilder url = new StringBuilder(API_BASE_URL);
        url.append("/").append(server);
        url.append("/").append(doi);
        url.append("/na/json");
        return url.toString();
    }

    private BioRxivFetchOutput.DetailedPreprint parseApiResponse(String json, String server, String requestedDoi) throws Exception {
        JsonNode root = NucleoJsonSerializer.readTree(json);
        JsonNode collection = root.get("collection");
        if (collection == null || !collection.isArray() || collection.size() == 0) {
            return null;
        }
        JsonNode node = collection.get(0);
        BioRxivFetchOutput.DetailedPreprint preprint = new BioRxivFetchOutput.DetailedPreprint();
        preprint.setDoi(node.has("doi") ? node.get("doi").asText() : requestedDoi);
        preprint.setTitle(node.has("title") ? node.get("title").asText() : null);
        preprint.setDate(node.has("date") ? node.get("date").asText() : null);
        preprint.setCategory(node.has("category") ? node.get("category").asText() : null);
        preprint.setServer(node.has("server") ? node.get("server").asText() : server);
        preprint.setVersion(node.has("version") ? node.get("version").asText() : null);
        preprint.setType(node.has("type") ? node.get("type").asText() : null);
        preprint.setLicense(node.has("license") ? node.get("license").asText() : null);
        preprint.setAbstractText(node.has("abstract") ? node.get("abstract").asText() : null);
        preprint.setAuthorCorresponding(node.has("author_corresponding") ? node.get("author_corresponding").asText() : null);
        preprint.setAuthorCorrespondingInstitution(node.has("author_corresponding_institution") ? node.get("author_corresponding_institution").asText() : null);
        preprint.setPublishedDoi(node.has("published") ? node.get("published").asText() : null);
        preprint.setJatsXmlPath(node.has("jatsxml") ? node.get("jatsxml").asText() : null);
        if (node.has("authors")) {
            String authorsStr = node.get("authors").asText();
            List<BioRxivFetchOutput.Author> authorList = parseAuthors(authorsStr);
            preprint.setDetailedAuthors(authorList);
        }
        else {
            preprint.setDetailedAuthors(null);
        }
        if (node.has("funding")) {
            JsonNode fundingNode = node.get("funding");
            List<BioRxivFetchOutput.FundingInfo> fundingList = parseFunding(fundingNode);
            preprint.setFunding(fundingList);
        }
        else {
            preprint.setFunding(null);
        }
        return preprint;
    }

    private List<BioRxivFetchOutput.Author> parseAuthors(String authorsStr) {
        List<BioRxivFetchOutput.Author> authors = new ArrayList<>();
        if (authorsStr == null || authorsStr.trim().isEmpty()) {
            return authors;
        }
        String[] authorNames = authorsStr.split(";");
        for (String name : authorNames) {
            name = name.trim();
            if (!name.isEmpty()) {
                BioRxivFetchOutput.Author author = new BioRxivFetchOutput.Author();
                author.setName(name);
                authors.add(author);
            }
        }
        return authors;
    }

    private List<BioRxivFetchOutput.FundingInfo> parseFunding(JsonNode fundingNode) {
        List<BioRxivFetchOutput.FundingInfo> fundingList = new ArrayList<>();
        if (fundingNode == null) {
            return fundingList;
        }
        if (fundingNode.isArray()) {
            for (JsonNode item : fundingNode) {
                BioRxivFetchOutput.FundingInfo funding = parseSingleFunding(item);
                if (funding != null) {
                    fundingList.add(funding);
                }
            }
        }
        else {
            BioRxivFetchOutput.FundingInfo funding = parseSingleFunding(fundingNode);
            if (funding != null) {
                fundingList.add(funding);
            }
        }
        return fundingList;
    }

    private BioRxivFetchOutput.FundingInfo parseSingleFunding(JsonNode node) {
        if (node == null) {
            return null;
        }
        BioRxivFetchOutput.FundingInfo funding = new BioRxivFetchOutput.FundingInfo();
        funding.setName(node.has("name") ? node.get("name").asText() : null);
        funding.setId(node.has("id") ? node.get("id").asText() : null);
        funding.setIdType(node.has("id-type") ? node.get("id-type").asText() : null);
        funding.setAward(node.has("award") ? node.get("award").asText() : null);
        if (funding.getName() == null && funding.getId() == null && funding.getAward() == null) {
            return null;
        }
        return funding;
    }
}
