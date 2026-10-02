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
import ai.redouble.nucleo.secrets.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.util.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.slf4j.*;
import org.w3c.dom.*;

import java.time.*;
import java.util.*;

/**
 * Fetches detailed article information from PubMed by PMID.
 *
 * <p>Retrieves complete article metadata including full abstracts,
 * author affiliations, MeSH terms, keywords, and grant information.
 * Use this tool when you have specific PMIDs and need comprehensive details.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@DisplayName(value = "PubMed Fetch", action = "Fetching Complete PubMed Articles")
@ToolName("fetch_pubmed_articles")
@ToolDescription(value = "Fetch complete details for specific PubMed articles by PMID. Returns full metadata including abstracts, authors with affiliations, MeSH terms, keywords, and grant information.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class PubMedFetchTool extends AbstractTool<PubMedFetchInput, PubMedFetchOutput> {
    private static final Logger log = LoggerFactory.getLogger(PubMedFetchTool.class);

    private static final String EFETCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    private static final int MAX_PMIDS_PER_REQUEST = 100;
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

    public PubMedFetchTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(60));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setRequiresHttpConnection(true);
        req.requireRateLimiter(RATE_LIMITER, null); // Shares 10 QPS limit with search
        return req;
    }

    @Override
    public PubMedFetchOutput execute(JobResources resources, JobContext<PubMedFetchOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);

        if (input.getPmids() == null || input.getPmids().isEmpty()) {
            throw new InvalidInputException("pmids", null, "is required and cannot be empty");
        }

        if (input.getPmids().size() > MAX_PMIDS_PER_REQUEST) {
            throw new InvalidInputException("pmids", input.getPmids().size(), "Maximum " + MAX_PMIDS_PER_REQUEST + " PMIDs per request");
        }

        // Set defaults
        boolean includeAbstract = input.getIncludeAbstract() != null ? input.getIncludeAbstract() : true;
        boolean includeMesh = input.getIncludeMeshTerms() != null ? input.getIncludeMeshTerms() : true;
        boolean includeGrants = input.getIncludeGrants() != null ? input.getIncludeGrants() : false;
        boolean includeAffiliations = input.getIncludeAffiliations() != null ? input.getIncludeAffiliations() : false;

        try {
            context.publish("Fetching articles from PubMed", 20);

            // Build and execute request
            String pmidList = String.join(",", input.getPmids());
            String xml = executeFetch(resources.getHttpClient(), pmidList);

            context.publish("Parsing article details", 60);

            // Parse response
            List<PubMedFetchOutput.DetailedArticle> articles = parseArticles(xml, includeAbstract, includeMesh, includeGrants, includeAffiliations);

            // Build output
            context.publish("Building results", 90);
            PubMedFetchOutput output = new PubMedFetchOutput();
            output.setArticles(articles);
            output.setRequestedCount(input.getPmids().size());
            output.setRetrievedCount(articles.size());

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
     * Executes EFetch request.
     */
    private String executeFetch(CloseableHttpClient httpClient, String pmidList) throws Exception {
        StringBuilder url = new StringBuilder(EFETCH_URL);
        url.append("?db=pubmed");
        url.append("&id=").append(pmidList);
        url.append("&retmode=xml");
        if (API_KEY != null) {
            url.append("&api_key=").append(API_KEY);
        }

        HttpGet request = new HttpGet(url.toString());

        HttpReply reply;
        try {
            reply = httpClient.execute(request, HttpReply.reader());
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.wrapWithContext(e, "PubMed", "pmids", input.getPmids(), "fetching the articles");
        }
        if (reply.status() != 200) {
            throw new ExternalServiceException("PubMed", "EFetch failed with HTTP " + reply.status());
        }
        return reply.body();
    }

    /**
     * Parses article XML response.
     */
    private List<PubMedFetchOutput.DetailedArticle> parseArticles(String xml, boolean includeAbstract, boolean includeMesh, boolean includeGrants, boolean includeAffiliations)
            throws Exception {

        Document doc = SafeXml.parse(xml);

        List<PubMedFetchOutput.DetailedArticle> articles = new ArrayList<>();
        NodeList articleNodes = doc.getElementsByTagName("PubmedArticle");

        for (int i = 0; i < articleNodes.getLength(); i++) {
            Element articleElement = (Element)articleNodes.item(i);
            PubMedFetchOutput.DetailedArticle article = parseDetailedArticle(articleElement, includeAbstract, includeMesh, includeGrants, includeAffiliations);
            articles.add(article);
        }

        return articles;
    }

    /**
     * Parses a single detailed article.
     */
    private PubMedFetchOutput.DetailedArticle parseDetailedArticle(Element articleElement, boolean includeAbstract, boolean includeMesh, boolean includeGrants, boolean includeAffiliations) {

        PubMedFetchOutput.DetailedArticle article = new PubMedFetchOutput.DetailedArticle();
        article.setPmid(getElementText(articleElement, "PMID"));
        article.setTitle(getElementText(articleElement, "ArticleTitle"));

        // Authors
        article.setDetailedAuthors(parseAuthors(articleElement, includeAffiliations));

        // Journal
        article.setJournalInfo(parseJournal(articleElement));

        // Publication Date
        article.setPublicationDate(extractPublicationDate(articleElement));

        // Abstract
        if (includeAbstract) {
            article.setAbstractText(extractAbstract(articleElement));
        }

        // DOI and PMCID
        parseArticleIds(articleElement, article);

        // Publication Types
        article.setPublicationTypes(extractPublicationTypes(articleElement));

        // MeSH Terms
        if (includeMesh) {
            article.setMeshTerms(parseMeshTerms(articleElement));
        }

        // Keywords
        article.setKeywords(extractKeywords(articleElement));

        // Grants
        if (includeGrants) {
            article.setGrants(parseGrants(articleElement));
        }

        // Citation
        article.setCitation(buildCitation(article));

        return article;
    }

    /**
     * Parses author list with optional affiliations.
     */
    private List<PubMedFetchOutput.Author> parseAuthors(Element articleElement, boolean includeAffiliations) {
        List<PubMedFetchOutput.Author> authors = new ArrayList<>();
        NodeList authorNodes = articleElement.getElementsByTagName("Author");

        for (int i = 0; i < authorNodes.getLength(); i++) {
            Element authorElement = (Element)authorNodes.item(i);
            PubMedFetchOutput.Author author = new PubMedFetchOutput.Author();

            author.setLastName(getElementText(authorElement, "LastName"));
            author.setFirstName(getElementText(authorElement, "ForeName"));

            if (includeAffiliations) {
                NodeList affNodes = authorElement.getElementsByTagName("Affiliation");
                if (affNodes.getLength() > 0) {
                    author.setAffiliation(affNodes.item(0).getTextContent());
                }
            }

            authors.add(author);
        }

        return authors;
    }

    /**
     * Parses journal information.
     */
    private PubMedFetchOutput.Journal parseJournal(Element articleElement) {
        PubMedFetchOutput.Journal journal = new PubMedFetchOutput.Journal();

        NodeList journalNodes = articleElement.getElementsByTagName("Journal");
        if (journalNodes.getLength() > 0) {
            Element journalElement = (Element)journalNodes.item(0);

            journal.setTitle(getElementText(journalElement, "Title"));
            journal.setAbbreviation(getElementText(journalElement, "ISOAbbreviation"));
            journal.setIssn(getElementText(journalElement, "ISSN"));

            NodeList issueNodes = journalElement.getElementsByTagName("JournalIssue");
            if (issueNodes.getLength() > 0) {
                Element issueElement = (Element)issueNodes.item(0);
                journal.setVolume(getElementText(issueElement, "Volume"));
                journal.setIssue(getElementText(issueElement, "Issue"));
            }
        }

        // Pagination
        NodeList paginationNodes = articleElement.getElementsByTagName("MedlinePgn");
        if (paginationNodes.getLength() > 0) {
            journal.setPages(paginationNodes.item(0).getTextContent());
        }

        return journal;
    }

    /**
     * Extracts full abstract text.
     */
    private String extractAbstract(Element articleElement) {
        StringBuilder abstractText = new StringBuilder();
        NodeList abstractNodes = articleElement.getElementsByTagName("AbstractText");

        for (int i = 0; i < abstractNodes.getLength(); i++) {
            Element abstractElement = (Element)abstractNodes.item(i);
            String label = abstractElement.getAttribute("Label");

            if (i > 0)
                abstractText.append("\n\n");
            if (!label.isEmpty()) {
                abstractText.append(label).append(": ");
            }
            abstractText.append(abstractElement.getTextContent());
        }

        return abstractText.length() > 0 ? abstractText.toString() : null;
    }

    /**
     * Parses article IDs (DOI, PMCID).
     */
    private void parseArticleIds(Element articleElement, PubMedFetchOutput.DetailedArticle article) {
        NodeList articleIdNodes = articleElement.getElementsByTagName("ArticleId");
        for (int i = 0; i < articleIdNodes.getLength(); i++) {
            Element idElement = (Element)articleIdNodes.item(i);
            String idType = idElement.getAttribute("IdType");
            String idValue = idElement.getTextContent();

            if ("doi".equals(idType)) {
                article.setDoi(idValue);
            }
            else if ("pmc".equals(idType)) {
                article.setPmcid(idValue);
            }
        }
    }

    /**
     * Extracts publication types.
     */
    private List<String> extractPublicationTypes(Element articleElement) {
        List<String> types = new ArrayList<>();
        NodeList typeNodes = articleElement.getElementsByTagName("PublicationType");
        for (int i = 0; i < typeNodes.getLength(); i++) {
            types.add(typeNodes.item(i).getTextContent());
        }
        return types;
    }

    /**
     * Parses MeSH terms with qualifiers.
     */
    private List<PubMedFetchOutput.MeshTerm> parseMeshTerms(Element articleElement) {
        List<PubMedFetchOutput.MeshTerm> meshTerms = new ArrayList<>();
        NodeList meshHeadingNodes = articleElement.getElementsByTagName("MeshHeading");

        for (int i = 0; i < meshHeadingNodes.getLength(); i++) {
            Element meshHeading = (Element)meshHeadingNodes.item(i);
            PubMedFetchOutput.MeshTerm term = new PubMedFetchOutput.MeshTerm();

            // Descriptor
            NodeList descriptorNodes = meshHeading.getElementsByTagName("DescriptorName");
            if (descriptorNodes.getLength() > 0) {
                Element descriptor = (Element)descriptorNodes.item(0);
                term.setDescriptor(descriptor.getTextContent());
                term.setMajorTopic("Y".equals(descriptor.getAttribute("MajorTopicYN")));
            }

            // Qualifiers
            List<String> qualifiers = new ArrayList<>();
            NodeList qualifierNodes = meshHeading.getElementsByTagName("QualifierName");
            for (int j = 0; j < qualifierNodes.getLength(); j++) {
                qualifiers.add(qualifierNodes.item(j).getTextContent());
            }
            if (!qualifiers.isEmpty()) {
                term.setQualifiers(qualifiers);
            }

            meshTerms.add(term);
        }

        return meshTerms;
    }

    /**
     * Extracts keywords.
     */
    private List<String> extractKeywords(Element articleElement) {
        List<String> keywords = new ArrayList<>();
        NodeList keywordNodes = articleElement.getElementsByTagName("Keyword");
        for (int i = 0; i < keywordNodes.getLength(); i++) {
            keywords.add(keywordNodes.item(i).getTextContent());
        }
        return keywords.isEmpty() ? null : keywords;
    }

    /**
     * Parses grant information.
     */
    private List<PubMedFetchOutput.Grant> parseGrants(Element articleElement) {
        List<PubMedFetchOutput.Grant> grants = new ArrayList<>();
        NodeList grantNodes = articleElement.getElementsByTagName("Grant");

        for (int i = 0; i < grantNodes.getLength(); i++) {
            Element grantElement = (Element)grantNodes.item(i);
            PubMedFetchOutput.Grant grant = new PubMedFetchOutput.Grant();

            grant.setGrantId(getElementText(grantElement, "GrantID"));
            grant.setAgency(getElementText(grantElement, "Agency"));
            grant.setCountry(getElementText(grantElement, "Country"));

            grants.add(grant);
        }

        return grants.isEmpty() ? null : grants;
    }

    /**
     * Extracts publication date.
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
                    date.append("/").append(month);
                    if (day != null) {
                        date.append("/").append(day);
                    }
                }
                return date.toString();
            }
        }
        return null;
    }

    /**
     * Builds citation string.
     */
    private String buildCitation(PubMedFetchOutput.DetailedArticle article) {
        StringBuilder citation = new StringBuilder();

        // Authors (first 3)
        if (article.getDetailedAuthors() != null && !article.getDetailedAuthors().isEmpty()) {
            int authorCount = Math.min(3, article.getDetailedAuthors().size());
            for (int i = 0; i < authorCount; i++) {
                if (i > 0)
                    citation.append(", ");
                PubMedFetchOutput.Author author = article.getDetailedAuthors().get(i);
                citation.append(author.getLastName());
                if (author.getFirstName() != null) {
                    citation.append(" ").append(author.getFirstName().charAt(0));
                }
            }
            if (article.getDetailedAuthors().size() > 3) {
                citation.append(", et al");
            }
            citation.append(". ");
        }

        // Title
        if (article.getTitle() != null) {
            citation.append(article.getTitle());
            if (!article.getTitle().endsWith(".")) {
                citation.append(".");
            }
            citation.append(" ");
        }

        // Journal
        if (article.getJournalInfo() != null) {
            if (article.getJournalInfo().getAbbreviation() != null) {
                citation.append(article.getJournalInfo().getAbbreviation()).append(". ");
            }
            citation.append(article.getPublicationDate()).append(". ");
        }

        // PMID
        if (article.getPmid() != null) {
            citation.append("PMID: ").append(article.getPmid()).append(".");
        }

        return citation.toString();
    }

    /**
     * Helper to get text content of first matching child element.
     */
    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }
}