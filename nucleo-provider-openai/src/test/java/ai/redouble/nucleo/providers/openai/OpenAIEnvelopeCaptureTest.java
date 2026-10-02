/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.http.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.message.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The account facts the hand-rolled transport reads off a successful reply's headers: the
 * provider's request id, the whole header set, and the x-ratelimit-* family as the account's
 * live limits under OUR catalog id, feeding the pre-emptive limiter. Absent or unreadable
 * headers degrade to absent values, never to zeros.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-30)
 */
class OpenAIEnvelopeCaptureTest {

    private static LLMResponse<String> emptyResponse() {
        ConversationContext conversation = TestModels.conversation(TestModels.onProvider("openai"));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("ping");
        conversation.getMessages().add(message);
        return new LLMResponse<>(new LLMRequest<>(conversation));
    }

    private static OpenAICompatibleClient clientFor(String specId) {
        OpenAICompatibleClient client = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(Models.spec(specId));
        return client;
    }

    private static HttpReply reply(String body, String... nameValuePairs) {
        Header[] headers = new Header[nameValuePairs.length / 2];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = new BasicHeader(nameValuePairs[2 * i], nameValuePairs[2 * i + 1]);
        }
        return new HttpReply(200, headers, body);
    }

    @Test
    void rateLimitHeadersBecomeAccountLimitsUnderTheCatalogId() {
        LLMResponse<String> response = emptyResponse();
        clientFor("gpt-5").captureEnvelope(response, reply("{}",
                "X-RateLimit-Limit-Tokens", "40000000",
                "x-ratelimit-remaining-tokens", "39990000",
                "x-ratelimit-limit-requests", "15000",
                "x-ratelimit-remaining-requests", "14999",
                "x-request-id", "req-777"));
        assertEquals("req-777", response.getProviderRequestId());
        assertEquals("40000000", response.getProviderHeaders().get("x-ratelimit-limit-tokens"), "header names are lowercased");
        RateLimitInfo limits = response.getRateLimitInfo();
        assertEquals("gpt-5", limits.getModel(), "limits attribute to OUR catalog id");
        assertEquals(40000000, limits.getTokensLimit());
        assertEquals(15000, limits.getRequestsLimit());
        assertFalse(limits.wasRateLimited());
    }

    @Test
    void theCapturedLimitsSurviveReadingTheBody() {
        // The whole call path: headers captured, then the body read onto the same response.
        // The limits are what the runtime learns from and what the catalog discovery observes,
        // so the body step must leave them in place.
        LLMResponse<String> response = emptyResponse();
        OpenAICompatibleClient client = clientFor("gpt-5");
        client.captureEnvelope(response, reply("{}", "x-ratelimit-limit-tokens", "40000000", "x-ratelimit-limit-requests", "15000"));
        client.finishResponse(response, response.getRequest(),
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}");
        assertEquals(40000000, response.getRateLimitInfo().getTokensLimit(), "the captured limits are still on the response after the body is read");
        assertEquals("pong", response.getResponseMessage().getRawContent());
    }

    @Test
    void absentOrGarbageHeadersDegradeToAbsentValues() {
        LLMResponse<String> response = emptyResponse();
        clientFor("gpt-5").captureEnvelope(response, reply("{}", "x-ratelimit-limit-tokens", "lots", "x-ratelimit-limit-requests", ""));
        assertNull(response.getRateLimitInfo(), "no readable limit, no limits recorded");
        assertNull(response.getProviderRequestId());
    }
}
