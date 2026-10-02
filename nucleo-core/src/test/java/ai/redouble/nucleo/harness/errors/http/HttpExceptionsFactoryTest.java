/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpExceptions}, the one place a status becomes a typed exception: below 400 the gate
 * returns silently, from 400 it throws; the ten mapped statuses become their classes and any
 * other error status becomes an {@link HttpUnmappedStatusException} that still carries what the
 * server said, so only a failure to reach the service is ever without a status. And
 * {@link UpstreamFailure}, what a limiter records: the status off the carrier, the retry-after off
 * the throttle signal, zero for both when there was none, rendered as one line.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class HttpExceptionsFactoryTest {

    @Test
    void theGateIsSilentBelow400AndThrowsFromIt() throws LLMReadableCheckedException {
        HttpExceptions.throwIfError("svc", "/e", 200, "ok");
        HttpExceptions.throwIfError("svc", "/e", 302, "moved");
        Http400Exception e = assertThrows(Http400Exception.class, () -> HttpExceptions.throwIfError("svc", "/e", 400, "bad"));
        assertEquals("bad", e.getResponseBody());
        assertThrows(Http503Exception.class, () -> HttpExceptions.throwIfError("svc", "/e", 503, "down"));
    }

    @Test
    void anUnmappedErrorStatusStillCarriesWhatTheServerSaid() {
        LLMReadableCheckedException teapot = HttpExceptions.fromStatus("svc", "/brew?cup=7", 418, "short and stout");
        HttpUnmappedStatusException unmapped = assertInstanceOf(HttpUnmappedStatusException.class, teapot);
        assertInstanceOf(ExternalServiceException.class, unmapped, "an unknown error status is the service's failure, uncorrectable");
        assertEquals(418, unmapped.getStatusCode());
        assertEquals("svc", unmapped.getService());
        assertEquals("/brew", unmapped.getEndpoint(), "the query is cut like every other carrier's");
        assertEquals("short and stout", unmapped.getResponseBody());
        assertEquals("HTTP 418 from svc at /brew: short and stout", unmapped.getLLMMessage());
        assertEquals("HTTP 418 at /brew: short and stout", unmapped.getErrorDetails());
    }

    @Test
    void aThrottleSignalWithoutAHintReportsZeroRetryAfter() {
        assertEquals(0, new UpstreamThrottleException("EPO", "X-Throttling-Control: search=black:0").getRetryAfterSeconds());
        assertEquals(0, HttpExceptions.fromStatus("svc", "/e", 429, "slow") instanceof Http429Exception h ? h.getRetryAfterSeconds() : -1,
                "the factory sees no headers, so a 429 through it carries no retry-after");
        assertEquals(30, new Http429Exception("svc", "/e", "slow", 30).getRetryAfterSeconds());
    }

    @Test
    void upstreamFailureReadsTheRetryAfterOffTheThrottleSignal() {
        UpstreamFailure throttled = UpstreamFailure.from(new Http429Exception("PubMed", "/efetch", "rate exceeded", 42));
        assertEquals(429, throttled.statusCode());
        assertEquals(42, throttled.retryAfterSeconds());
        UpstreamFailure header = UpstreamFailure.from(new UpstreamThrottleException("EPO", "X-Throttling-Control: search=black:0", 60));
        assertEquals(0, header.statusCode(), "a throttle signal read off a header, not a status, has no status");
        assertEquals(60, header.retryAfterSeconds());
        UpstreamFailure robot = UpstreamFailure.from(new Http429Exception("EPO", "/rest-services/published-data/search", "CLIENT.RobotDetected"));
        assertEquals(429, robot.statusCode(), "EPO's robot-detection 403 is raised by its client as a 429, and the limiter sees a 429");
        assertEquals(0, robot.retryAfterSeconds());
        assertEquals(0, UpstreamFailure.from(new Http503Exception("PubMed", "/efetch", "down")).retryAfterSeconds(),
                "a plain server error asked for nothing");
    }

    @Test
    void aCarrierWrappedByAToolIsStillWhatTheServerSaid() {
        Http429Exception throttled = new Http429Exception("EPO OPS", "/rest-services/published-data/publication/docdb/EP1/claims", "rate exceeded", 42);
        ExternalServiceException wrapped = assertInstanceOf(ExternalServiceException.class,
                LLMReadableCheckedException.wrapWithContext(throttled, "EPO OPS", "patentNumber", "EP1", "fetching the claims"));
        UpstreamFailure failure = UpstreamFailure.from(wrapped);
        assertEquals(429, failure.statusCode(), "the status is read off the carrier beneath the tool's wrap");
        assertEquals(42, failure.retryAfterSeconds(), "and so is the retry-after");
        assertEquals("EPO OPS", failure.service());
        assertEquals(throttled.getErrorDetails(), failure.detail(), "the server's detail, not the tool's step text");
        ExternalServiceException wrappedHeader = assertInstanceOf(ExternalServiceException.class,
                LLMReadableCheckedException.wrapWithContext(new UpstreamThrottleException("EPO OPS", "X-Throttling-Control: search=black:0", 60),
                        "EPO OPS", "patentNumber", "EP1", "searching"));
        UpstreamFailure header = UpstreamFailure.from(wrappedHeader);
        assertEquals(0, header.statusCode(), "a throttle read off a header still has no status");
        assertEquals(60, header.retryAfterSeconds(), "its retry-after survives the wrap");
        UpstreamFailure plain = UpstreamFailure.from(assertInstanceOf(ExternalServiceException.class,
                LLMReadableCheckedException.wrapWithContext(new java.io.IOException("connection reset"), "EPO OPS", "patentNumber", "EP1", "fetching the claims")));
        assertEquals(0, plain.statusCode(), "no carrier anywhere in the chain: no status");
        assertEquals("fetching the claims: connection reset", plain.detail(), "and the detail is the wrapper's own");
    }

    @Test
    void theSummaryIsOneLineNamingWhatIsKnown() {
        assertEquals("HTTP 429 from PubMed: rate exceeded (server asked for 42s)",
                new UpstreamFailure(429, "PubMed", "rate exceeded", 42).summary());
        assertEquals("no response from PubMed: connection refused",
                new UpstreamFailure(0, "PubMed", "connection refused", 0).summary(), "no status reads as no response");
        assertEquals("HTTP 500", new UpstreamFailure(500, null, "", 0).summary(), "a blank service or detail is left out");
    }
}
