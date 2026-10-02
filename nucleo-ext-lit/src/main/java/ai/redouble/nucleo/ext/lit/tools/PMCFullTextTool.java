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

/**
 * Fetches full-text articles from PubMed Central via NCBI E-utilities.
 * Only works for open-access articles available in PMC.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-25)
 */
@DisplayName(value = "PMC Full Text", action = "Fetching full text from PubMed Central")
@MCP
@ToolName("fetch_pmc_fulltext")
@ToolDescription(value = "Fetch full-text article from PubMed Central by PMCID. Returns the complete article text. Only works for open-access articles in PMC.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class PMCFullTextTool extends AbstractTool<PMCFullTextInput, PMCFullTextOutput> {
    private static final Logger log = LoggerFactory.getLogger(PMCFullTextTool.class);
    private static final String EFETCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    private static final PubMedRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(PubMedRateLimiter.class);

    private static final String API_KEY;
    static {
        Credential ncbi = Secrets.configured().find(PubMedRateLimiter.NCBI_SECRET_ID);
        if (ncbi == null) {
            log.warn("NCBI API key not configured - PMC requests run at the unauthenticated rate limit. To use one, provide {}",
                    Secrets.configured().describe(PubMedRateLimiter.NCBI_SECRET_ID));
        }
        API_KEY = ncbi == null ? null : ncbi.secret();
    }

    public PMCFullTextTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(120));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setRequiresHttpConnection(true);
        req.requireRateLimiter(RATE_LIMITER, null);
        return req;
    }

    @Override
    public PMCFullTextOutput execute(JobResources resources, JobContext<PMCFullTextOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        if (input.getPmcid() == null || input.getPmcid().trim().isEmpty()) {
            throw new InvalidInputException("pmcid", null, "is required");
        }
        String pmcid = input.getPmcid().trim();

        // Strip "PMC" prefix if present for the API call
        String numericId = pmcid.startsWith("PMC") ? pmcid.substring(3) : pmcid;
        try {
            CloseableHttpClient httpClient = resources.getHttpClient();

            // Fetch JATS XML from PMC
            context.publish("Fetching article from PMC", 20);
            StringBuilder url = new StringBuilder(EFETCH_URL);
            url.append("?db=pmc");
            url.append("&id=").append(URLEncoder.encode(numericId, StandardCharsets.UTF_8));
            url.append("&rettype=xml");
            if (API_KEY != null) {
                url.append("&api_key=").append(API_KEY);
            }
            HttpReply reply;
            try {
                reply = httpClient.execute(new HttpGet(url.toString()), HttpReply.reader());
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.wrapWithContext(e, "PubMed Central", "pmcid", pmcid, "fetching the article");
            }
            if (reply.status() != 200) {
                throw new ExternalServiceException("PubMed Central", "efetch failed with HTTP " + reply.status() + " for " + pmcid);
            }

            // Parse JATS XML
            context.publish("Parsing article content", 60);
            String body = reply.body();

            // Article not in PMC - identifier doesn't resolve
            if (body.contains("<ERROR>") || body.contains("Cannot process ID")) {
                throw new ResourceNotFoundException("PMC article", pmcid);
            }
            Document doc = SafeXml.parse(body);
            String title = extractTitle(doc);
            String fullText = extractBodyText(doc);
            PMCArticle article = new PMCArticle();
            article.setPmcid(pmcid);
            article.setTitle(title);
            article.setFullText(fullText);
            article.setSource("pmc");
            context.publish("Complete", 100);
            PMCFullTextOutput output = new PMCFullTextOutput();
            output.setArticle(article);
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
     * Extracts the article title from JATS XML.
     */
    private String extractTitle(Document doc) {
        NodeList titleNodes = doc.getElementsByTagName("article-title");
        if (titleNodes.getLength() > 0) {
            return titleNodes.item(0).getTextContent();
        }
        return null;
    }

    /**
     * Extracts concatenated body text from JATS XML sections.
     */
    private String extractBodyText(Document doc) {
        StringBuilder text = new StringBuilder();

        // Extract abstract
        NodeList abstractNodes = doc.getElementsByTagName("abstract");
        for (int i = 0; i < abstractNodes.getLength(); i++) {
            String abstractText = extractSectionText((Element) abstractNodes.item(i));
            if (!abstractText.isEmpty()) {
                text.append("ABSTRACT\n\n");
                text.append(abstractText);
                text.append("\n\n");
            }
        }

        // Extract body sections
        NodeList bodyNodes = doc.getElementsByTagName("body");
        for (int i = 0; i < bodyNodes.getLength(); i++) {
            Element bodyElement = (Element) bodyNodes.item(i);
            NodeList secNodes = bodyElement.getElementsByTagName("sec");
            if (secNodes.getLength() > 0) {
                for (int j = 0; j < secNodes.getLength(); j++) {
                    Element sec = (Element) secNodes.item(j);

                    // Only process top-level sections (not nested)
                    if (sec.getParentNode() == bodyElement) {
                        String sectionText = extractSectionText(sec);
                        if (!sectionText.isEmpty()) {
                            text.append(sectionText);
                            text.append("\n\n");
                        }
                    }
                }
            }
            else {
                // No sections - extract all content directly from body
                String bodyText = extractSectionText(bodyElement);
                if (!bodyText.isEmpty()) {
                    text.append(bodyText);
                    text.append("\n\n");
                }
            }
        }
        return text.toString().trim();
    }

    /**
     * Extracts text from a JATS section element by walking all direct children in document order.
     * Handles paragraphs, tables, figures, lists, and nested sections.
     */
    private String extractSectionText(Element section) {
        StringBuilder text = new StringBuilder();

        // Walk direct child elements in document order
        NodeList children = section.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element childEl)) {
                continue;
            }
            String tag = childEl.getTagName();
            switch (tag) {
                case "title" -> {
                    String title = childEl.getTextContent().trim();
                    if (!title.isEmpty()) {
                        text.append(title.toUpperCase());
                        text.append("\n\n");
                    }
                }
                case "p" -> {
                    String para = childEl.getTextContent().trim();
                    if (!para.isEmpty()) {
                        text.append(para);
                        text.append("\n\n");
                    }
                }
                case "table-wrap" -> {
                    String tableText = extractTable(childEl);
                    if (!tableText.isEmpty()) {
                        text.append(tableText);
                        text.append("\n\n");
                    }
                }
                case "fig" -> {
                    String caption = extractCaption(childEl);
                    if (!caption.isEmpty()) {
                        text.append("[Figure] ").append(caption);
                        text.append("\n\n");
                    }
                }
                case "list" -> {
                    String listText = extractList(childEl);
                    if (!listText.isEmpty()) {
                        text.append(listText);
                        text.append("\n\n");
                    }
                }
                case "sec" -> {
                    String subText = extractSectionText(childEl);
                    if (!subText.isEmpty()) {
                        text.append("\n");
                        text.append(subText);
                        text.append("\n");
                    }
                }
                case "supplementary-material", "disp-formula", "boxed-text" -> {
                    String content = childEl.getTextContent().trim();
                    if (!content.isEmpty()) {
                        text.append(content);
                        text.append("\n\n");
                    }
                }
            }
        }
        return text.toString().trim();
    }

    /**
     * Extracts a JATS table-wrap into TSV-like text.
     * Renders caption + header row + data rows separated by tabs.
     */
    private String extractTable(Element tableWrap) {
        StringBuilder text = new StringBuilder();

        // Table caption/label
        String caption = extractCaption(tableWrap);
        if (!caption.isEmpty()) {
            text.append("[Table] ").append(caption).append("\n");
        }

        // Find the <table> element inside table-wrap
        NodeList tables = tableWrap.getElementsByTagName("table");
        if (tables.getLength() == 0) {
            // Some tables use <alternatives> or are text-only
            String fallback = tableWrap.getTextContent().trim();
            if (!fallback.isEmpty() && text.isEmpty()) {
                text.append(fallback);
            }
            return text.toString().trim();
        }
        Element table = (Element) tables.item(0);

        // Extract thead rows
        NodeList theadNodes = table.getElementsByTagName("thead");
        if (theadNodes.getLength() > 0) {
            extractRows((Element) theadNodes.item(0), text);
        }

        // Extract tbody rows
        NodeList tbodyNodes = table.getElementsByTagName("tbody");
        if (tbodyNodes.getLength() > 0) {
            extractRows((Element) tbodyNodes.item(0), text);
        }
        else {
            // Rows directly in <table>
            extractRows(table, text);
        }
        return text.toString().trim();
    }

    /**
     * Extracts rows from a thead/tbody/table element into tab-separated lines.
     */
    private void extractRows(Element container, StringBuilder text) {
        NodeList rows = container.getElementsByTagName("tr");
        for (int r = 0; r < rows.getLength(); r++) {
            Node rowNode = rows.item(r);
            if (rowNode.getParentNode() != container) {
                continue;
            }
            Element row = (Element) rowNode;
            NodeList cells = row.getChildNodes();
            boolean first = true;
            for (int c = 0; c < cells.getLength(); c++) {
                Node cellNode = cells.item(c);
                if (!(cellNode instanceof Element cellEl)) {
                    continue;
                }
                String cellTag = cellEl.getTagName();
                if (!"th".equals(cellTag) && !"td".equals(cellTag)) {
                    continue;
                }
                if (!first) {
                    text.append("\t");
                }
                text.append(cellEl.getTextContent().trim());
                first = false;
            }
            text.append("\n");
        }
    }

    /**
     * Extracts caption text from a JATS element (table-wrap or fig).
     */
    private String extractCaption(Element element) {
        StringBuilder caption = new StringBuilder();
        NodeList labelNodes = element.getElementsByTagName("label");
        for (int i = 0; i < labelNodes.getLength(); i++) {
            if (labelNodes.item(i).getParentNode() == element) {
                caption.append(labelNodes.item(i).getTextContent().trim());
                break;
            }
        }
        NodeList captionNodes = element.getElementsByTagName("caption");
        for (int i = 0; i < captionNodes.getLength(); i++) {
            if (captionNodes.item(i).getParentNode() == element) {
                String capText = captionNodes.item(i).getTextContent().trim();
                if (!capText.isEmpty()) {
                    if (caption.length() > 0) {
                        caption.append(": ");
                    }
                    caption.append(capText);
                }
                break;
            }
        }
        return caption.toString().trim();
    }

    /**
     * Extracts list items from a JATS list element.
     */
    private String extractList(Element list) {
        StringBuilder text = new StringBuilder();
        NodeList items = list.getElementsByTagName("list-item");
        for (int i = 0; i < items.getLength(); i++) {
            if (items.item(i).getParentNode() == list) {
                String item = items.item(i).getTextContent().trim();
                if (!item.isEmpty()) {
                    text.append("- ").append(item).append("\n");
                }
            }
        }
        return text.toString();
    }
}
