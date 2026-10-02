/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.tools.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.slf4j.*;

import java.time.*;

/**
 * Fetches web page content from a URL.
 *
 * <p>Retrieves the HTML content of a web page and extracts readable text.
 * Returns a WebPageArtifact with the content field populated.
 *
 * <p>Uses Jsoup to parse HTML and extract main content, removing scripts,
 * styles, and navigation elements.
 *
 * <p>The tool itself refuses no address - it fetches what it is given.
 * {@link ai.redouble.nucleo.tools.guardrails.UrlGuardrail}, declared as an INPUT content
 * guardrail, is where private and reserved ranges are refused; external-facing deployments
 * subclass it for domain allowlists or other restrictions.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@DisplayName(value = "Fetch Web Page", action = "Fetching Web Page")
@ToolName("web_fetch")
@ToolDescription(value = "Fetch the text content of a web page. Use this after search to get full page content for analysis.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class WebFetchTool extends AbstractTool<WebFetchInput, WebFetchOutput> {
    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int DEFAULT_MAX_LENGTH = 50000;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; RedoubleBot/1.0; +https://redouble.ai)";

    public WebFetchTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(30));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public WebFetchOutput execute(JobResources resources, JobContext<WebFetchOutput> context) throws LLMReadableCheckedException {
        try {
            String url = input.getUrl();
            int maxLength = input.getMaxLength() != null ? input.getMaxLength() : DEFAULT_MAX_LENGTH;
            context.publish("Fetching: " + url, 10);
            CloseableHttpClient httpClient = resources.getHttpClient();
            HttpGet request = new HttpGet(url);
            request.setHeader("User-Agent", USER_AGENT);
            request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            HttpReply reply;
            try {
                reply = httpClient.execute(request, HttpReply.reader());
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.wrapWithContext(e, "web:" + url, "url", url, "fetching the page");
            }
            if (reply.status() != 200) {
                throwForHttpStatus(reply.status(), url);
            }
            String html = reply.body();
            context.publish("Parsing content", 60);
            Document doc = Jsoup.parse(html, url);
            String title = doc.title();
            String content = extractReadableContent(doc);
            if (content.length() > maxLength) {
                content = content.substring(0, maxLength) + "\n\n[Content truncated at " + maxLength + " characters]";
            }
            String description = extractDescription(doc);
            context.publish("Complete", 100);
            WebPageArtifact artifact = new WebPageArtifact();
            artifact.setUrl(url);
            artifact.setTitle(title);
            artifact.setDescription(description);
            artifact.setContent(content);
            artifact.setSource("fetch");
            WebFetchOutput output = new WebFetchOutput();
            output.setPage(artifact);
            return output;
        }
        catch (LLMReadableCheckedException e) {
            throw e;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    private void throwForHttpStatus(int statusCode, String url) throws LLMReadableCheckedException {
        log.warn("WebFetchTool: HTTP {} for URL: {}", statusCode, url);
        switch (statusCode) {
            case 400, 422 -> throw new InvalidInputException("url", url, "Server returned HTTP " + statusCode);
            case 401, 403 -> throw new UnauthorizedException("web:" + url, "HTTP " + statusCode);
            case 404 -> throw new ResourceNotFoundException("webpage", url);
            default -> throw new ExternalServiceException("web:" + url, "HTTP " + statusCode);
        }
    }

    private String extractReadableContent(Document doc) {
        // Remove non-content elements
        doc.select("script, style, nav, header, footer, aside, form, iframe, noscript").remove();
        // Try to find main content area
        Element mainContent = doc.selectFirst("main, article, [role=main], .content, #content, .post, .article");
        if (mainContent != null) {
            return cleanText(mainContent.text());
        }
        // Fallback to body (Jsoup always synthesizes one)
        return cleanText(doc.body().text());
    }

    private String extractDescription(Document doc) {
        // Try meta description
        Element metaDesc = doc.selectFirst("meta[name=description]");
        if (metaDesc != null) {
            String content = metaDesc.attr("content");
            if (!content.isEmpty()) {
                return content;
            }
        }
        // Try og:description
        Element ogDesc = doc.selectFirst("meta[property=og:description]");
        if (ogDesc != null) {
            String content = ogDesc.attr("content");
            if (!content.isEmpty()) {
                return content;
            }
        }
        // Fallback to first paragraph
        Element firstP = doc.selectFirst("p");
        if (firstP != null) {
            String text = firstP.text();
            if (text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }
            return text;
        }
        return null;
    }

    private String cleanText(String text) {
        if (text == null) {
            return "";
        }
        // Normalize whitespace
        return text.replaceAll("\\s+", " ").trim();
    }

}
