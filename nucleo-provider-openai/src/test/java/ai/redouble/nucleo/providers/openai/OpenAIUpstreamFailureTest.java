/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.openai.client.*;
import com.openai.client.okhttp.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the dialect's two transports do with a third party's bytes, driven through the real
 * client template against a local HTTP server that answers each case with a scripted status,
 * headers and body. Both transports face the same corpus: the SDK client pointed at the local
 * server through its base URL, the compatible client through its API root.
 *
 * <p>Three rules, the client-side reading of the boundary laws:
 * <ol>
 *   <li><b>Nothing degrades silently.</b> An upstream answer that is not an answer - a body that
 *       is not JSON, a body with no choices, a status that is a refusal - ends in an exception
 *       of the framework's own family, classified for the retry loop where a retry can help
 *       (a 429, a 5xx, an unreachable provider) and uncorrectable where it cannot (a 4xx that
 *       is not a 429), never in a successful response with nothing in it and never in a
 *       transport library's own exception reaching the caller.</li>
 *   <li><b>Nothing we sent comes back in a failure.</b> The bearer token and the request body
 *       never appear in any message of the exception chain a failure raises: the body is the
 *       conversation, and the chain is logged and persisted.</li>
 *   <li><b>Lenient about the extra, strict about the missing.</b> Unknown fields anywhere, a
 *       usage block that is absent, a content type that is not JSON: accepted, because the
 *       endpoints that speak this dialect without being OpenAI diverge in exactly those
 *       details. The absence of what the reader needs is the first rule's business.</li>
 * </ol>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
