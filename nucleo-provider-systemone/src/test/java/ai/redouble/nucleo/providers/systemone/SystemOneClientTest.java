/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the client does with an endpoint's bytes, driven through the real template against a
 * local HTTP server that answers each case with a scripted status, headers and body. Three
 * rules: nothing degrades silently (a body that is not an answer, a refusal, an unreachable
 * server each end in an exception of the framework's own family, classified for the retry
 * loop where a retry can help and uncorrectable where it cannot); nothing the client sent
 * comes back in a failure (the bearer token and the state never appear in any message of the
 * chain); lenient about the extra and strict about the missing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class SystemOneClientTest {
    static final String STATE_CANARY = "STATE-CANARY-7f3e91";
    static final String KEY_CANARY = "KEY-CANARY-9b1c44"; // gitleaks:allow: a made-up canary
    static final String ANSWER = "{\"model\":\"kev-latest\",\"answers\":{"
            + "\"department\":{\"type\":\"choice\",\"choice\":\"billing\",\"confidence\":0.8,\"probabilities\":{\"billing\":0.87,\"technical\":0.13}},"
            + "\"is_urgent\":{\"type\":\"noul\",\"noul\":0.95}},"
            + "\"usage\":{\"input_tokens\":96,\"output_tokens\":183},\"latency_ms\":203.1}";
    private static HttpServer server;
    private static String base;
    private static int contexts;

    /** One scripted answer: status, headers, body, and what the server saw of the request. */
    record Case(int status, Map<String, String> headers, String body, List<String> seenBodies, List<Map<String, String>> seenHeaders) {}

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

    /** Scripts one answer under a fresh root and returns a client pointed at it, with the case to read what arrived. */
    private static Map.Entry<SystemOneClient, Case> scripted(int status, Map<String, String> headers, String body) {
        return scripted(status, headers, body, KEY_CANARY);
    }

    /** The same, with the key the client sends; null for a local server's credential, which carries none. */
    private static synchronized Map.Entry<SystemOneClient, Case> scripted(int status, Map<String, String> headers, String body, String key) {
        String root = "/case" + (++contexts);
        Case scripted = new Case(status, headers, body, new ArrayList<>(), new ArrayList<>());
        server.createContext(root + SystemOneWire.PATH, exchange -> {
            Map<String, String> requestHeaders = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> requestHeaders.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            scripted.seenHeaders().add(requestHeaders);
            scripted.seenBodies().add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        SystemOneClient client = new SystemOneClient(base + root, key);
        client.setModel(spec());
        return Map.entry(client, scripted);
    }

    static StandardModelSpec spec() {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("kev-test");
        spec.setIdentity("kev-test");
        spec.setProviderKey("systemone-decision");
        spec.setWireModelId("kev-latest");
        spec.setMaxContextTokens(4096);
        spec.setMaxConcurrent(1);
        return spec;
    }

    static DecisionRequest request() {
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        questions.put("department", Choice.of("Which team should handle this?", "billing", "technical"));
        questions.put("is_urgent", Noul.of("Does this convey urgency?"));
        return new DecisionRequest("Help! My payouts have been failing for 3 days. " + STATE_CANARY, questions);
    }

    static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    @Test
    void anAnswerIsReadBackWithItsAccounting_andTheRequestWentOutAsTheWireSpells() throws Exception {
        Map<String, String> headers = new HashMap<>(JSON);
        headers.put(SystemOneClient.REQUEST_ID_HEADER, "req-42");
        Map.Entry<SystemOneClient, Case> scripted = scripted(200, headers, ANSWER);
        DecisionResponse response = scripted.getKey().decide(request());
        assertTrue(response.isSuccessful());
        assertEquals("kev-test", response.getModel(), "the catalog id we called, never the endpoint's echo");
        assertEquals("kev-latest", response.getServedModelId());
        assertEquals("req-42", response.getProviderRequestId());
        assertEquals(96, response.getActualInputTokens());
        assertEquals(0, response.getLastOutputTokens(), "a decision generates nothing");
        assertEquals("billing", response.choice("department").choice());
        assertEquals(0.95, response.noul("is_urgent").probability(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> response.score("department"), "read an answer as it was asked");
        assertTrue(response.getLatency().compareTo(Duration.ZERO) >= 0);
        String sent = scripted.getValue().seenBodies().get(0);
        assertTrue(sent.contains("\"model\":\"kev-latest\""), sent);
        assertTrue(sent.contains(STATE_CANARY), "the state went out");
        assertTrue(sent.contains("\"type\":\"choice\"") && sent.contains("\"type\":\"noul\""), sent);
        assertEquals("Bearer " + KEY_CANARY, scripted.getValue().seenHeaders().get(0).get("authorization"));
    }

    /** A local server's credential is its address alone: the decision goes out with no authorization header at all. */
    @Test
    void aClientWithNoKeySendsNoAuthorizationHeader() throws Exception {
        Map.Entry<SystemOneClient, Case> scripted = scripted(200, JSON, ANSWER, null);
        assertTrue(scripted.getKey().decide(request()).isSuccessful());
        assertFalse(scripted.getValue().seenHeaders().get(0).containsKey("authorization"), "no key, no header: " + scripted.getValue().seenHeaders().get(0));
    }

    @Test
    void theCredentialIsTheBearerKeyAndTheApiRoot_andARootWithoutASchemeIsRefused() {
        assertEquals("systemone-api-key", SystemOneClient.SECRET_ID);
        assertEquals("SYSTEMONE_API_KEY", SystemOneClient.SHAPE.secret().variable());
        assertEquals("SYSTEMONE_API_KEY_HOST", SystemOneClient.SHAPE.host().variable());
        assertNull(SystemOneClient.SHAPE.user(), "no user part: a bearer key and a root");
        SystemOneClient schemeless = new SystemOneClient("127.0.0.1:8009", KEY_CANARY);
        schemeless.setModel(spec());
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class, () -> schemeless.decide(request()));
        assertTrue(refusal.getMessage().contains("scheme"), "refused rather than guessed at: " + refusal.getMessage());
        assertNoCanary(refusal);
    }

    @Test
    void aRateLimitIsARetrySignalCarryingTheRetryAfter() {
        Map<String, String> headers = new HashMap<>(JSON);
        headers.put("Retry-After", "7");
        SystemOneClient client = scripted(429, headers, "{\"detail\":\"slow down\"}").getKey();
        RateLimitRetryException signal = assertThrows(RateLimitRetryException.class, () -> client.decide(request()));
        assertEquals(Duration.ofSeconds(7), signal.getRateLimitInfo().getRetryAfter());
        assertNoCanary(signal);
    }

    @Test
    void aServerErrorAndAnUnreachableServerAreTransientRetrySignals() throws IOException {
        SystemOneClient failing = scripted(503, JSON, "{\"detail\":\"overloaded\"}").getKey();
        TransientErrorRetryException server = assertThrows(TransientErrorRetryException.class, () -> failing.decide(request()));
        assertNoCanary(server);
        int port;
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = taken.getLocalPort();
        }
        SystemOneClient unreachable = new SystemOneClient("http://127.0.0.1:" + port, KEY_CANARY);
        unreachable.setModel(spec());
        TransientErrorRetryException down = assertThrows(TransientErrorRetryException.class, () -> unreachable.decide(request()));
        assertTrue(chain(down).contains("could not be reached"), chain(down));
        assertNoCanary(down);
    }

    @Test
    void aRefusalThatNoRetryHelpsIsAFailureNamingTheStatus() {
        SystemOneClient client = scripted(400, JSON, "{\"detail\":\"bad request\"}").getKey();
        Exception failure = assertThrows(Exception.class, () -> client.decide(request()));
        assertTrue(failure instanceof IOException, "a 4xx is a plain failure: " + failure);
        assertFalse(failure instanceof UpstreamRetryException, "a 4xx is not paced and retried");
        assertTrue(chain(failure).contains("HTTP 400"), chain(failure));
        assertNoCanary(failure);
    }

    @Test
    void aBodyThatIsNotAnAnswerIsAServiceFailureNamingWhatWasMissing() {
        SystemOneClient missing = scripted(200, JSON, "{\"answers\":{\"department\":{\"type\":\"choice\",\"choice\":\"billing\",\"confidence\":0.8,"
                + "\"probabilities\":{\"billing\":0.87,\"technical\":0.13}}}}").getKey();
        Exception failure = assertThrows(Exception.class, () -> missing.decide(request()));
        assertTrue(chain(failure).contains("is_urgent"), "the missing answer is named: " + chain(failure));
        assertTrue(hasCause(failure, ExternalServiceException.class), "a service failure, on the types");
        assertNoCanary(failure);
        SystemOneClient garbage = scripted(200, Map.of("Content-Type", "text/html"), "<html>oops</html>").getKey();
        Exception notJson = assertThrows(Exception.class, () -> garbage.decide(request()));
        assertTrue(hasCause(notJson, ExternalServiceException.class), chain(notJson));
        assertNoCanary(notJson);
    }

    private static boolean hasCause(Throwable t, Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoCanary(Throwable t) {
        String chain = chain(t);
        assertFalse(chain.contains(STATE_CANARY), "the state never rides in a failure: " + chain);
        assertFalse(chain.contains(KEY_CANARY), "the key never rides in a failure: " + chain);
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
