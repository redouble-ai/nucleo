/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.ext.patent.ratelimiters.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.secrets.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.slf4j.*;

import java.util.regex.*;

/**
 * Typed client for the USPTO Open Data Portal (ODP) REST API.
 *
 * <p>Authenticates via the {@code X-API-KEY} header. Supports GET with query
 * parameters and POST with JSON bodies (for the opensearch-style search DSL).
 *
 * <p>ODP enforces burst=1 (one concurrent request per key), so callers must
 * use {@link UsptoOdpRateLimiter} to serialize access.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpClient extends AbstractApiClient {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpClient.class);
    /** The {@code <abstract>} element of a split grant XML. */
    public static final Pattern ABSTRACT = Pattern.compile("<abstract[^>]*>(.*?)</abstract>", Pattern.DOTALL);
    /** The {@code <claims>} element of a split grant XML. */
    public static final Pattern CLAIMS = Pattern.compile("<claims[^>]*>(.*?)</claims>", Pattern.DOTALL);
    /** The {@code <description>} element of a split grant XML. */
    public static final Pattern DESCRIPTION = Pattern.compile("<description[^>]*>(.*?)</description>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    /** The id of the USPTO Open Data Portal API key in the deployment's secret store. */
    public static final String SECRET_ID = "myodp-api-key";
    private static final String API_KEY;
    static {
        Credential odp = Secrets.configured().find(SECRET_ID);
        if (odp == null) {
            log.warn("USPTO ODP API key not configured - ODP tools will not function. Provide {}", Secrets.configured().describe(SECRET_ID));
        }
        API_KEY = odp == null ? null : odp.secret();
    }
    @Override
    protected String getServiceName() {
        return "USPTO ODP";
    }
    @Override
    protected String getBaseUrl() {
        return "https://api.uspto.gov/api/v1/";
    }
    @Override
    protected void decorateRequest(HttpUriRequestBase request) {
        authenticate(request);
        request.setHeader("Accept", "application/json");
    }

    /**
     * Signs a request with the deployment's ODP key. The API's own calls get this through
     * {@link #decorateRequest}; the split grant XML the associated-documents endpoint points
     * at is downloaded outside this client, and that request is signed the same way.
     */
    public void authenticate(HttpUriRequestBase request) {
        if (API_KEY != null) {
            request.setHeader("X-API-KEY", API_KEY);
        }
    }

    /**
     * Issues a GET against an ODP endpoint with query parameters.
     * Fetch-by-ID semantics: propagates {@link Http404Exception} when not found.
     */
    public JsonNode get(String endpoint, String queryParams) throws LLMReadableCheckedException {
        String path = endpoint + "?" + queryParams;
        log.debug("USPTO ODP request: {}{}", getBaseUrl(), path);
        return get(path);
    }

    /**
     * Issues a POST with JSON body against an ODP endpoint.
     * Fetch-by-ID semantics: propagates 404 as {@link Http404Exception}.
     */
    public JsonNode postJson(String endpoint, ObjectNode body) throws LLMReadableCheckedException {
        return postForJson(endpoint, body);
    }

    /**
     * Search variant of {@link #postJson}: returns {@code null} when the
     * upstream responds with 404 (no matches). ODP returns 404 with
     * "No matching records found" for empty search results.
     */
    public JsonNode searchPostJson(String endpoint, ObjectNode body) throws LLMReadableCheckedException {
        try {
            return postForJson(endpoint, body);
        }
        catch (Http404Exception e) {
            return null;
        }
    }

    /**
     * Search variant of {@link #get(String, String)}: returns {@code null}
     * when the upstream responds with 404 (no matches).
     */
    public JsonNode searchGet(String endpoint, String queryParams) throws LLMReadableCheckedException {
        try {
            return get(endpoint, queryParams);
        }
        catch (Http404Exception e) {
            return null;
        }
    }

    /** A patent number as ODP indexes it: commas and whitespace removed. Null stays null. */
    public static String normalizePatentNumber(String raw) {
        return raw == null ? null : raw.trim().replaceAll("[,\\s]", "");
    }

    /**
     * The file wrapper of the application that issued as this patent, carrying its
     * application number text and application metadata. Fetch-by-ID semantics: a patent
     * ODP does not know is a {@link ResourceNotFoundException}.
     */
    public JsonNode findApplicationByPatentNumber(String patentNumber) throws LLMReadableCheckedException {
        ObjectNode body = NucleoJsonSerializer.createObjectNode();
        body.put("q", "applicationMetaData.patentNumber:" + patentNumber);
        ObjectNode pagination = NucleoJsonSerializer.createObjectNode();
        pagination.put("offset", 0);
        pagination.put("limit", 1);
        body.set("pagination", pagination);
        ArrayNode fields = NucleoJsonSerializer.createArrayNode();
        fields.add("applicationNumberText");
        fields.add("applicationMetaData");
        body.set("fields", fields);
        JsonNode response = searchPostJson("patent/applications/search", body);
        JsonNode dataBag = response == null ? null : response.path("patentFileWrapperDataBag");
        if (dataBag == null || !dataBag.isArray() || dataBag.isEmpty()) {
            throw new ResourceNotFoundException("US patent", patentNumber);
        }
        return dataBag.get(0);
    }

    /**
     * The application number a tool works on: the one given, or the one resolved from the
     * patent number when only that was given. Neither present is the model's mistake to
     * correct.
     */
    public String applicationNumberFor(String patentNumber, String applicationNumber) throws LLMReadableCheckedException {
        String patNum = normalizePatentNumber(patentNumber);
        if ((patNum == null || patNum.isEmpty()) && (applicationNumber == null || applicationNumber.trim().isEmpty())) {
            throw new InvalidInputException("patentNumber", null, "Either patentNumber or applicationNumber is required");
        }
        if (applicationNumber != null && !applicationNumber.trim().isEmpty()) {
            return applicationNumber;
        }
        return applicationNumber(findApplicationByPatentNumber(patNum), patNum);
    }

    /** The application number of a file wrapper; a wrapper without one is a service fault. */
    public String applicationNumber(JsonNode wrapper, String patentNumber) throws ExternalServiceException {
        String appNumber = wrapper.path("applicationNumberText").asText(null);
        if (appNumber == null) {
            throw new ExternalServiceException("USPTO ODP", "No application number returned for patent " + patentNumber);
        }
        return appNumber;
    }

    /**
     * Where the associated-documents endpoint keeps an application's split grant XML, or the
     * pre-grant publication's when no grant has issued. Null when ODP lists neither.
     */
    public String grantXmlUri(String applicationNumber) throws LLMReadableCheckedException {
        JsonNode assocDocs = get("patent/applications/" + applicationNumber + "/associated-documents", "");
        JsonNode dataBag = assocDocs.path("patentFileWrapperDataBag");
        if (!dataBag.isArray() || dataBag.isEmpty()) {
            return null;
        }
        String xmlUri = dataBag.get(0).path("grantDocumentMetaData").path("fileLocationURI").asText(null);
        if (xmlUri == null) {
            xmlUri = dataBag.get(0).path("pgpubDocumentMetaData").path("fileLocationURI").asText(null);
        }
        return xmlUri;
    }

    /**
     * Downloads a grant XML the associated-documents endpoint pointed at, signed with the
     * deployment's key. A failure of the download itself is wrapped with the service and the
     * step, so it reaches the model as the service's fault and never as an internal error; a
     * status outside 2xx is this client's own classification.
     */
    public String downloadXml(String xmlUri) throws LLMReadableCheckedException {
        HttpGet request = new HttpGet(xmlUri);
        authenticate(request);
        HttpReply reply;
        try {
            reply = httpClient.execute(request, HttpReply.reader());
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.wrapWithContext(e, getServiceName(), "grantXmlUri", xmlUri, "downloading the grant XML");
        }
        if (reply.status() < 200 || reply.status() >= 300) {
            throw new ExternalServiceException(getServiceName(), "Failed to download grant XML (HTTP " + reply.status() + "): " + xmlUri);
        }
        return reply.body();
    }

    /** One section of a grant XML as plain text, tags stripped and whitespace collapsed; null when absent or empty. */
    public static String sectionText(String xml, Pattern section) {
        if (xml == null) {
            return null;
        }
        Matcher m = section.matcher(xml);
        if (!m.find()) {
            return null;
        }
        String text = TAG.matcher(m.group(1)).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }
}
