/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.awscore.exception.*;
import software.amazon.awssdk.core.exception.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An error response from Bedrock carries the AWS request id, and the exception the Converse
 * and the Cohere embeddings clients build from it states that id: it is what AWS support asks
 * for. The service's failure is the AWS SDK's own exception, handed to the real client
 * templates in place of the transport, so the clients' own classification builds the signal.
 *
 * <p>The AWS SDK prints the request id in its exception message whenever the response gave
 * it error details, which the first test pins; a text that already states the id is left as
 * the SDK wrote it, and the id is appended only where the text lacks it.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
class BedrockRequestIdTest {
    static final String AWS_ID = "8c1e5b7a-2f40-4d6e-b3a9-71d0c4e2f5a6";

    /** A service failure as the SDK builds it from an error response: status, error details, request id. */
    static AwsServiceException serviceFailure(int status, String words) {
        return BedrockRuntimeException.builder()
                .statusCode(status)
                .requestId(AWS_ID)
                .awsErrorDetails(AwsErrorDetails.builder().serviceName("BedrockRuntime").errorCode("Fixture").errorMessage(words).build())
                .build();
    }

    /** A service failure whose response gave no error details, so the SDK's message is the bare words. */
    static AwsServiceException bareFailure(int status, String words) {
        return BedrockRuntimeException.builder().statusCode(status).requestId(AWS_ID).message(words).build();
    }

    /** The real Converse client with the service's failure in place of the transport. */
    private static final class FailingConverse extends BedrockConverseClient {
        private final RuntimeException failure;

        FailingConverse(RuntimeException failure) {
            this.failure = failure;
            setModel(TestModels.micro());
        }

        @Override
        protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
            throw failure;
        }
    }

    /** The real Cohere embeddings client with the service's failure in place of the transport, wrapped as the client wraps it. */
    private static final class FailingEmbeddings extends BedrockCohereEmbeddingsClient {
        private final SdkException failure;

        FailingEmbeddings(SdkException failure) {
            super(null);
            this.failure = failure;
            StandardModelSpec spec = new StandardModelSpec();
            spec.setId("cohere-test");
            spec.setIdentity("cohere-test");
            spec.setProviderKey("bedrock-cohere-embeddings");
            spec.setWireModelId("us.cohere.embed-v4:0");
            spec.setMaxContextTokens(128000);
            spec.setMaxOutputTokens(128000);
            spec.setTpm(300000);
            spec.setRpm(2000);
            spec.setEmbeddingDimensions(1024);
            setModel(spec);
        }

        @Override
        protected RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) throws IOException {
            throw new IOException(failure);
        }
    }

    static LLMRequest<String> request(ModelSpec model) {
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("Say hello.");
        conversation.getMessages().add(message);
        return new LLMRequest<>(conversation);
    }

    /**
     * The text states the id, and the client added its own {@code (request id ...)} only where
     * the SDK's words did not already state it, and then once.
     */
    static void assertStatedOnce(String text) {
        assertTrue(text.contains(AWS_ID), "the request id is stated: " + text);
        String appended = "(request id " + AWS_ID + ")";
        if (text.contains("Request ID: " + AWS_ID)) {
            assertFalse(text.contains(appended), "the SDK's words state it, so the client adds nothing: " + text);
        }
        else {
            assertEquals(text.indexOf(appended), text.lastIndexOf(appended), "the client adds it once: " + text);
            assertTrue(text.contains(appended), text);
        }
    }

    @Test
    void theSDKsMessageStatesTheRequestId_whenTheResponseGaveErrorDetails_andNotWithoutThem() {
        assertTrue(serviceFailure(503, "down").getMessage().contains("Request ID: " + AWS_ID), serviceFailure(503, "down").getMessage());
        assertEquals("down", bareFailure(503, "down").getMessage(), "without error details the message is the bare words");
        assertEquals(AWS_ID, BedrockClients.requestId(bareFailure(503, "down")), "the id is on the exception either way");
    }

    @Test
    void aThrottledAndAFailingConverseCall_stateTheRequestIdInTheirSignalsOnce() {
        for (AwsServiceException failure : new AwsServiceException[] {serviceFailure(429, "Too many requests"), bareFailure(429, "Too many requests")}) {
            FailingConverse client = new FailingConverse(failure);
            RateLimitRetryException rateLimited = assertThrows(RateLimitRetryException.class, () -> client.singleResponse(request(client.getModel())));
            assertStatedOnce(rateLimited.getMessage());
            assertStatedOnce(rateLimited.getProviderMessage());
            assertEquals(AWS_ID, client.providerRequestId(rateLimited), "and the client gives it as a value of its own");
        }
        for (AwsServiceException failure : new AwsServiceException[] {serviceFailure(503, "Service unavailable"), bareFailure(503, "Service unavailable")}) {
            FailingConverse client = new FailingConverse(failure);
            TransientErrorRetryException transientError = assertThrows(TransientErrorRetryException.class, () -> client.singleResponse(request(client.getModel())));
            assertStatedOnce(transientError.getMessage());
        }
        assertTrue(assertThrows(TransientErrorRetryException.class,
                        () -> { FailingConverse bare = new FailingConverse(bareFailure(503, "Service unavailable")); bare.singleResponse(request(bare.getModel())); })
                .getMessage().contains("(request id " + AWS_ID + ")"), "where the SDK's text lacks the id, the client appends it");
    }

    /** A refusal no retry can help leaves the client as the SDK threw it; the id is read off it for the log line and the call record. */
    @Test
    void aRefusedConverseCall_leavesAsTheSDKThrewIt_andGivesItsRequestId() {
        AwsServiceException refusal = serviceFailure(400, "Malformed input request");
        FailingConverse client = new FailingConverse(refusal);
        assertSame(refusal, assertThrows(AwsServiceException.class, () -> client.singleResponse(request(client.getModel()))));
        assertEquals(AWS_ID, client.providerRequestId(refusal));
    }

    @Test
    void aThrottledAndAFailingEmbeddingsCall_stateTheRequestIdInTheirSignalsOnce() {
        for (AwsServiceException failure : new AwsServiceException[] {serviceFailure(429, "Too many tokens"), bareFailure(429, "Too many tokens")}) {
            FailingEmbeddings client = new FailingEmbeddings(failure);
            RateLimitRetryException rateLimited = assertThrows(RateLimitRetryException.class, () -> client.embed("text", EmbeddingPurpose.QUERY));
            assertStatedOnce(rateLimited.getMessage());
            assertEquals(AWS_ID, client.providerRequestId(rateLimited));
        }
        for (AwsServiceException failure : new AwsServiceException[] {serviceFailure(500, "Internal failure"), bareFailure(500, "Internal failure")}) {
            FailingEmbeddings client = new FailingEmbeddings(failure);
            TransientErrorRetryException transientError = assertThrows(TransientErrorRetryException.class, () -> client.embed("text", EmbeddingPurpose.QUERY));
            assertStatedOnce(transientError.getMessage());
        }
    }

    @Test
    void aFailureThatNeverGotAResponse_orWhoseResponseNamedNoId_carriesNone() {
        FailingConverse client = new FailingConverse(new IllegalStateException("unused"));
        assertNull(client.providerRequestId(SdkClientException.builder().message("connection reset").build()), "a transport failure has no response");
        assertNull(client.providerRequestId(BedrockRuntimeException.builder().statusCode(503).message("down").build()), "a response that named none");
        assertNull(client.providerRequestId(BedrockRuntimeException.builder().statusCode(503).requestId(" ").message("down").build()), "a blank id is no id");
    }
}
