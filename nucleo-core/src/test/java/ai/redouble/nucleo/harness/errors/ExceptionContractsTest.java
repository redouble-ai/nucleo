/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins three small mapping contracts every tool author leans on:
 *
 * <ul>
 *   <li>{@link HttpExceptions#fromStatus} - the ONE place a status code becomes a typed
 *       exception. A drift here mis-types errors for every client that inherits
 *       {@code AbstractApiClient}, turning correctable refusals into terminal ones.</li>
 *   <li>{@link LLMReadableCheckedException#unwrap} - a raw exception becomes a
 *       SystemException, an LLM-readable one passes through unchanged, and a transparent
 *       retry signal is rethrown rather than converted (a broad catch must not turn a
 *       retryable 429 into a terminal failure).</li>
 *   <li>{@link LLMStopReason#from} - the full normalization table across provider
 *       vocabularies, null and garbage included.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class ExceptionContractsTest {

    private static LLMReadableCheckedException status(int code) {
        return HttpExceptions.fromStatus("svc", "/endpoint", code, "{\"detail\":\"body\"}");
    }

    @Test
    void statusCodesMapToTheirSemanticParents() {
        assertInstanceOf(InvalidInputException.class, status(400), "400 is the LLM's input to fix");
        assertInstanceOf(UnauthorizedException.class, status(401));
        assertInstanceOf(UnauthorizedException.class, status(403));
        assertInstanceOf(ResourceNotFoundException.class, status(404), "a well-formed id that does not exist");
        assertInstanceOf(InvalidInputException.class, status(413), "too large is correctable - try smaller");
        assertInstanceOf(InvalidInputException.class, status(422));
        assertInstanceOf(UpstreamThrottleException.class, status(429));
        assertInstanceOf(ExternalServiceException.class, status(500));
        assertInstanceOf(ExternalServiceException.class, status(502));
        assertInstanceOf(ExternalServiceException.class, status(503));
    }

    @Test
    void eachStatusBecomesItsOwnClass() {
        Map<Integer, Class<?>> classes = Map.of(
                400, Http400Exception.class, 401, Http401Exception.class, 403, Http403Exception.class,
                404, Http404Exception.class, 413, Http413Exception.class, 422, Http422Exception.class,
                429, Http429Exception.class, 500, Http500Exception.class, 502, Http502Exception.class,
                503, Http503Exception.class);
        for (Map.Entry<Integer, Class<?>> entry : classes.entrySet()) {
            assertSame(entry.getValue(), status(entry.getKey()).getClass(), "status " + entry.getKey() + " has a class of its own");
        }
    }

    @Test
    void everyMappedExceptionCarriesTheHttpPayload() {
        for (int code : new int[]{400, 401, 404, 429, 503}) {
            LLMReadableCheckedException e = status(code);
            assertInstanceOf(HttpErrorResponse.class, e,
                    "the payload interface is how callers read what the server said");
            HttpErrorResponse response = (HttpErrorResponse)e;
            assertEquals(code, response.getStatusCode());
            assertEquals("svc", response.getService());
            assertTrue(response.getResponseBody().contains("detail"));
        }
    }

    @Test
    void anUnknownStatusStillYieldsATypedCarrier() {
        LLMReadableCheckedException teapot = status(418);
        HttpUnmappedStatusException unmapped = assertInstanceOf(HttpUnmappedStatusException.class, teapot,
                "a status outside the ten mapped ones is still a carrier of what the server said, never a raw exception");
        assertEquals(418, unmapped.getStatusCode());
        assertInstanceOf(ExternalServiceException.class, teapot, "and it is the service's failure, uncorrectable");
    }

    @Test
    void unwrapWrapsRawThrowables_andPassesLLMReadableThrough() {
        LLMReadableCheckedException wrapped = LLMReadableCheckedException.unwrap(new IOException("disk on fire"));
        assertInstanceOf(SystemException.class, wrapped, "a raw exception is a bug surface, typed as such");

        InvalidInputException original = new InvalidInputException("param", "value", "rule");
        assertSame(original, LLMReadableCheckedException.unwrap(original),
                "an already-typed failure passes through with its semantics intact");
        assertSame(original, LLMReadableCheckedException.unwrap(new RuntimeException(new RuntimeException(original))),
                "unwrap digs the typed failure out of a wrapper chain");
    }

    @Test
    void unwrapRethrowsTransparentRetrySignals_neverConvertsThem() {
        RateLimitRetryException retry = new RateLimitRetryException("429", null, 1, "slow down", null, null);
        assertThrows(RateLimitRetryException.class,
                () -> LLMReadableCheckedException.unwrap(new RuntimeException(retry)),
                "a broad catch must not launder a retryable signal into a terminal failure");
    }

    @Test
    void stopReasonNormalizationTable() {
        assertEquals(LLMStopReason.END_TURN, LLMStopReason.from("end_turn"));
        assertEquals(LLMStopReason.END_TURN, LLMStopReason.from("stop"));
        assertEquals(LLMStopReason.END_TURN, LLMStopReason.from("COMPLETED"), "case-insensitive");
        assertEquals(LLMStopReason.MAX_TOKENS, LLMStopReason.from("max_tokens"));
        assertEquals(LLMStopReason.MAX_TOKENS, LLMStopReason.from("length"));
        assertEquals(LLMStopReason.STOP_SEQUENCE, LLMStopReason.from("stop_sequence"));
        assertEquals(LLMStopReason.TOOL_USE, LLMStopReason.from("tool_use"));
        assertEquals(LLMStopReason.TOOL_USE, LLMStopReason.from("tool_calls"));
        assertEquals(LLMStopReason.CONTENT_FILTERED, LLMStopReason.from("content_filter"));
        assertEquals(LLMStopReason.CONTENT_FILTERED, LLMStopReason.from("guardrail_intervened"));
        assertEquals(LLMStopReason.CONTENT_FILTERED, LLMStopReason.from("refusal"));
        assertEquals(LLMStopReason.UNKNOWN, LLMStopReason.from(null), "null never throws");
        assertEquals(LLMStopReason.UNKNOWN, LLMStopReason.from("some_future_reason"),
                "an unrecognized vocabulary degrades to UNKNOWN, never to an exception");
    }
}