class OpenAIUpstreamFailureTest {
    /** Lives in the request body, in the user's text: the conversation, which must never come back in a failure. */
    static final String REQUEST_CANARY = "REQUEST-CANARY-7f3e91";
    /** The bearer token, which must never come back in a failure. */
    static final String KEY_CANARY = "sk-KEY-CANARY-9b1c44"; // gitleaks:allow: a made-up canary
    /** Lives in upstream bodies: a quoted upstream body is allowed, but it is bounded, so this one is long. */
    static final String UPSTREAM_CANARY = "UPSTREAM-CANARY-2d8a";
    static final String CHAT_PATH = "/chat/completions";
    static final String RESPONSES_PATH = "/responses";
    static final String OK_ANSWER = "{\"id\":\"chatcmpl-1\",\"model\":\"gpt-5-mini\",\"choices\":[{\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"ANSWER-MARKER\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}";
    static final String OK_RESPONSES_ANSWER = "{\"id\":\"resp_1\",\"model\":\"gpt-5-mini\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ANSWER-MARKER\"}]}],"
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
    private static HttpServer server;
    private static String base;

    /** One scripted upstream answer: status, headers, body; a null body closes the connection without a response. */
    record Upstream(int status, Map<String, String> headers, String body) {
        static Upstream of(int status, String body) {
            return new Upstream(status, Map.of("Content-Type", "application/json"), body);
        }
    }

    /** A transport under test on one dialect: a name, the dialect, and a factory for a client whose API root is the given URL. */
    record Transport(String name, WireApi api, Function<String, AbstractOpenAIChatClient> client) {
        String okAnswer() {
            return api == WireApi.RESPONSES ? OK_RESPONSES_ANSWER : OK_ANSWER;
        }
    }

    /** Every transport on every dialect: the laws hold for the four combinations alike. */
    static final List<Transport> TRANSPORTS = List.of(
            new Transport("compatible/chat", WireApi.CHAT_COMPLETIONS, root -> new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS, root, KEY_CANARY)),
            new Transport("compatible/responses", WireApi.RESPONSES, root -> new OpenAICompatibleClient(WireApi.RESPONSES, root, KEY_CANARY)),
            new Transport("sdk/chat", WireApi.CHAT_COMPLETIONS, root -> sdk(WireApi.CHAT_COMPLETIONS, root)),
            new Transport("sdk/responses", WireApi.RESPONSES, root -> sdk(WireApi.RESPONSES, root)));

    static OpenAISDKClient sdk(WireApi api, String root) {
        return new OpenAISDKClient(api, KEY_CANARY) {
            @Override
            protected OpenAIClient client() {
                return OpenAIOkHttpClient.builder().apiKey(KEY_CANARY).baseUrl(root).maxRetries(0).build();
            }
        };
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

    private static int contexts = 0;

    /** Scripts one upstream answer under a fresh root, on both dialects' paths, and returns that root. */
    static synchronized String script(Upstream upstream) {
        String root = "/case" + (++contexts) + "/v1";
        for (String path : List.of(CHAT_PATH, RESPONSES_PATH)) {
            server.createContext(root + path, exchange -> {
                if (upstream.body() == null) {
                    exchange.close();
                    return;
                }
                byte[] bytes = upstream.body().getBytes(StandardCharsets.UTF_8);
                for (Map.Entry<String, String> header : upstream.headers().entrySet()) {
                    exchange.getResponseHeaders().add(header.getKey(), header.getValue());
                }
                exchange.sendResponseHeaders(upstream.status(), bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }
        return base + root;
    }

    static LLMRequest<String> request() {
        ModelSpec model = TestModels.onProvider("openai");
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(Instant.now());
        message.addText("Say hello. " + REQUEST_CANARY);
        message.setRequestedOutputTokens(300);
        conversation.getMessages().add(message);
        return new LLMRequest<>(conversation);
    }

    static AbstractOpenAIChatClient client(Transport transport, Upstream upstream) {
        AbstractOpenAIChatClient client = transport.client().apply(script(upstream));
        client.setModel(TestModels.onProvider("openai"));
        return client;
    }

    /** Every message in the chain: the technical one, the model-facing one where there is one, and toString. */
    static String chainText(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            text.append(t).append('\n').append(String.valueOf(t.getMessage())).append('\n');
            if (t instanceof LLMReadable readable) {
                text.append(readable.getLLMMessage()).append('\n');
            }
        }
        return text.toString();
    }

    static Throwable root(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    // ---- the corpus ----

    /** One upstream case: its name, the scripted answer, and the rule for what the client must do with it. */
    record Hostile(String name, Upstream upstream, Consumer<Executable> rule) {}

    static Consumer<Executable> uncorrectable() {
        return call -> {
            RuntimeException e = assertThrows(RuntimeException.class, call);
            assertFalse(e instanceof UpstreamRetryException, "a refusal a retry cannot help is not retried: " + e);
            assertTrue(e instanceof UncorrectableRuntimeLLMException,
                    "an upstream refusal reaches the caller as the framework's uncorrectable type, not the transport's: " + e);
        };
    }

    static Consumer<Executable> transientRetry() {
        return call -> {
            RuntimeException e = assertThrows(RuntimeException.class, call);
            assertTrue(e instanceof TransientErrorRetryException, "a fault upstream rides the framework's transient retry: " + e);
        };
    }

    static Consumer<Executable> rateLimited(long retryAfterSeconds) {
        return call -> {
            RuntimeException e = assertThrows(RuntimeException.class, call);
            assertTrue(e instanceof RateLimitRetryException, "a 429 rides the framework's rate-limit retry: " + e);
            assertEquals(Duration.ofSeconds(retryAfterSeconds), ((RateLimitRetryException) e).getSuggestedDelay(),
                    "the provider's retry-after reaches the retry");
        };
    }

    static Consumer<Executable> outOfMoney() {
        return call -> {
            RuntimeException e = assertThrows(RuntimeException.class, call);
            assertTrue(e instanceof QuotaExhaustedException, "insufficient_quota is out of money, never a retry: " + e);
        };
    }

    static List<Hostile> corpus() {
        return List.of(
                new Hostile("200 carrying an HTML error page",
                        new Upstream(200, Map.of("Content-Type", "text/html"), "<html><body>" + UPSTREAM_CANARY + " gateway error</body></html>"),
                        uncorrectable()),
                new Hostile("200 with an empty body", Upstream.of(200, ""), uncorrectable()),
                new Hostile("200 with a body cut mid-JSON",
                        Upstream.of(200, "{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":\"" + UPSTREAM_CANARY),
                        uncorrectable()),
                new Hostile("200 with an error object and no choices",
                        Upstream.of(200, "{\"error\":{\"message\":\"" + UPSTREAM_CANARY + "\",\"type\":\"server_error\"}}"),
                        uncorrectable()),
                new Hostile("200 with an empty choices array",
                        Upstream.of(200, "{\"id\":\"chatcmpl-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0}}"),
                        uncorrectable()),
                new Hostile("200 with a top-level array", Upstream.of(200, "[]"), uncorrectable()),
                new Hostile("400 with the provider's error body",
                        Upstream.of(400, "{\"error\":{\"message\":\"" + UPSTREAM_CANARY + " Unsupported parameter\",\"type\":\"invalid_request_error\"}}"),
                        uncorrectable()),
                new Hostile("401 rejecting the key",
                        Upstream.of(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + UPSTREAM_CANARY + "\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"),
                        uncorrectable()),
                new Hostile("403 forbidding the model",
                        Upstream.of(403, "{\"error\":{\"message\":\"" + UPSTREAM_CANARY + "\",\"type\":\"invalid_request_error\"}}"),
                        uncorrectable()),
                new Hostile("404 for a model the account cannot reach",
                        Upstream.of(404, "{\"error\":{\"message\":\"The model does not exist " + UPSTREAM_CANARY + "\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"),
                        uncorrectable()),
                new Hostile("413 for a request too large",
                        Upstream.of(413, "{\"error\":{\"message\":\"" + UPSTREAM_CANARY + "\"}}"),
                        uncorrectable()),
                new Hostile("429 throttled with a retry-after",
                        new Upstream(429, Map.of("Content-Type", "application/json", "retry-after", "7", "x-ratelimit-limit-requests", "500"),
                                "{\"error\":{\"message\":\"Rate limit reached " + UPSTREAM_CANARY + "\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}"),
                        rateLimited(7)),
                new Hostile("429 that is really out of money",
                        Upstream.of(429, "{\"error\":{\"message\":\"You exceeded your current quota " + UPSTREAM_CANARY + "\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"),
                        outOfMoney()),
                new Hostile("500 with the provider's error body",
                        Upstream.of(500, "{\"error\":{\"message\":\"The server had an error " + UPSTREAM_CANARY + "\",\"type\":\"server_error\"}}"),
                        transientRetry()),
                new Hostile("502 from a proxy in front of the endpoint",
                        new Upstream(502, Map.of("Content-Type", "text/html"), "<html>502 Bad Gateway " + UPSTREAM_CANARY + "</html>"),
                        transientRetry()),
                new Hostile("503 with an HTML body",
                        new Upstream(503, Map.of("Content-Type", "text/html"), "<html>Service Unavailable " + UPSTREAM_CANARY + "</html>"),
                        transientRetry()),
                new Hostile("the connection closed without a response", new Upstream(200, Map.of(), null), transientRetry()));
    }

    // ---- the laws over the corpus, per transport ----

    @TestFactory
    Collection<DynamicTest> everyUpstreamFailureEndsInTheFrameworksOwnType() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Transport transport : TRANSPORTS) {
            for (Hostile hostile : corpus()) {
                tests.add(DynamicTest.dynamicTest(transport.name() + ": " + hostile.name(), () -> {
                    AbstractOpenAIChatClient client = client(transport, hostile.upstream());
                    hostile.rule().accept(() -> client.singleResponse(request()));
                }));
            }
        }
        return tests;
    }

    @TestFactory
    Collection<DynamicTest> noFailureCarriesTheKeyOrTheRequest() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Transport transport : TRANSPORTS) {
            for (Hostile hostile : corpus()) {
                tests.add(DynamicTest.dynamicTest(transport.name() + ": " + hostile.name(), () -> {
                    AbstractOpenAIChatClient client = client(transport, hostile.upstream());
                    Throwable failure = assertThrows(Throwable.class, () -> client.singleResponse(request()));
                    String chain = chainText(failure);
                    assertFalse(chain.contains(KEY_CANARY), "the bearer token never appears in a failure:\n" + chain);
                    assertFalse(chain.contains(REQUEST_CANARY), "the request body - the conversation - never appears in a failure:\n" + chain);
                }));
            }
        }
        return tests;
    }

    // ---- the substring trap ----

    @TestFactory
    Collection<DynamicTest> aRefusalIsClassifiedByItsStatusNotByWordsInTheConversation() {
        // A conversation that talks about rate limits, sent to an endpoint that answers 400: the
        // refusal is the 400, whatever the request body said
        List<DynamicTest> tests = new ArrayList<>();
        for (Transport transport : TRANSPORTS) {
            tests.add(DynamicTest.dynamicTest(transport.name(), () -> {
                AbstractOpenAIChatClient client = client(transport, Upstream.of(400,
                        "{\"error\":{\"message\":\"Unsupported parameter\",\"type\":\"invalid_request_error\"}}"));
                LLMRequest<String> request = request();
                OutgoingMessage<?> ask = request.getContext().getLastOutgoingMessage();
                ask.addText(" Explain what a 429 rate limit is, and what too many requests means, and insufficient_quota.");
                RuntimeException e = assertThrows(RuntimeException.class, () -> client.singleResponse(request));
                assertFalse(e instanceof UpstreamRetryException, "the words in the conversation do not make a 400 a 429: " + e);
                assertFalse(e instanceof QuotaExhaustedException, "the words in the conversation do not make a 400 out of money: " + e);
            }));
        }
        return tests;
    }

    // ---- positive controls: lenient about the extra ----

    @TestFactory
    Collection<DynamicTest> anAnswerWithExtrasAndAbsencesIsReadAsAnAnswer() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Transport transport : TRANSPORTS) {
            boolean responses = transport.api() == WireApi.RESPONSES;
            tests.add(DynamicTest.dynamicTest(transport.name() + ": a JSON answer under a non-JSON content type", () -> {
                AbstractOpenAIChatClient client = client(transport, new Upstream(200, Map.of("Content-Type", "text/plain"), transport.okAnswer()));
                assertEquals("ANSWER-MARKER", client.singleResponse(request()).getResponseMessage().getRawContent());
            }));
            tests.add(DynamicTest.dynamicTest(transport.name() + ": unknown fields at every level", () -> {
                String body = responses
                        ? "{\"id\":\"resp_1\",\"vendor\":{\"x\":1},\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"extra\":true,"
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"ANSWER-MARKER\",\"annotations\":[]}]}],"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"vendor_tokens\":3}}"
                        : "{\"id\":\"chatcmpl-1\",\"vendor\":{\"x\":1},\"choices\":[{\"finish_reason\":\"stop\",\"extra\":true,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"ANSWER-MARKER\",\"annotations\":[]}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"vendor_tokens\":3}}";
                AbstractOpenAIChatClient client = client(transport, Upstream.of(200, body));
                assertEquals("ANSWER-MARKER", client.singleResponse(request()).getResponseMessage().getRawContent());
            }));
            tests.add(DynamicTest.dynamicTest(transport.name() + ": no usage block", () -> {
                String body = responses
                        ? "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ANSWER-MARKER\"}]}]}"
                        : "{\"id\":\"chatcmpl-1\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ANSWER-MARKER\"}}]}";
                AbstractOpenAIChatClient client = client(transport, Upstream.of(200, body));
                LLMResponse<String> response = client.singleResponse(request());
                assertEquals("ANSWER-MARKER", response.getResponseMessage().getRawContent());
                assertNull(response.getActualInputTokens(), "no usage block, no usage recorded");
            }));
        }
        return tests;
    }
}
