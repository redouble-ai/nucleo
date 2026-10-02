/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.ratelimiters.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.secrets.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.xml.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.*;
import org.slf4j.*;

import javax.xml.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Typed client for the EPO Open Patent Services (OPS) REST API.
 *
 * <p>Encapsulates the full EPO quirks package:
 * <ul>
 *   <li><b>OAuth 2.0 token refresh</b> - obtains a bearer token via client-credentials
 *       grant against the auth endpoint, re-fetches on 401, cached for ~19 minutes.
 *   <li><b>Robot detection</b> - EPO returns HTTP 403 with {@code CLIENT.RobotDetected}
 *       in the body when it thinks the caller is a bot. {@link #classify} maps
 *       this specific response to {@link Http429Exception} so the dispatcher signals
 *       the shared {@link EPORateLimiter}, not a generic auth failure.
 *   <li><b>X-Throttling-Control side-channel</b> - every successful response carries
 *       a per-service health status (green/yellow/red/black). {@link #postProcessResponse}
 *       parses it and nudges the rate limiter via {@code onAdvisoryPressure}/{@code onSuccess}
 *       so we slow down BEFORE the upstream starts rejecting.
 *   <li><b>XML responses</b> - EPO returns XML; {@link #get} converts the body to a
 *       {@link JsonNode} for uniform navigation at the tool layer.
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class EPOClient extends AbstractApiClient {
    private static final Logger log = LoggerFactory.getLogger(EPOClient.class);
    private static final String BASE_URL = "https://ops.epo.org/3.2";
    private static final String AUTH_URL = "https://ops.epo.org/3.2/auth/accesstoken";
    private static final long TOKEN_EXPIRY_MS = 19 * 60 * 1000; // 19 min (1 min margin from 20 min actual)
    private static final XmlMapper XML_MAPPER;
    static {
        // Disable external entity resolution to prevent XXE via malicious API responses
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_MAPPER = new XmlMapper(new com.fasterxml.jackson.dataformat.xml.XmlFactory(inputFactory));
    }
    /** The id of the EPO OPS credential in the deployment's secret store: user is the consumer key, secret the consumer secret. */
    public static final String SECRET_ID = "epo-api-key";
    private static final String CONSUMER_KEY;
    private static final String CONSUMER_SECRET;
    static {
        Credential epo = Secrets.configured().find(SECRET_ID);
        if (epo == null) {
            log.warn("EPO OPS credentials not configured - EPO tools will not function. Provide {} with user = consumer key and secret = consumer secret",
                    Secrets.configured().describe(SECRET_ID));
        }
        CONSUMER_KEY = epo == null ? null : epo.user();
        CONSUMER_SECRET = epo == null ? null : epo.secret();
    }
    private String accessToken;
    private long tokenExpiresAt;

    @Override
    protected String getServiceName() {
        return "EPO OPS";
    }

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected void decorateRequest(HttpUriRequestBase request) {
        if (accessToken != null) {
            request.setHeader("Authorization", "Bearer " + accessToken);
        }
        request.setHeader("Accept", "application/xml");
    }

    /**
     * Maps EPO's robot-detection response ({@code HTTP 403} with
     * {@code CLIENT.RobotDetected} in the body) to {@link Http429Exception} so
     * the dispatcher signals the rate limiter instead of surfacing a generic
     * auth error. Other responses fall through to the normal status-code
     * mapping.
     */
    @Override
    protected LLMReadableCheckedException classify(int statusCode, Header[] headers, String responseBody) {
        if (statusCode == 403 && isRobotDetected(responseBody)) {
            return new Http429Exception(getServiceName(), "robot-detection", responseBody);
        }
        return null;
    }

    /**
     * Inspects EPO's {@code X-Throttling-Control} header on every response and
     * routes per-service status signals to the matching {@link EPORateLimiter}
     * bucket. The header format is:
     *
     * <pre>{@code <overall>, <service>=<color>:<quota>, <service>=<color>:<quota>, ...}</pre>
     *
     * For example: {@code busy (images=green:100, inpadoc=green:45, other=green:1000,
     * retrieval=green:100, search=black:0)}. Each per-service token is parsed
     * into {@link EPOService} + color. Non-green colors call
     * {@link EPORateLimiter#onAdvisoryPressure(EPOService)} on just that bucket
     * (bumps throttle, does not affect circuit state). Green calls
     * {@link EPORateLimiter#onAdvisorySuccess(EPOService)} to aid per-bucket
     * recovery without depending on signaling from a successful job completion.
     */
    @Override
    protected void postProcessResponse(int statusCode, Header[] headers, String responseBody) {
        String throttling = null;
        for (Header h : headers) {
            if ("X-Throttling-Control".equalsIgnoreCase(h.getName())) {
                throttling = h.getValue();
                break;
            }
        }
        if (throttling == null) {
            return;
        }
        EPORateLimiter limiter = RateLimiterFactory.getInstance().getRateLimiter(EPORateLimiter.class);
        if (limiter == null) {
            return; // Factory shutting down
        }
        boolean anyPressure = false;
        for (String token : throttling.split("[,()]")) {
            String trimmed = token.trim();
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue; // overall status (idle/busy/overloaded) - no per-service bucket
            }
            EPOService service = parseService(trimmed.substring(0, eq));
            if (service == null) {
                continue;
            }
            String valuePart = trimmed.substring(eq + 1);
            String[] colorAndQuota = valuePart.split(":", 2);
            String color = colorAndQuota[0].toLowerCase();
            switch (color) {
                case "green" -> limiter.onAdvisorySuccess(service);
                case "yellow", "red", "black" -> {
                    anyPressure = true;
                    limiter.onAdvisoryPressure(service);
                }
                default -> {} // unknown color - ignore
            }
        }
        if (anyPressure) {
            log.warn("EPO OPS throttling: {}", throttling);
        }
    }

    private static EPOService parseService(String name) {
        return switch (name.trim().toLowerCase()) {
            case "search" -> EPOService.SEARCH;
            case "retrieval" -> EPOService.RETRIEVAL;
            case "inpadoc" -> EPOService.INPADOC;
            case "images" -> EPOService.IMAGES;
            case "other" -> EPOService.OTHER;
            default -> null;
        };
    }

    /**
     * Fetches a resource from EPO and returns the parsed JSON view of the
     * underlying XML. Fetch-by-ID semantics - propagates
     * {@link Http404Exception} (a {@link ResourceNotFoundException}) when
     * the resource does not exist. Use {@link #searchGet(String)} for
     * search-style calls where empty is a valid outcome.
     */
    public JsonNode get(String path) throws LLMReadableCheckedException {
        String xml = getAsXml(path);
        try {
            return xmlToJson(xml);
        }
        catch (IOException e) {
            throw new ExternalServiceException(getServiceName(), "Failed to parse XML response: " + e.getMessage(), e);
        }
    }

    /**
     * Search variant of {@link #get(String)}: returns {@code null} when the
     * upstream responds with 404 (no matches), propagates every other error.
     */
    public JsonNode searchGet(String path) throws LLMReadableCheckedException {
        try {
            return get(path);
        }
        catch (Http404Exception e) {
            return null;
        }
    }

    /**
     * Fetches a resource from EPO and returns the raw XML body. Handles OAuth
     * token refresh transparently: on a 401, refreshes the access token and
     * retries once. Propagates {@link Http404Exception} when the resource
     * does not exist.
     */
    public String getAsXml(String path) throws LLMReadableCheckedException {
        ensureAccessToken();
        try {
            return executeXmlGet(path);
        }
        catch (Http401Exception e) {
            log.info("EPO token expired, refreshing");
            refreshAccessToken();
            return executeXmlGet(path);
        }
    }

    private String executeXmlGet(String path) throws LLMReadableCheckedException {
        HttpGet request = new HttpGet(buildUrl(path));
        return executeRequest(request, path, body -> body);
    }

    private synchronized void ensureAccessToken() throws LLMReadableCheckedException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return;
        }
        refreshAccessToken();
    }

    private synchronized void refreshAccessToken() throws LLMReadableCheckedException {
        if (CONSUMER_KEY == null || CONSUMER_SECRET == null) {
            throw new UnauthorizedException(getServiceName(),
                "Credentials not configured. Provide " + Secrets.configured().describe(SECRET_ID) + " with user = consumer key and secret = consumer secret.");
        }
        String credentials = CONSUMER_KEY + ":" + CONSUMER_SECRET;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpPost authRequest = new HttpPost(AUTH_URL);
        authRequest.setHeader("Authorization", "Basic " + encodedCredentials);
        authRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");
        authRequest.setEntity(new StringEntity("grant_type=client_credentials", ContentType.APPLICATION_FORM_URLENCODED));
        try {
            HttpReply reply = httpClient.execute(authRequest, HttpReply.reader());
            int statusCode = reply.status();
            String responseBody = reply.body();
            if (statusCode != 200) {
                throw new UnauthorizedException(getServiceName(),
                    "OAuth failed (HTTP " + statusCode + "): " + truncate(responseBody));
            }
            JsonNode tokenResponse = NucleoJsonSerializer.readTree(responseBody);
            accessToken = tokenResponse.path("access_token").asText(null);
            if (accessToken == null) {
                throw new UnauthorizedException(getServiceName(), "OAuth response missing access_token");
            }
            tokenExpiresAt = System.currentTimeMillis() + TOKEN_EXPIRY_MS;
        }
        catch (IOException e) {
            throw new ExternalServiceException(getServiceName(), "OAuth request failed: " + e.getMessage(), e);
        }
    }

    private static boolean isRobotDetected(String body) {
        return body != null && (body.contains("RobotDetected") || body.contains("CLIENT.Robot"));
    }

    /** Converts an EPO XML response to a Jackson {@link JsonNode} for uniform navigation. */
    public static JsonNode xmlToJson(String xml) throws IOException {
        return XML_MAPPER.readTree(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Strips embedded HTML/XML tags from EPO text content. EPO full-text
     * responses often contain inline HTML ({@code img}, {@code sup},
     * {@code sub}, {@code b}, {@code i}) that is meaningless as plain text.
     */
    public static String stripHtml(String text) {
        if (text == null) return null;
        return org.jsoup.Jsoup.parse(text).text();
    }

    /**
     * A document-id as a dotted DOCDB number, {@code EP.1000000.B1}: country, doc-number and
     * kind joined by dots, each part only when present. The one format every EPO tool prints
     * and every tool takes by default, so a number read from any output goes straight back
     * into any input. Null when the id carries neither country nor number.
     */
    public static String docdbNumber(JsonNode docId) {
        String country = docId.path("country").asText(null);
        String docNumber = docId.path("doc-number").asText(null);
        if (country == null && docNumber == null) {
            return null;
        }
        StringJoiner number = new StringJoiner(".");
        for (String part : new String[] {country, docNumber, docId.path("kind").asText(null)}) {
            if (part != null) {
                number.add(part);
            }
        }
        return number.toString();
    }

    // null-to-empty is intentional: used only for error-message formatting.
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
