/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.apache.hc.client5.http.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.pool.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client base every per-service client extends, driven through a local server: each
 * request method sends what it promises and returns what it promises; every request passes
 * {@code buildUrl}, {@code decorateRequest}, {@code postProcessResponse} on every response
 * whatever its status, then {@code classify}, whose non-null answer replaces the status mapping;
 * any status from 300 up is an {@code HttpErrorResponse} carrier (a 3xx only when the request
 * disabled redirects; the client follows them otherwise); a service that could not be reached
 * and a 2xx body the parser refuses are each an {@code ExternalServiceException} with no status;
 * every failure names the endpoint as its path alone and carries nothing of the request; the
 * transport is the shared pool unless a client is injected; {@code encode} is UTF-8.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class AbstractApiClientTest {

    private static HttpServer server;
    private static String base;
    private static int contexts = 0;

    /** What the local server saw of one request. */
    record Seen(String method, URI uri, Map<String, String> headers, String body) {
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    /** Scripts one answer under a fresh path, records what arrives, and returns the root under which the path is served. */
    static synchronized String script(int status, Map<String, String> headers, String body, List<Seen> seen) {
        String root = "/case" + (++contexts);
        server.createContext(root, exchange -> {
            Map<String, String> requestHeaders = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> requestHeaders.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI(), requestHeaders, requestBody));
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return base + root;
    }

    static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    /** A per-service client with every hook observable and the protected request methods exposed. */
    static class Probe extends AbstractApiClient {
        final String baseUrl;
        String querySuffix;
        String decorationHeader;
        boolean followRedirects = true;
        BiFunction<String, Integer, LLMReadableCheckedException> classifier;
        final List<Integer> postProcessed = new ArrayList<>();
        final List<String> postProcessedBodies = new ArrayList<>();

        Probe(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        protected String getServiceName() {
            return "probe";
        }

        @Override
        protected String getBaseUrl() {
            return baseUrl;
        }

        @Override
        protected String buildUrl(String path) {
            return querySuffix == null ? super.buildUrl(path) : super.buildUrl(path) + querySuffix;
        }

        @Override
        protected void decorateRequest(HttpUriRequestBase request) {
            if (decorationHeader != null) {
                request.setHeader("X-Decoration", decorationHeader);
            }
            if (!followRedirects) {
                request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            }
        }

        @Override
        protected LLMReadableCheckedException classify(String endpoint, int statusCode, Header[] headers, String responseBody) {
            return classifier == null ? super.classify(endpoint, statusCode, headers, responseBody) : classifier.apply(endpoint, statusCode);
        }

        @Override
        protected void postProcessResponse(int statusCode, Header[] headers, String responseBody) {
            postProcessed.add(statusCode);
            postProcessedBodies.add(responseBody);
        }

        JsonNode getJson(String path) throws LLMReadableCheckedException {
            return get(path);
        }

        String text(String path, String accept) throws LLMReadableCheckedException {
            return getText(path, accept);
        }

        <T> T postTyped(String endpoint, Object body, Class<T> type) throws LLMReadableCheckedException {
            return post(endpoint, body, type);
        }

        JsonNode postJson(String endpoint, Object body) throws LLMReadableCheckedException {
            return postForJson(endpoint, body);
        }

        HttpReply postRaw(String endpoint, String json) throws LLMReadableCheckedException {
            return postForReply(endpoint, json);
        }
    }

    public static class Echo {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void getSendsAJsonAcceptOnThePathAndReturnsTheTree() throws Exception {
        List<Seen> seen = new ArrayList<>();
        Probe probe = new Probe(script(200, JSON, "{\"name\":\"ada\"}", seen));
        assertEquals("ada", probe.getJson("/thing").get("name").asText(), "the body is the parsed tree");
        assertEquals("GET", seen.get(0).method());
        assertEquals("application/json", seen.get(0).headers().get("accept"), "get asks for JSON");
        assertTrue(seen.get(0).uri().getPath().endsWith("/thing"), "the path is appended to the base URL");
    }

    @Test
    void getTextSendsTheCallersAcceptAndReturnsTheRawBody() throws Exception {
        List<Seen> seen = new ArrayList<>();
        Probe probe = new Probe(script(200, Map.of("Content-Type", "text/csv"), "a,b\n1,2", seen));
        assertEquals("a,b\n1,2", probe.text("/rows", "text/csv"), "the body comes back as it was, unparsed");
        assertEquals("text/csv", seen.get(0).headers().get("accept"), "getText sends the Accept the caller named");
    }

    @Test
    void postWritesTheBodyAsJsonAndReadsTheAnswerAsTheType() throws Exception {
        List<Seen> seen = new ArrayList<>();
        Probe probe = new Probe(script(200, JSON, "{\"name\":\"reply\"}", seen));
        Echo sent = new Echo();
        sent.setName("sent");
        assertEquals("reply", probe.postTyped("/echo", sent, Echo.class).getName(), "the answer is read as the type");
        assertEquals("POST", seen.get(0).method());
        assertTrue(seen.get(0).headers().get("content-type").startsWith("application/json"), "the body goes as JSON");
        assertEquals("{\"name\":\"sent\"}", seen.get(0).body().replaceAll("\\s", ""), "the body is the object written as JSON");
        assertEquals("reply", probe.postJson("/echo", sent).get("name").asText(), "postForJson answers the tree");
    }

    @Test
    void postForReplyCarriesTheJsonAsWrittenAndReturnsTheWholeReply() throws Exception {
        List<Seen> seen = new ArrayList<>();
        Probe probe = new Probe(script(200, Map.of("Content-Type", "application/json", "X-Ratelimit-Remaining", "41"), "{\"ok\":true}", seen));
        HttpReply reply = probe.postRaw("/chat", "{\"raw\": 1}");
        assertEquals(200, reply.status());
        assertEquals("{\"ok\":true}", reply.body());
        assertEquals("41", reply.header("x-ratelimit-remaining"), "the headers are the reply's, for a client that reads account facts off them");
        assertEquals("{\"raw\": 1}", seen.get(0).body(), "the JSON goes as written");
        assertEquals("application/json", seen.get(0).headers().get("accept"));
    }

    @Test
    void everyRequestMethodPassesBuildUrlAndDecorateRequest() throws Exception {
        List<Seen> seen = new ArrayList<>();
        Probe probe = new Probe(script(200, JSON, "{\"name\":\"x\"}", seen));
        probe.querySuffix = "?api_key=k";
        probe.decorationHeader = "stamped";
        Echo body = new Echo();
        probe.getJson("/a");
        probe.text("/b", "text/plain");
        probe.postTyped("/c", body, Echo.class);
        probe.postJson("/d", body);
        probe.postRaw("/e", "{}");
        assertEquals(5, seen.size());
        for (Seen request : seen) {
            assertEquals("api_key=k", request.uri().getQuery(), "buildUrl's override shapes the target of " + request.method() + " " + request.uri().getPath());
            assertEquals("stamped", request.headers().get("x-decoration"), "decorateRequest's header reaches the wire on " + request.uri().getPath());
        }
    }

    /** A per-service client that overrides only the endpoint-less form of the classification hook. */
    static class EndpointlessProbe extends Probe {
        final List<Integer> classified = new ArrayList<>();

        EndpointlessProbe(String baseUrl) {
            super(baseUrl);
        }

        @Override
        protected LLMReadableCheckedException classify(int statusCode, Header[] headers, String responseBody) {
            classified.add(statusCode);
            return new ExternalServiceException("probe", "endpointless " + statusCode);
        }
    }

    @Test
    void theEndpointFormOfClassifyDefaultsToTheEndpointlessOne() {
        EndpointlessProbe probe = new EndpointlessProbe(script(502, JSON, "bad gateway", new ArrayList<>()));
        ExternalServiceException classified = assertThrows(ExternalServiceException.class, () -> probe.getJson("/via"));
        assertEquals("endpointless 502", classified.getErrorDetails(), "the endpoint-less override answers through the endpoint form");
        assertEquals(List.of(502), probe.classified);
        assertFalse(classified instanceof HttpErrorResponse, "its answer replaces the status mapping");
    }

    @Test
    void theParserIsTheBodyToValueFunctionOfExecuteRequest() throws Exception {
        Probe probe = new Probe(script(200, Map.of("Content-Type", "text/plain"), "4 2", new ArrayList<>()));
        int sum = probe.executeRequest(new HttpGet(probe.buildUrl("/numbers")), "/numbers", body -> {
            int total = 0;
            for (String part : body.split(" ")) {
                total += Integer.parseInt(part);
            }
            return total;
        });
        assertEquals(6, sum, "what the parser makes of the body is what executeRequest returns");
    }

    @Test
    void postProcessResponseSeesEveryResponseWhateverItsStatus() throws Exception {
        Probe ok = new Probe(script(200, JSON, "{\"fine\":1}", new ArrayList<>()));
        ok.getJson("/a");
        Probe failing = new Probe(script(503, JSON, "down", new ArrayList<>()));
        assertThrows(Http503Exception.class, () -> failing.getJson("/b"));
        assertEquals(List.of(200), ok.postProcessed);
        assertEquals(List.of(503), failing.postProcessed, "a failing status is post-processed before it is mapped");
        assertEquals(List.of("down"), failing.postProcessedBodies, "the hook sees the body");
    }

    @Test
    void aClassificationReplacesTheStatusMappingAndReceivesTheEndpoint() throws Exception {
        Probe probe = new Probe(script(500, JSON, "special", new ArrayList<>()));
        List<String> endpoints = new ArrayList<>();
        probe.classifier = (endpoint, status) -> {
            endpoints.add(endpoint);
            return new ExternalServiceException("probe", "classified " + status);
        };
        ExternalServiceException classified = assertThrows(ExternalServiceException.class, () -> probe.getJson("/odd"));
        assertEquals("classified 500", classified.getErrorDetails(), "the hook's answer is the failure, not the status mapping's");
        assertFalse(classified instanceof HttpErrorResponse, "the mapping did not run");
        assertEquals(List.of("/odd"), endpoints, "the endpoint form of the hook receives the endpoint");
    }

    @Test
    void anyStatusFrom400IsACarrierOfItsOwnClass() {
        Probe notFound = new Probe(script(404, JSON, "no such thing", new ArrayList<>()));
        Http404Exception carrier = assertThrows(Http404Exception.class, () -> notFound.getJson("/things/9"));
        assertEquals(404, carrier.getStatusCode());
        assertEquals("probe", carrier.getService());
        assertEquals("/things/9", carrier.getEndpoint());
        assertEquals("no such thing", carrier.getResponseBody(), "the upstream body is quoted whole");
        Probe teapot = new Probe(script(418, JSON, "short and stout", new ArrayList<>()));
        HttpUnmappedStatusException unmapped = assertThrows(HttpUnmappedStatusException.class, () -> teapot.getJson("/pot"));
        assertEquals(418, unmapped.getStatusCode(), "a status without a class of its own is still a carrier");
    }

    @Test
    void aRedirectIsFollowedUnlessTheRequestDisabledIt() throws Exception {
        String target = script(200, JSON, "{\"there\":true}", new ArrayList<>());
        Probe following = new Probe(script(302, Map.of("Location", target + "/landing"), "", new ArrayList<>()));
        assertTrue(following.getJson("/hop").get("there").asBoolean(), "the client follows a redirect by default");
        Probe refusing = new Probe(script(302, Map.of("Location", target + "/landing"), "", new ArrayList<>()));
        refusing.followRedirects = false;
        HttpUnmappedStatusException answer = assertThrows(HttpUnmappedStatusException.class, () -> refusing.getJson("/hop"));
        assertEquals(302, answer.getStatusCode(), "with redirects disabled a 3xx is an answer the client must not act on");
    }

    @Test
    void aStatusWithoutABodyReadsAsAnEmptyBody() throws Exception {
        Probe probe = new Probe(script(204, Map.of(), "", new ArrayList<>()));
        assertEquals("", probe.text("/gone", "text/plain"), "a 204 carries no entity and reads as an empty body");
    }

    @Test
    void aServiceThatCannotBeReachedIsAStatuslessFailureNamingThePath() {
        Probe probe = new Probe("http://127.0.0.1:1");
        ExternalServiceException failure = assertThrows(ExternalServiceException.class, () -> probe.getJson("/search?q=secret-term"));
        assertFalse(failure instanceof HttpErrorResponse, "no response, so no status and no carrier");
        assertEquals("probe", failure.getServiceName());
        assertTrue(failure.getErrorDetails().startsWith("/search failed: "), "the endpoint is named as its path: " + failure.getErrorDetails());
        for (Throwable t = failure; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains("secret-term"), "the query is the caller's own text and never rides in a failure");
        }
    }

    @Test
    void aBodyTheParserRefusesIsAStatuslessFailureNamingThePath() {
        Probe probe = new Probe(script(200, JSON, "this is not json", new ArrayList<>()));
        ExternalServiceException failure = assertThrows(ExternalServiceException.class, () -> probe.getJson("/tree?id=7"));
        assertFalse(failure instanceof HttpErrorResponse, "a 200 whose body will not parse has no error status to carry");
        assertTrue(failure.getErrorDetails().startsWith("/tree failed: "), "the endpoint is named as its path: " + failure.getErrorDetails());
    }

    @Test
    void nothingOfTheRequestRidesInAFailure() {
        Probe probe = new Probe(script(500, JSON, "upstream broke", new ArrayList<>()));
        probe.decorationHeader = "HEADER-CANARY-7731";
        Echo sent = new Echo();
        sent.setName("BODY-CANARY-4419");
        Http500Exception failure = assertThrows(Http500Exception.class, () -> probe.postTyped("/echo?token=QUERY-CANARY-2207", sent, Echo.class));
        assertEquals("/echo", failure.getEndpoint(), "the endpoint is the path alone");
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage() + failure.getLLMMessage();
            assertFalse(message.contains("HEADER-CANARY"), "no header rides in a failure");
            assertFalse(message.contains("BODY-CANARY"), "no body rides in a failure");
            assertFalse(message.contains("QUERY-CANARY"), "no query rides in a failure");
        }
    }

    /**
     * The shared pool never retries on its own: a 429 or 503 with a retry-after header
     * comes back to the client at once, seen by the server exactly once, for the dispatcher
     * to schedule the retry. Apache's default strategy would re-send it after sleeping the
     * header's seconds inside the call.
     */
    @Test
    void theSharedPoolHandsAThrottledOrFaultingAnswerBackAtOnce() {
        for (int status : new int[] {429, 503}) {
            List<Seen> seen = new ArrayList<>();
            Probe probe = new Probe(script(status, Map.of("Content-Type", "application/json", "Retry-After", "5"), "{\"error\":\"later\"}", seen));
            long started = System.nanoTime();
            LLMReadableCheckedException failure = assertThrows(LLMReadableCheckedException.class, () -> probe.postTyped("/echo", new Echo(), Echo.class));
            assertTrue(failure instanceof HttpErrorResponse, "the status reaches the client as its carrier: " + failure);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertEquals(1, seen.size(), "a " + status + " is sent once; the retry is the dispatcher's, never the transport's");
            assertTrue(elapsedMs < 2_000, "a " + status + " with Retry-After 5 came back in " + elapsedMs + " ms, not after the header's seconds");
        }
    }

    @Test
    void theTransportIsTheInjectedClientElseTheSharedPool() throws Exception {
        // A server of this test's own, so its route in the pool starts at whatever this test alone puts there
        HttpServer own = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Seen> seen = new ArrayList<>();
        own.createContext("/", exchange -> {
            Map<String, String> requestHeaders = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> requestHeaders.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI(), requestHeaders, ""));
            byte[] bytes = "{\"via\":\"own-server\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        own.start();
        try {
            String ownBase = "http://127.0.0.1:" + own.getAddress().getPort();
            HttpRoute route = new HttpRoute(new HttpHost("http", "127.0.0.1", own.getAddress().getPort()));
            PoolingHttpClientConnectionManager pool = HttpConnectionPools.getInstance().connectionManager();
            int before = held(pool.getStats(route));
            Probe pooled = new Probe(ownBase);
            assertEquals("own-server", pooled.getJson("/p").get("via").asText());
            assertEquals(before + 1, held(pool.getStats(route)), "with no client injected the shared pool opened the one connection on this server's route");
            Probe injected = new Probe(ownBase);
            // A client that stamps every request it sends: the stamp on the wire proves which client carried it
            try (CloseableHttpClient stamping = HttpClients.custom()
                    .addRequestInterceptorFirst((request, entity, context) -> request.setHeader("X-Carried-By", "injected"))
                    .build()) {
                injected.setHttpClient(stamping);
                assertEquals("own-server", injected.getJson("/i").get("via").asText());
            }
            assertEquals("injected", seen.get(1).headers().get("x-carried-by"), "the injected client, not the pool, carried the request");
            assertEquals(before + 1, held(pool.getStats(route)), "the pool saw nothing of the injected client's request");
        }
        finally {
            own.stop(0);
        }
    }

    private static int held(PoolStats stats) {
        return stats.getLeased() + stats.getAvailable();
    }

    @Test
    void encodeIsUtf8UrlEncoding() {
        assertEquals("caf%C3%A9+au+lait%26more", AbstractApiClient.encode("café au lait&more"));
    }
}
