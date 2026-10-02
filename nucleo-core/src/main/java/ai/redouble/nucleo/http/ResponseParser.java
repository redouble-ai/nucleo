/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import java.io.*;

/**
 * Parses a raw HTTP response body into a typed result. Used by
 * {@link AbstractApiClient#executeRequest} to keep the transport layer
 * generic over response type.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
@FunctionalInterface
public interface ResponseParser<T> {
    T parse(String body) throws IOException;
}
