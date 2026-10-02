/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.errors.retry.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the out-of-money vs rate-limit discrimination against the real strings
 * each provider returns, since both arrive as the same HTTP status for OpenAI
 * (429) and the whole point is telling them apart. Exercises the shared
 * {@link AbstractRateLimitedClient#messageChainContains} engine and the
 * exact needles the provider classifiers use.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class QuotaClassificationTest {

    // The verified OpenAI out-of-money body (captured live), as a message that carries the
    // status and the JSON body. The OpenAI clients themselves no longer classify by message
    // text - they read the status and the body off the transport's typed failure - so this
    // pins the substring helper the Anthropic client still uses on its SDK's messages.
    private static final String OPENAI_QUOTA = "API request failed: 429 /v1/embeddings\nResponse body: "
            + "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing "
            + "details.\",\"type\":\"insufficient_quota\",\"param\":null,\"code\":\"insufficient_quota\"}}";

    private static final String OPENAI_RATE_LIMIT = "API request failed: 429 /v1/embeddings\nResponse body: "
            + "{\"error\":{\"message\":\"Rate limit reached for text-embedding-3-small\",\"type\":\"requests\","
            + "\"code\":\"rate_limit_exceeded\"}}";

    private static final String ANTHROPIC_CREDIT = "400 Bad Request: {\"type\":\"error\",\"error\":{\"type\":"
            + "\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API. "
            + "Please go to Plans & Billing to upgrade or purchase credits.\"}}";

    private static boolean quota(String msg) {
        // The OpenAI/Anthropic classifiers reduce to these needles.
        return AbstractRateLimitedClient.messageChainContains(new RuntimeException(msg),
                "insufficient_quota", "credit balance is too low", "credit balance");
    }

    private static boolean rateLimit(String msg) {
        return AbstractRateLimitedClient.messageChainContains(new RuntimeException(msg),
                "429", "rate limit", "too many requests");
    }

    @Test
    void openAiInsufficientQuotaIsQuotaNotRetryableRateLimit() {
        assertTrue(quota(OPENAI_QUOTA), "insufficient_quota must classify as out-of-money");
        // It also literally contains '429', so order matters: quota is checked
        // first in the call templates, which this documents.
        assertTrue(rateLimit(OPENAI_QUOTA), "shares the 429 string - hence quota-first ordering in the templates");
    }

    @Test
    void openAiRateLimitIsRetryableNotQuota() {
        assertFalse(quota(OPENAI_RATE_LIMIT), "a genuine rate limit is not out-of-money");
        assertTrue(rateLimit(OPENAI_RATE_LIMIT));
    }

    @Test
    void anthropicLowCreditIsQuotaAndNotARateLimit() {
        assertTrue(quota(ANTHROPIC_CREDIT), "Anthropic low credit must classify as out-of-money");
        assertFalse(rateLimit(ANTHROPIC_CREDIT), "it is an HTTP 400, not a 429 - must not be retried as a rate limit");
    }

    @Test
    void messageChainWalksCausesAndIsCaseInsensitive() {
        Exception buried = new RuntimeException("wrapper", new IllegalStateException("INSUFFICIENT_QUOTA here"));
        assertTrue(AbstractRateLimitedClient.messageChainContains(buried, "insufficient_quota"));
        assertFalse(AbstractRateLimitedClient.messageChainContains(new RuntimeException("all good"), "insufficient_quota"));
        assertFalse(AbstractRateLimitedClient.messageChainContains(null, "x"));
    }

    @Test
    void exceptionMessageNamesTheAccountAndIsClear() {
        QuotaExhaustedException e = new QuotaExhaustedException("OPENAI", "OPENAI / secret openai-prod",
                "text-embedding-3-small", "insufficient_quota", new RuntimeException("orig"));
        String m = e.getMessage();
        assertTrue(m.contains("OUT OF MONEY"), m);
        assertTrue(m.contains("OPENAI / secret openai-prod"), m);
        assertTrue(m.contains("text-embedding-3-small"), m);
        assertTrue(m.toLowerCase().contains("does not recover by retrying"), m);
        assertEquals("OPENAI", e.getProvider());
    }
}
