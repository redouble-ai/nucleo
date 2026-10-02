/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.tools;

import ai.redouble.nucleo.ext.lit.*;
import ai.redouble.nucleo.ext.lit.models.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.secrets.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.util.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.slf4j.*;
import org.w3c.dom.*;

import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/**
 * Searches PubMed for biomedical literature using NCBI E-utilities API.
 *
 * <p>This tool provides access to PubMed's 36+ million citations from
 * biomedical literature, including MEDLINE, life science journals, and
 * online books. Uses the official NCBI E-utilities API (ESearch + EFetch).
 *
 * <p>Rate limits: 3 requests/second without API key, 10/second with API key.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@DisplayName(value = "PubMed Search", action = "Searching PubMed Database")
@MCP
@ToolName("search_pubmed")
@ToolDescription(value = "Search PubMed biomedical literature database by keywords, authors, dates, or MeSH terms. Returns article metadata including titles, authors, journals, and optionally abstracts.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class PubMedSearchTool extends AbstractTool<PubMedSearchInput, PubMedSearchOutput> {
    private static final Logger log = LoggerFactory.getLogger(PubMedSearchTool.class);

    private static final String ESEARCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi";
    private static final String EFETCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_ALLOWED_RESULTS = 100;
    private static final PubMedRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(PubMedRateLimiter.class);

    private static final String API_KEY;
    static {
        Credential ncbi = Secrets.configured().find(PubMedRateLimiter.NCBI_SECRET_ID);
        if (ncbi == null) {
            log.warn("NCBI API key not configured - PubMed requests run at the unauthenticated rate limit. To use one, provide {}",
                    Secrets.configured().describe(PubMedRateLimiter.NCBI_SECRET_ID));
        }
        API_KEY = ncbi == null ? null : ncbi.secret();
    }

    public PubMedSearchTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(60));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setRequiresHttpConnection(true);
        // One held slot covers the job: the sequential ESearch and EFetch calls ride it
        req.requireRateLimiter(RATE_LIMITER, null);
        return req;
    }

    @Override
    public PubMedSearchOutput execute(JobResources resources, JobContext<PubMedSearchOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);

        // Validate and set defaults
        if (input.getQuery() == null || input.getQuery().trim().isEmpty()) {
            throw new InvalidInputException("query", null, "is required");
        }

        int maxResults = input.getMaxResults() != null ? Math.min(input.getMaxResults(), MAX_ALLOWED_RESULTS) : DEFAULT_MAX_RESULTS;
        boolean includeAbstracts = input.getIncludeAbstracts() != null ? input.getIncludeAbstracts() : false;

        try {
            CloseableHttpClient httpClient = resources.getHttpClient();

            // Step 1: ESearch to get PMIDs
            context.publish("Searching PubMed", 20);
            String searchQuery = buildSearchQuery();
            SearchResult searchResult = executeESearch(httpClient, searchQuery, maxResults);

            if (searchResult.pmids.isEmpty()) {
                PubMedSearchOutput output = new PubMedSearchOutput();
                output.setArticles(new ArrayList<>());
                output.setTotalCount(0);
                output.setQueryExecuted(searchQuery);
                output.setTruncated(false);
                return output;
            }

            // Step 2: EFetch to get article details
            context.publish("Fetching article details", 50);
            List<PubMedSearchOutput.PubMedArticle> articles = executeEFetch(httpClient, searchResult.pmids, includeAbstracts);

            // Build output
            context.publish("Building results", 90);
            PubMedSearchOutput output = new PubMedSearchOutput();
            output.setArticles(articles);
            output.setTotalCount(searchResult.totalCount);
            output.setQueryExecuted(searchQuery);
            output.setTruncated(articles.size() < searchResult.totalCount);

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

    /**
     * Builds the full search query with filters.
     */
    private String buildSearchQuery() {
        StringBuilder query = new StringBuilder(input.getQuery());

        // Add date range filter
        if (input.getDateFrom() != null || input.getDateTo() != null) {
            query.append(" AND ");
            String from = input.getDateFrom() != null ? input.getDateFrom() : "1900";
            String to = input.getDateTo() != null ? input.getDateTo() : "3000";
            query.append(from).append(":").append(to).append("[PDAT]");
        }

        // Add publication type filter
        if (input.getPublicationType() != null) {
            query.append(" AND ").append(input.getPublicationType()).append("[PT]");
        }

        return query.toString();
    }

    /**
     * Executes ESearch to get PMIDs.
     */
    private SearchResult executeESearch(CloseableHttpClient httpClient, String query, int maxResults) throws Exception {
        // Build URL
        String sortOrder = "relevance";
        if ("pub_date".equals(input.getSortBy())) {
            sortOrder = "pub+date";
        }
        else if ("pub_date_asc".equals(input.getSortBy())) {
            sortOrder = "pub+date";
        }

        StringBuilder url = new StringBuilder(ESEARCH_URL);
        url.append("?db=pubmed");
        url.append("&term=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        url.append("&retmax=").append(maxResults);
        url.append("&retmode=xml");
        url.append("&sort=").append(sortOrder);
        if (API_KEY != null) {
            url.append("&api_key=").append(API_KEY);
        }

        HttpReply reply;
        try {
            reply = httpClient.execute(new HttpGet(url.toString()), HttpReply.reader());
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.wrapWithContext(e, "PubMed", "query", input.getQuery(), "searching PubMed");
        }
        if (reply.status() != 200) {
            throw eSearchRejection(reply.status(), query);
        }

        // Parse XML response
        return parseESearchResponse(reply.body());
    }

    /**
     * The query is forwarded to NCBI unvalidated, so a 400 is NCBI rejecting its syntax -
     * a fault the caller can correct and retry; any other error status is the service
     * failing. Package-private for its test.
     */
    static LLMReadableCheckedException eSearchRejection(int status, String query) {
        if (status == 400) {
            return new InvalidInputException("query", query, "was rejected by PubMed's ESearch (HTTP 400) - correct the query syntax and retry");
        }
        return new ExternalServiceException("PubMed", "ESearch failed with HTTP " + status);
    }

    /**
     * Parses ESearch XML response to extract PMIDs and count.
     */
    private SearchResult parseESearchResponse(String xml) throws Exception {
        Document doc = SafeXml.parse(xml);

        // Get total count
        NodeList countNodes = doc.getElementsByTagName("Count");
        int totalCount = 0;
        if (countNodes.getLength() > 0) {
            totalCount = Integer.parseInt(countNodes.item(0).getTextContent());
        }

        // Get PMIDs
        List<String> pmids = new ArrayList<>();
        NodeList idNodes = doc.getElementsByTagName("Id");
        for (int i = 0; i < idNodes.getLength(); i++) {
            pmids.add(idNodes.item(i).getTextContent());
        }

        return new SearchResult(pmids, totalCount);
    }

    /**
     * Executes EFetch to get article details for PMIDs.
     */
    private List<PubMedSearchOutput.PubMedArticle> executeEFetch(CloseableHttpClient httpClient, List<String> pmids, boolean includeAbstracts) throws Exception {

        // Build URL
        String pmidList = String.join(",", pmids);
        StringBuilder url = new StringBuilder(EFETCH_URL);
        url.append("?db=pubmed");
        url.append("&id=").append(pmidList);
        url.append("&retmode=xml");
        if (API_KEY != null) {
            url.append("&api_key=").append(API_KEY);
        }

        HttpReply reply;
        try {
            reply = httpClient.execute(new HttpGet(url.toString()), HttpReply.reader());
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.wrapWithContext(e, "PubMed", "query", input.getQuery(), "fetching the article details");
        }
        if (reply.status() != 200) {
            throw new ExternalServiceException("PubMed", "EFetch failed with HTTP " + reply.status());
        }

        // Parse XML response
        return parseEFetchResponse(reply.body(), includeAbstracts);
    }

    /**
     * Parses EFetch XML response to extract article details.
     */
    private List<PubMedSearchOutput.PubMedArticle> parseEFetchResponse(String xml, boolean includeAbstracts) throws Exception {

        Document doc = SafeXml.parse(xml);

        List<PubMedSearchOutput.PubMedArticle> articles = new ArrayList<>();
        NodeList articleNodes = doc.getElementsByTagName("PubmedArticle");

        for (int i = 0; i < articleNodes.getLength(); i++) {
            Element articleElement = (Element)articleNodes.item(i);
            PubMedSearchOutput.PubMedArticle article = parseArticle(articleElement, includeAbstracts);
            articles.add(article);
        }

        return articles;
    }

    /**
     * Parses a single PubmedArticle XML element.
     */
    private PubMedSearchOutput.PubMedArticle parseArticle(Element articleElement, boolean includeAbstracts) {
        PubMedSearchOutput.PubMedArticle article = new PubMedSearchOutput.PubMedArticle();
        article.setPmid(getElementText(articleElement, "PMID"));
        article.setTitle(getElementText(articleElement, "ArticleTitle"));

        // Authors
        List<String> authors = new ArrayList<>();
        NodeList authorNodes = articleElement.getElementsByTagName("Author");
        for (int i = 0; i < Math.min(authorNodes.getLength(), 10); i++) {
            Element author = (Element)authorNodes.item(i);
            String lastName = getElementText(author, "LastName");
            String initials = getElementText(author, "Initials");
            if (lastName != null) {
                authors.add(initials != null ? lastName + " " + initials : lastName);
            }
        }
        article.setAuthors(authors);

        // Journal
        NodeList journalNodes = articleElement.getElementsByTagName("Title");
        if (journalNodes.getLength() > 0) {
            article.setJournal(journalNodes.item(0).getTextContent());
        }

        // Publication Date
        String pubDate = extractPublicationDate(articleElement);
        article.setPublicationDate(pubDate);

        // Abstract (if requested)
        if (includeAbstracts) {
            StringBuilder abstractText = new StringBuilder();
            NodeList abstractNodes = articleElement.getElementsByTagName("AbstractText");
            for (int i = 0; i < abstractNodes.getLength(); i++) {
                if (i > 0)
                    abstractText.append("\n\n");
                abstractText.append(abstractNodes.item(i).getTextContent());
            }
            if (abstractText.length() > 0) {
                article.setAbstractText(abstractText.toString());
            }
        }

        // DOI
        NodeList articleIdNodes = articleElement.getElementsByTagName("ArticleId");
        for (int i = 0; i < articleIdNodes.getLength(); i++) {
            Element idElement = (Element)articleIdNodes.item(i);
            String idType = idElement.getAttribute("IdType");
            if ("doi".equals(idType)) {
                article.setDoi(idElement.getTextContent());
            }
            else if ("pmc".equals(idType)) {
                article.setPmcid(idElement.getTextContent());
            }
        }

        // Publication Types
        List<String> pubTypes = new ArrayList<>();
        NodeList pubTypeNodes = articleElement.getElementsByTagName("PublicationType");
        for (int i = 0; i < pubTypeNodes.getLength(); i++) {
            pubTypes.add(pubTypeNodes.item(i).getTextContent());
        }
        article.setPublicationTypes(pubTypes);

        // MeSH Terms (limit to avoid huge lists)
        List<String> meshTerms = new ArrayList<>();
        NodeList meshNodes = articleElement.getElementsByTagName("DescriptorName");
        for (int i = 0; i < Math.min(meshNodes.getLength(), 15); i++) {
            meshTerms.add(meshNodes.item(i).getTextContent());
        }
        article.setMeshTerms(meshTerms);

        return article;
    }

    /**
     * Extracts publication date in YYYY/MM/DD format.
     */
    private String extractPublicationDate(Element articleElement) {
        NodeList pubDateNodes = articleElement.getElementsByTagName("PubDate");
        if (pubDateNodes.getLength() > 0) {
            Element pubDate = (Element)pubDateNodes.item(0);
            String year = getElementText(pubDate, "Year");
            String month = getElementText(pubDate, "Month");
            String day = getElementText(pubDate, "Day");

            if (year != null) {
                StringBuilder date = new StringBuilder(year);
                if (month != null) {
                    date.append("/").append(formatMonth(month));
                    if (day != null) {
                        date.append("/").append(String.format("%02d", Integer.parseInt(day)));
                    }
                }
                return date.toString();
            }
        }
        return null;
    }

    /**
     * Converts month name to number (e.g., "Jan" -> "01").
     */
    private String formatMonth(String month) {
        Map<String, String> monthMap = new HashMap<>();
        monthMap.put("Jan", "01");
        monthMap.put("Feb", "02");
        monthMap.put("Mar", "03");
        monthMap.put("Apr", "04");
        monthMap.put("May", "05");
        monthMap.put("Jun", "06");
        monthMap.put("Jul", "07");
        monthMap.put("Aug", "08");
        monthMap.put("Sep", "09");
        monthMap.put("Oct", "10");
        monthMap.put("Nov", "11");
        monthMap.put("Dec", "12");

        String mapped = monthMap.get(month);
        if (mapped != null)
            return mapped;

        // Try parsing as number (PubMed sometimes returns numeric months)
        return String.format("%02d", Integer.parseInt(month));
    }

    /**
     * Helper to get text content of first matching child element.
     */
    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    /**
     * Internal class to hold ESearch results.
     */
    private static class SearchResult {
        final List<String> pmids;
        final int totalCount;

        SearchResult(List<String> pmids, int totalCount) {
            this.pmids = pmids;
            this.totalCount = totalCount;
        }
    }
}