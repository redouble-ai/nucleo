/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.anthropic.client.okhttp.*;
import com.anthropic.errors.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An error response names its request in a header, {@code request-id} from Anthropic's own API
 * and {@code x-amzn-RequestId} from a Bedrock endpoint, and the exception the client builds
 * from it states that id: it is what the provider's support asks for. Driven through the real
 * client template against a local HTTP server that answers with a scripted status, headers
 * and body. The Bedrock clients are this client over another transport and read the id
 * through the same method, so the Bedrock shape of the response is tested here.
 *
 * <p>The SDK's exception message is the status and the body. Bedrock's error body is its
 * message alone, so there the header is the only place the id exists, which the first test
 * pins. Anthropic's own error body repeats the id as {@code request_id}, so its text states
 * the id already and the client adds nothing to it.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
class AnthropicRequestIdTest {
    static final String AWS_ID = "5d3a1f0e-7c2b-4e19-9a44-0b6f2c8d7e31";
    static final String ANTHROPIC_ID = "req_011CUQfixtureAnthropicId";
    /** Bedrock's error body: the message, and no id. */
    static final String BEDROCK_BODY = "{\"message\":\"Too many connections, please wait before trying again.\"}";
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

    /** A client whose API root answers every message call with the scripted status, headers and body. */
    static synchronized AnthropicSDKClient client(int status, Map<String, String> headers, String body) {
        String root = "/requestid" + (++contexts);
        server.createContext(root + "/v1/messages", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                exchange.getResponseHeaders().add(header.getKey(), header.getValue());
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        LocalClient client = new LocalClient(base + root);
        client.setModel(Models.spec("claude-haiku-4-5-direct"));
        return client;
    }

    /** The real client over a local endpoint, without the credential read the public constructor performs. */
    private static final class LocalClient extends AnthropicSDKClient {
        LocalClient(String root) {
            super(true);
            this.client = AnthropicOkHttpClient.builder().apiKey("local-fixture").baseUrl(root).maxRetries(0).build();
        }
    }

    static LLMRequest<String> request(AnthropicSDKClient client) {
        ConversationContext conversation = TestModels.conversation(client.getModel());
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("Say hello.");
        conversation.getMessages().add(message);
        return new LLMRequest<>(conversation);
    }

    static <E extends Throwable> E failure(Class<E> type, AnthropicSDKClient client) {
        return assertThrows(type, () -> client.singleResponse(request(client)));
    }

    @Test
    void theSDKsMessageForABedrockErrorStatesNoRequestId_soTheClientMustReadTheHeader() {
        AnthropicSDKClient client = client(529, Map.of("x-amzn-RequestId", AWS_ID), BEDROCK_BODY);
        OverloadRetryException overloaded = failure(OverloadRetryException.class, client);
        AnthropicServiceException sdk = assertInstanceOf(AnthropicServiceException.class, overloaded.getCause());
        assertFalse(String.valueOf(sdk.getMessage()).contains(AWS_ID), "the SDK prints the status and the body, and the body has no id: " + sdk.getMessage());
    }

    @Test
    void anOverloadedBedrockEndpoint_statesItsRequestIdInTheSignal() {
        AnthropicSDKClient client = client(529, Map.of("x-amzn-RequestId", AWS_ID), BEDROCK_BODY);
        OverloadRetryException overloaded = failure(OverloadRetryException.class, client);
        assertTrue(overloaded.getMessage().contains("Too many connections"), overloaded.getMessage());
        assertTrue(overloaded.getMessage().endsWith("(request id " + AWS_ID + ")"), overloaded.getMessage());
        assertTrue(UpstreamRetryException.details(overloaded).contains("(request id " + AWS_ID + ")"),
                "the details the dispatcher quotes when the retries end: " + UpstreamRetryException.details(overloaded));
        assertEquals(AWS_ID, client.providerRequestId(overloaded), "and the client gives it as a value of its own");
    }

    @Test
    void aThrottledAndAFailingBedrockEndpoint_stateTheirRequestIdsInTheirSignals() {
        RateLimitRetryException rateLimited = failure(RateLimitRetryException.class,
                client(429, Map.of("x-amzn-RequestId", AWS_ID), "{\"message\":\"Too many requests, please wait before trying again.\"}"));
        assertTrue(rateLimited.getMessage().endsWith("(request id " + AWS_ID + ")"), rateLimited.getMessage());
        assertTrue(rateLimited.getProviderMessage().endsWith("(request id " + AWS_ID + ")"), rateLimited.getProviderMessage());
        TransientErrorRetryException transientError = failure(TransientErrorRetryException.class,
                client(500, Map.of("x-amzn-RequestId", AWS_ID), "{\"message\":\"The system encountered an unexpected error.\"}"));
        assertTrue(transientError.getMessage().endsWith("(request id " + AWS_ID + ")"), transientError.getMessage());
    }

    /**
     * A refusal no retry can help leaves the client as the SDK threw it, so its own text gains
     * nothing; the id is read off it, which is how the failed-call log line and the call
     * record get it.
     */
    @Test
    void aRefusedCall_leavesAsTheSDKThrewIt_andGivesItsRequestId() {
        AnthropicSDKClient client = client(400, Map.of("x-amzn-RequestId", AWS_ID), "{\"message\":\"Malformed input request.\"}");
        BadRequestException refused = failure(BadRequestException.class, client);
        assertFalse(String.valueOf(refused.getMessage()).contains(AWS_ID), refused.getMessage());
        assertEquals(AWS_ID, client.providerRequestId(refused));
    }

    @Test
    void anthropicsOwnApi_namesTheRequestInItsOwnHeader_andItsTextStatesTheIdOnce() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"},\"request_id\":\"" + ANTHROPIC_ID + "\"}";
        AnthropicSDKClient client = client(529, Map.of("request-id", ANTHROPIC_ID), body);
        OverloadRetryException overloaded = failure(OverloadRetryException.class, client);
        assertEquals(ANTHROPIC_ID, client.providerRequestId(overloaded));
        String message = overloaded.getMessage();
        assertTrue(message.contains(ANTHROPIC_ID), "the body Anthropic sent states the id: " + message);
        assertEquals(message.indexOf(ANTHROPIC_ID), message.lastIndexOf(ANTHROPIC_ID), "and it is not stated a second time: " + message);
    }

    @Test
    void aResponseWithBothHeaders_isNamedByAnthropics_andOneWithNeitherOrABlankOneByNone() {
        AnthropicSDKClient both = client(529, Map.of("request-id", ANTHROPIC_ID, "x-amzn-RequestId", AWS_ID), BEDROCK_BODY);
        assertEquals(ANTHROPIC_ID, both.providerRequestId(failure(OverloadRetryException.class, both)));
        AnthropicSDKClient neither = client(529, Map.of(), BEDROCK_BODY);
        OverloadRetryException unnamed = failure(OverloadRetryException.class, neither);
        assertNull(neither.providerRequestId(unnamed), "no header, no value");
        assertFalse(unnamed.getMessage().contains("request id"), "and nothing appended: " + unnamed.getMessage());
        AnthropicSDKClient blank = client(529, Map.of("x-amzn-RequestId", " "), BEDROCK_BODY);
        assertNull(blank.providerRequestId(failure(OverloadRetryException.class, blank)), "a blank header is no id");
        assertNull(both.providerRequestId(new IllegalStateException("never sent")), "a failure that carries no response carries no id");
    }
}
