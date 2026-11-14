/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.openai.client.*;
import com.openai.client.okhttp.*;
import com.openai.errors.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An error response from OpenAI names the request in its {@code x-request-id} header, and the
 * exception the SDK clients build from it states that id: it is what OpenAI's support asks
 * for. Driven through the real client templates against a local HTTP server that answers with
 * a scripted status, headers and body.
 *
 * <p>The SDK's own exception message is the status and the error's message, with no id in it,
 * which the first test pins: the day the SDK prints the id itself, that test says so and the
 * rendering, which never repeats an id a text already states, needs no change.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
class OpenAIRequestIdTest {
    static final String REQUEST_ID = "req_openai_4f1d2c9e";
    static final String ERROR_BODY = "{\"error\":{\"message\":\"upstream said no\",\"type\":\"invalid_request_error\",\"code\":\"refused\"}}";
    private static HttpServer server;
    private static String base;
    private static int contexts = 0;

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

    /** Scripts one answer under a fresh root for the chat, responses and embeddings paths; a null id sends no header. */
    static synchronized String script(int status, String requestId, String body) {
        String root = "/requestid" + (++contexts) + "/v1";
        for (String path : List.of("/chat/completions", "/responses", "/embeddings")) {
            server.createContext(root + path, exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (requestId != null) {
                    exchange.getResponseHeaders().add("x-request-id", requestId);
                }
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }
        return base + root;
    }

    static OpenAISDKClient chat(WireApi api, int status, String requestId, String body) {
        OpenAISDKClient client = OpenAIUpstreamFailureTest.sdk(api, script(status, requestId, body));
        client.setModel(TestModels.onProvider("openai"));
        return client;
    }

    static OpenAISDKEmbeddingsClient embeddings(int status, String requestId) {
        String root = script(status, requestId, ERROR_BODY);
        OpenAISDKEmbeddingsClient client = new OpenAISDKEmbeddingsClient(OpenAIUpstreamFailureTest.KEY_CANARY) {
            @Override
            protected OpenAIClient client() {
                return OpenAIOkHttpClient.builder().apiKey(OpenAIUpstreamFailureTest.KEY_CANARY).baseUrl(root).maxRetries(0).build();
            }
        };
        client.setModel(TestModels.onProvider("openai"));
        return client;
    }

    static OpenAIServiceException sdkFailure(Throwable e) {
        OpenAIServiceException svc = OpenAISDKFailures.serviceException(e);
        assertNotNull(svc, "the SDK's own failure rides in the cause chain: " + e);
        return svc;
    }

    @Test
    void theSDKsOwnMessageStatesNoRequestId_soTheClientMustReadTheHeader() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> chat(WireApi.CHAT_COMPLETIONS, 400, REQUEST_ID, ERROR_BODY).singleResponse(OpenAIUpstreamFailureTest.request()));
        OpenAIServiceException svc = sdkFailure(e);
        assertFalse(String.valueOf(svc.getMessage()).contains(REQUEST_ID), "the SDK prints no id: " + svc.getMessage());
        assertEquals(List.of(REQUEST_ID), svc.headers().values("x-request-id"), "it is in the response's header and nowhere else");
    }

    @Test
    void aRefusedChatCall_statesTheRequestIdInTheExceptionTheClientBuilds_onBothDialects() {
        for (WireApi api : List.of(WireApi.CHAT_COMPLETIONS, WireApi.RESPONSES)) {
            OpenAISDKClient client = chat(api, 400, REQUEST_ID, ERROR_BODY);
            UncorrectableRuntimeLLMException e = assertThrows(UncorrectableRuntimeLLMException.class,
                    () -> client.singleResponse(OpenAIUpstreamFailureTest.request()), api.name());
            assertTrue(e.getMessage().endsWith("answered HTTP 400 for " + client.getModel().getId() + " (request id " + REQUEST_ID + ")"),
                    api + ": " + e.getMessage());
            assertEquals(REQUEST_ID, client.providerRequestId(e), "and the client gives it as a value of its own");
        }
    }

    @Test
    void aRetriedChatCall_statesTheRequestIdInItsSignal() {
        OpenAISDKClient throttled = chat(WireApi.CHAT_COMPLETIONS, 429, REQUEST_ID, ERROR_BODY);
        RateLimitRetryException rateLimited = assertThrows(RateLimitRetryException.class,
                () -> throttled.singleResponse(OpenAIUpstreamFailureTest.request()));
        assertTrue(rateLimited.getMessage().endsWith("(request id " + REQUEST_ID + ")"), rateLimited.getMessage());
        assertTrue(rateLimited.getProviderMessage().endsWith("(request id " + REQUEST_ID + ")"),
                "the words quoted when the retries end: " + rateLimited.getProviderMessage());
        OpenAISDKClient failing = chat(WireApi.CHAT_COMPLETIONS, 503, REQUEST_ID, ERROR_BODY);
        TransientErrorRetryException transientError = assertThrows(TransientErrorRetryException.class,
                () -> failing.singleResponse(OpenAIUpstreamFailureTest.request()));
        String message = transientError.getMessage();
        assertTrue(message.contains("answered HTTP 503 for " + failing.getModel().getId() + " (request id " + REQUEST_ID + ")"), message);
        assertEquals(message.indexOf(REQUEST_ID), message.lastIndexOf(REQUEST_ID), "stated once: " + message);
    }

    @Test
    void aResponseTheServiceFailedInsideA200_statesTheRequestIdOfItsEnvelope() {
        String failed = "{\"id\":\"resp_1\",\"model\":\"gpt-5-mini\",\"status\":\"failed\",\"output\":[],"
                + "\"error\":{\"code\":\"server_error\",\"message\":\"the model gave up\"}}";
        UncorrectableRuntimeLLMException e = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> chat(WireApi.RESPONSES, 200, REQUEST_ID, failed).singleResponse(OpenAIUpstreamFailureTest.request()));
        assertTrue(e.getMessage().endsWith("the model gave up (request id " + REQUEST_ID + ")"), e.getMessage());
    }

    @Test
    void anErrorResponseWithoutTheHeaderOrWithABlankOne_hasNothingAppended() {
        for (String header : Arrays.asList(null, " ")) {
            OpenAISDKClient client = chat(WireApi.CHAT_COMPLETIONS, 400, header, ERROR_BODY);
            UncorrectableRuntimeLLMException e = assertThrows(UncorrectableRuntimeLLMException.class,
                    () -> client.singleResponse(OpenAIUpstreamFailureTest.request()));
            assertTrue(e.getMessage().endsWith("answered HTTP 400 for " + client.getModel().getId()), "nothing appended: " + e.getMessage());
            assertNull(client.providerRequestId(e), "no id is no value");
        }
        assertNull(chat(WireApi.CHAT_COMPLETIONS, 400, REQUEST_ID, ERROR_BODY).providerRequestId(new IOException("never sent")),
                "a failure that never reached the service carries none");
    }

    /**
     * The embeddings client builds no exception of its own from a service failure: the SDK's
     * leaves it as thrown. The id is therefore read off that exception, which is how the call
     * record gets it.
     */
    @Test
    void aFailedEmbeddingsCall_givesTheRequestIdOfItsErrorResponse() {
        for (int status : List.of(400, 429, 503)) {
            OpenAISDKEmbeddingsClient client = embeddings(status, REQUEST_ID);
            OpenAIServiceException e = assertThrows(OpenAIServiceException.class, () -> client.embed("text", EmbeddingPurpose.QUERY));
            assertEquals(REQUEST_ID, client.providerRequestId(e), "HTTP " + status);
        }
        OpenAISDKEmbeddingsClient noHeader = embeddings(400, null);
        OpenAIServiceException e = assertThrows(OpenAIServiceException.class, () -> noHeader.embed("text", EmbeddingPurpose.QUERY));
        assertNull(noHeader.providerRequestId(e), "no header, no value");
    }
}
