/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ping's pure logic, shared by the catalog discovery and the platform's scheduled probe:
 * the failure classification that decides what may turn a model off (every exception family),
 * and the response harvest (facts and headers onto the outcome, both branches).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class CatalogPingTest {

    /**
     * A ping's failure carries the provider's own words: a client composes its LLM-facing
     * message from the status and the model and leaves the body in the cause, and the person
     * reading a discovery report needs the body to decide what to do about the entry.
     */
    @Test
    void aFailureIsDescribedWithItsWholeCauseChain() {
        RuntimeException body = new RuntimeException("{\"error\":{\"code\":\"unsupported_parameter\",\"message\":\"max_tokens is not supported\"}}");
        UncorrectableRuntimeLLMException framework = new UncorrectableRuntimeLLMException("Azure AI Foundry answered HTTP 400 for phi-4-azure", body);
        assertEquals("Azure AI Foundry answered HTTP 400 for phi-4-azure <- RuntimeException: {\"error\":{\"code\":\"unsupported_parameter\","
                + "\"message\":\"max_tokens is not supported\"}}", CatalogPing.describe(framework));
        assertEquals("boom", CatalogPing.describe(new IllegalStateException("boom", new IllegalStateException("boom"))),
                "a cause that repeats the message adds nothing");
        assertEquals("outer: inner", CatalogPing.describe(new IllegalStateException("outer: inner", new IllegalStateException("inner"))),
                "a cause the message already contains adds nothing");
        assertEquals("IllegalStateException", CatalogPing.describe(new IllegalStateException()), "a failure with no words at all is named by its class");
    }

    @Test
    void classificationSeparatesDownFromBusyFromMisconfigured() {
        // Availability-shaped: these may turn a model off
        assertEquals(ProbeOutcome.Classification.AVAILABILITY, CatalogPing.classify(
                new TransientErrorRetryException("boom", "openai", "500", 500, 1, null)));
        assertEquals(ProbeOutcome.Classification.AVAILABILITY, CatalogPing.classify(
                new OverloadRetryException("overloaded", "anthropic", "529", null)));
        assertEquals(ProbeOutcome.Classification.AVAILABILITY, CatalogPing.classify(
                new ExternalServiceException("bedrock", "connection refused")));
        assertEquals(ProbeOutcome.Classification.AVAILABILITY, CatalogPing.classify(
                new ResourceNotFoundException("model", "gone-model")));
        // Busy is not down
        assertEquals(ProbeOutcome.Classification.THROTTLE, CatalogPing.classify(
                new RateLimitRetryException("429", null, 1, "rate limited", Duration.ofSeconds(5), null)));
        // A rotated key is a config incident
        assertEquals(ProbeOutcome.Classification.AUTH, CatalogPing.classify(
                new UnauthorizedException("openai", "invalid api key")));
        // Anything else stays out of the turnoff calculus
        assertEquals(ProbeOutcome.Classification.OTHER, CatalogPing.classify(
                new IllegalStateException("unexpected")));
    }

    private static LLMResponse<String> responseWith(boolean successful) {
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(TestModels.small()));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(CatalogPing.PING_PROMPT);
        conversation.getMessages().add(message);
        LLMResponse<String> response = new LLMResponse<>(new LLMRequest<>(conversation));
        response.setSuccessful(successful);
        return response;
    }

    @Test
    void harvestCarriesTheFullProviderEnvelope() {
        LLMResponse<String> response = responseWith(true);
        response.setServedModelId("gpt-5.6-sol");
        response.setProviderRequestId("req-123");
        response.setProviderHeaders(Map.of("x-ratelimit-limit-tokens", "40000000"));
        response.setUsage(42, null, null, 7);
        response.setStopReason(LLMStopReason.END_TURN);
        response.setRateLimitInfo(new RateLimitInfo("gpt-5.6", 40000000, 39999000, null, 15000, 14999, null, false));
        ProbeOutcome outcome = new ProbeOutcome();
        CatalogPing.harvest(outcome, response);
        assertEquals(ProbeOutcome.Status.OK, outcome.getStatus());
        assertNull(outcome.getClassification());
        assertEquals("gpt-5.6-sol", outcome.getServedModelId());
        assertEquals("req-123", outcome.getProviderRequestId());
        assertEquals("40000000", outcome.getHeaders().get("x-ratelimit-limit-tokens"));
        assertEquals(42L, outcome.getInputTokens());
        assertEquals(7L, outcome.getOutputTokens());
        assertEquals("END_TURN", outcome.getStopReason());
        assertEquals(40000000L, outcome.getObservedTokensLimit());
        assertEquals(15000L, outcome.getObservedRequestsLimit());
    }

    @Test
    void harvestOfANonThrowingFailureIsAFailedOutcome() {
        LLMResponse<String> response = responseWith(false);
        response.setLastError(new IOException("mid-stream disconnect"));
        response.setReasonForFailure("stream broke");
        ProbeOutcome outcome = new ProbeOutcome();
        CatalogPing.harvest(outcome, response);
        assertEquals(ProbeOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(ProbeOutcome.Classification.OTHER, outcome.getClassification());
        assertEquals("IOException", outcome.getErrorClass());
        assertEquals("stream broke", outcome.getErrorMessage());
    }
}
