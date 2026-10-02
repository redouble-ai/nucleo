/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.*;
import org.apache.hc.core5.http.io.entity.*;

/**
 * A classic HTTP exchange's answer, fully read: status, headers and the body as text.
 *
 * <p>The client's handler form of {@code execute} hands the response to a reader and closes
 * it as soon as the reader returns, so the connection is back in the pool before the caller
 * judges the status. A caller that wants to decide after that point reads everything first,
 * through {@link #reader()}, and decides on the value. A status that carries no entity
 * (204, 304) reads as an empty body.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public record HttpReply(int status, Header[] headers, String body) {

    /** The handler that reads a whole response into an {@link HttpReply}. */
    public static HttpClientResponseHandler<HttpReply> reader() {
        return response -> {
            HttpEntity entity = response.getEntity();
            String body = entity == null ? "" : EntityUtils.toString(entity);
            return new HttpReply(response.getCode(), response.getHeaders(), body);
        };
    }

    /** The first header with this name, or null when the response carried none. */
    public String header(String name) {
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }
}
