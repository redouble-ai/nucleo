/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each type of the retry package promises, pacing and weight included: the three upstream signals
 * and the dispatcher's two re-run signals are not LLM-readable (the model never sees them);
 * an attempt-counting signal re-issues itself with the counter advanced and everything else
 * kept; {@code details} renders a failure with its root cause; the two model-facing correctable
 * failures name what to fix; and the two terminal failures, quota and context size, are
 * uncorrectable and say so in numbers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class RetrySignalContractsTest {

    @Test
    void noRetrySignalIsAddressedToTheModel() {
        List<RuntimeException> signals = List.of(
                new RateLimitRetryException("429", null, 1, "slow down", Duration.ofSeconds(3), null),
                new OverloadRetryException("529", "anthropic", "overloaded", null),
                new TransientErrorRetryException("500", "openai", "api_error", 500, 1, null),
                new ResponseCorrectionRetryException("m", 1, new JsonParseException("no object", null)),
                new OutputTruncationRetryException("m", 1000, 4000));
        for (RuntimeException signal : signals) {
            assertFalse(signal instanceof LLMReadable, signal.getClass().getSimpleName() + " is a framework signal, invisible to the model");
        }
    }

    @Test
    void theThreeUpstreamSignalsAreTheWholeSealedFamily() {
        assertEquals(Set.of(RateLimitRetryException.class, OverloadRetryException.class, TransientErrorRetryException.class),
                new HashSet<>(Arrays.asList(UpstreamRetryException.class.getPermittedSubclasses())));
    }

    @Test
    void theThreeSignalsCarryTheirDocumentedPacingAndWeight() {
        RateLimitRetryException rateLimit = new RateLimitRetryException("429", null, 1, "slow down", Duration.ofSeconds(3), null);
        OverloadRetryException overload = new OverloadRetryException("529", "anthropic", "overloaded", null);
        TransientErrorRetryException fault = new TransientErrorRetryException("500", "openai", "api_error", 500, 1, null);
        assertEquals(5_000, rateLimit.baseMinJitterMs());
        assertEquals(30_000, rateLimit.baseMaxJitterMs());
        assertEquals(1, rateLimit.backpressureIncrements());
        assertEquals(15_000, overload.baseMinJitterMs());
        assertEquals(90_000, overload.baseMaxJitterMs());
        assertEquals(3, overload.backpressureIncrements());
        assertEquals(5_000, fault.baseMinJitterMs());
        assertEquals(30_000, fault.baseMaxJitterMs());
        assertEquals(0, fault.backpressureIncrements());
    }

    @Test
    void anAttemptCountingSignalReissuesItselfWithTheCounterAdvanced() {
        RateLimitInfo info = new RateLimitInfo("m", 1000, 0, Instant.parse("2026-09-16T10:00:00Z"), 60, 0, Duration.ofSeconds(7), true);
        IOException cause = new IOException("io");
        RateLimitRetryException rateLimit = new RateLimitRetryException("429", info, 1, "slow down", Duration.ofSeconds(3), cause);
        RateLimitRetryException next = rateLimit.withIncrementedAttempt();
        assertEquals(2, next.getAttemptNumber());
        assertEquals("429", next.getMessage());
        assertSame(info, next.getRateLimitInfo());
        assertEquals("slow down", next.getProviderMessage());
        assertEquals(Duration.ofSeconds(3), next.getSuggestedDelay());
        assertSame(cause, next.getCause(), "everything but the counter is kept");
        IOException faultCause = new IOException("reset");
        TransientErrorRetryException fault = new TransientErrorRetryException("500", "openai", "api_error", 502, 1, faultCause);
        TransientErrorRetryException nextFault = fault.withIncrementedAttempt();
        assertEquals(2, nextFault.getAttemptNumber());
        assertEquals("500", nextFault.getMessage());
        assertEquals("openai", nextFault.getProvider());
        assertEquals("api_error", nextFault.getErrorDetails());
        assertEquals(502, nextFault.getHttpStatus());
        assertSame(faultCause, nextFault.getCause(), "everything but the counter is kept");
    }

    @Test
    void detailsRendersTheMessageAndTheRootCauseWhenThereIsADistinctOne() {
        assertEquals("plain", UpstreamRetryException.details(new RuntimeException("plain")), "no cause: the message alone");
        assertEquals("wrapper [UnknownHostException: api.example]",
                UpstreamRetryException.details(new RuntimeException("wrapper", new java.net.UnknownHostException("api.example"))),
                "the root cause is what separates a broken egress path from a provider fault");
        assertEquals("wrapper [IOException]", UpstreamRetryException.details(new RuntimeException("wrapper", new IOException())),
                "a root cause without a message still names its type");
        assertEquals("IllegalStateException", UpstreamRetryException.details(new IllegalStateException()),
                "no message anywhere: the class name");
    }

    @Test
    void theDispatchersReRunSignalsCarryWhatTheNextRunNeeds() {
        OutputTruncationRetryException truncation = new OutputTruncationRetryException("m", 1000, 4000);
        assertEquals("m", truncation.getModelName());
        assertEquals(1000, truncation.getPreviousBudget());
        assertEquals(4000, truncation.getNewBudget());
        JsonParseException parse = new JsonParseException("no JSON object found", null);
        ResponseCorrectionRetryException correction = new ResponseCorrectionRetryException("m", 2, parse);
        assertEquals("m", correction.getModelName());
        assertEquals(2, correction.getCorrectionAttempt());
        assertSame(parse, correction.getCause(), "the correctable failure that needs the correction travels as the cause");
        assertTrue(correction.getMessage().contains("attempt 2") && correction.getMessage().contains(parse.getMessage()));
    }

    @Test
    void theOverloadSignalNamesTheProviderAndItsWords() {
        OverloadRetryException overload = new OverloadRetryException("529", "anthropic", "overloaded_error", null);
        assertEquals("anthropic", overload.getProvider());
        assertEquals("overloaded_error", overload.getErrorDetails());
    }

    @Test
    void aJsonParseFailureIsCorrectableAndNamesTheShapeThatWasMissed() {
        JsonParseException e = new JsonParseException("No JSON object or array found in the response", null);
        assertTrue(e.isCorrectable(), "the model can fix its own output");
        assertEquals("No JSON object or array found in the response", e.getParseError());
        assertTrue(e.getLLMMessage().contains("JSON object") && e.getLLMMessage().contains(e.getParseError()),
                "the model is told which shape was asked for, and why its reply missed it: " + e.getLLMMessage());
    }

    @Test
    void aValidationFailureIsCorrectableAndListsEveryMissingField() {
        ResponseValidationException e = new ResponseValidationException(List.of("verdict is required", "detail is blank"));
        assertTrue(e.isCorrectable());
        assertEquals(List.of("verdict is required", "detail is blank"), e.getValidationErrors());
        assertEquals("Your response failed validation:\n- verdict is required\n- detail is blank\n", e.getLLMMessage());
    }

    @Test
    void aContextThatCannotFitIsUncorrectableAndSaysByHowMuch() {
        TokenEstimateExceedsLimitException e = new TokenEstimateExceedsLimitException(150_000, 100_000, "m");
        assertFalse(e.isCorrectable(), "no tool parameter shrinks the conversation");
        assertEquals(50_000, e.getOverflowAmount());
        assertEquals(1.5, e.getOverflowRatio(), 1e-9);
        assertEquals(String.format("Conversation context (%,d tokens) exceeds m limit of %,d tokens. Context must be reduced before retrying.", 150_000, 100_000),
                e.getLLMMessage());
        assertTrue(e.getMessage().startsWith("Pre-flight ESTIMATE"), "the log says it was an estimate, never an API answer");
    }

    @Test
    void anExhaustedQuotaIsUncorrectableAndNamesTheAccount() {
        IOException cause = new IOException("insufficient_quota");
        QuotaExhaustedException e = new QuotaExhaustedException("OpenAI", "acct-1", "gpt-5-mini", "You exceeded your current quota", cause);
        assertFalse(e.isCorrectable());
        assertFalse(UpstreamRetryException.class.isAssignableFrom(QuotaExhaustedException.class),
                "never a transparent retry: retrying cannot make money appear");
        assertEquals("OpenAI", e.getProvider());
        assertEquals("acct-1", e.getAccount());
        assertEquals("gpt-5-mini", e.getModelName());
        assertSame(cause, e.getCause());
        assertTrue(e.getLLMMessage().startsWith("OUT OF MONEY: the OpenAI account [acct-1] has no remaining credits or quota (model gpt-5-mini)."),
                e.getLLMMessage());
        assertTrue(e.getLLMMessage().endsWith("Provider said: You exceeded your current quota"));
        String withoutModel = new QuotaExhaustedException("OpenAI", "acct-1", null, null, null).getLLMMessage();
        assertFalse(withoutModel.contains("(model") || withoutModel.contains("Provider said"), "no model and no provider text: neither is mentioned");
    }
}
