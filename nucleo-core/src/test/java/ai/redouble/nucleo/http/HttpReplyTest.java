/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.*;
import org.apache.hc.core5.http.message.*;
import org.junit.jupiter.api.*;

import java.nio.charset.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpReply}: the reader reads a whole classic response into status, headers and the
 * body as text, a response without an entity reads as an empty body, and {@code header(name)}
 * is the first header of that name, case-insensitive, or null when the response carried none.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class HttpReplyTest {

    @Test
    void theReaderReadsStatusHeadersAndBody() throws Exception {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(201);
        response.addHeader("X-Request-Id", "req-1");
        response.setEntity(new StringEntity("{\"made\":true}", StandardCharsets.UTF_8));
        HttpReply reply = HttpReply.reader().handleResponse(response);
        assertEquals(201, reply.status());
        assertEquals("{\"made\":true}", reply.body());
        assertEquals("req-1", reply.header("X-Request-Id"));
    }

    @Test
    void aResponseWithoutAnEntityReadsAsAnEmptyBody() throws Exception {
        HttpReply reply = HttpReply.reader().handleResponse(new BasicClassicHttpResponse(204));
        assertEquals(204, reply.status());
        assertEquals("", reply.body(), "no entity is an empty body, never null");
    }

    @Test
    void headerIsTheFirstOfItsNameCaseInsensitiveOrNull() {
        HttpReply reply = new HttpReply(200, new Header[] {
                new BasicHeader("X-Ratelimit-Remaining", "9"),
                new BasicHeader("x-ratelimit-remaining", "8")}, "");
        assertEquals("9", reply.header("X-RATELIMIT-REMAINING"), "the name matches in any case and the first wins");
        assertNull(reply.header("Retry-After"), "a header the response did not carry is null");
    }
}
