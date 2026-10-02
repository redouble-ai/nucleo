/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import org.junit.jupiter.api.*;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RateLimitInfo} as the clients build it: the minimal record for a limit that was hit
 * carries a sixty-second retry-after, nothing remaining and an unknown kind; the record built
 * from a successful response's headers is a capacity reading that was not rate limited; the
 * per-minute fields mirror the limits and read zero when a limit is absent; a null kind reads
 * as unknown; and a header value parses to an integer or to null, never to a failure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class RateLimitInfoTest {

    @Test
    void theMinimalRecordSaysOnlyThatTheLimitWasHit() {
        RateLimitInfo hit = RateLimitInfo.rateLimited("model-a");
        assertEquals("model-a", hit.getModel());
        assertTrue(hit.wasRateLimited());
        assertEquals(Duration.ofSeconds(60), hit.getRetryAfter(), "a minute is the retry-after when the provider named none");
        assertEquals(0, hit.getTokensRemaining());
        assertEquals(0, hit.getRequestsRemaining());
        assertNull(hit.getTokensLimit());
        assertEquals(RateLimitType.UNKNOWN, hit.getType());
        assertEquals(0, hit.tokensPerMinute, "no limit known, so no per-minute figure");
        assertEquals(0, hit.requestsPerMinute);
    }

    @Test
    void theHeadersOfASuccessfulResponseAreACapacityReading() {
        Instant reset = Instant.parse("2026-09-16T12:00:00Z");
        RateLimitInfo info = RateLimitInfo.fromHeaders("model-b", 1_000, 400, reset);
        assertFalse(info.wasRateLimited());
        assertEquals(RateLimitType.CAPACITY, info.getType());
        assertEquals(1_000, info.getTokensLimit());
        assertEquals(400, info.getTokensRemaining());
        assertEquals(reset, info.getTokensReset());
        assertNull(info.getRetryAfter(), "a success carries no retry-after");
        assertNull(info.getRequestsLimit(), "the request headers were not read on this path");
        assertEquals(1_000, info.tokensPerMinute);
        assertEquals(0, info.requestsPerMinute);
    }

    @Test
    void thePerMinuteFieldsMirrorTheLimits_andANullKindIsUnknown() {
        RateLimitInfo info = new RateLimitInfo("model-c", 9_000, 100, null, 50, 10, Duration.ofSeconds(3), true, null);
        assertEquals(9_000, info.tokensPerMinute);
        assertEquals(50, info.requestsPerMinute);
        assertEquals(RateLimitType.UNKNOWN, info.getType(), "a kind nobody named is unknown, never null");
        assertEquals(Duration.ofSeconds(3), info.getRetryAfter());
        assertTrue(info.getCapturedAt().isBefore(Instant.now().plusSeconds(1)), "captured at construction");
    }

    @Test
    void aHeaderValueParsesToAnIntegerOrToNull() {
        assertEquals(42, RateLimitInfo.parseIntOrNull("42"));
        assertEquals(42, RateLimitInfo.parseIntOrNull(" 42 "), "surrounding whitespace is ignored");
        assertNull(RateLimitInfo.parseIntOrNull(null), "a missing header is an absent value");
        assertNull(RateLimitInfo.parseIntOrNull("n/a"), "a non-numeric header is an absent value, not a failure");
        assertNull(RateLimitInfo.parseIntOrNull(""));
    }
}
