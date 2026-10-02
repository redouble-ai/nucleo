/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.time.*;

/**
 * The one reading of a failure from an {@link OpenAIDialectEndpoint}, shared by the chat and
 * the embeddings clients so the two cannot classify the same status differently. Everything
 * is read off the types in the cause chain: the status the endpoint answered, the
 * {@code retry-after} a 429 carried, the quota code in a 429's body. The words of the
 * request never enter into it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
final class OpenAIDialectFailures {
    static final String INSUFFICIENT_QUOTA = "insufficient_quota";

    private OpenAIDialectFailures() {}

    /** The status the endpoint answered, or null when the failure carries none. */
    static HttpErrorResponse answered(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof HttpErrorResponse http) {
                return http;
            }
        }
        return null;
    }

    static boolean is429(Throwable e) {
        HttpErrorResponse http = answered(e);
        return http != null && http.getStatusCode() == 429;
    }

    /**
     * Out of money arrives as a 429 too, told apart by the error code in the body - the same
     * signal the SDK reads off its typed error. A body that is not the dialect's error shape
     * carries no such verdict, and the 429 stays a rate limit.
     */
    static boolean isQuota(Throwable e) {
        HttpErrorResponse http = answered(e);
        if (http == null || http.getStatusCode() != 429 || http.getResponseBody() == null) {
            return false;
        }
        try {
            JsonNode error = NucleoJsonSerializer.readTree(http.getResponseBody()).path("error");
            return INSUFFICIENT_QUOTA.equals(error.path("code").asText(null)) || INSUFFICIENT_QUOTA.equals(error.path("type").asText(null));
        }
        catch (IOException notTheDialectsShape) {
            return false;
        }
    }

    /** A 5xx the endpoint answered, or no answer at all: the provider was not reached. */
    static boolean isServerErrorOrUnreachable(Throwable e) {
        HttpErrorResponse http = answered(e);
        if (http != null) {
            return http.getStatusCode() >= 500;
        }
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof ExternalServiceException) {
                return true;
            }
        }
        return false;
    }

    /** The {@code retry-after} a 429 carried, or null. */
    static Duration retryAfter(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof Http429Exception throttled && throttled.getRetryAfterSeconds() > 0) {
                return Duration.ofSeconds(throttled.getRetryAfterSeconds());
            }
        }
        return null;
    }

    /** What to tell a thinker: the status and the model, never the request. */
    static String describe(Throwable e, String endpointName, String modelId) {
        HttpErrorResponse http = answered(e);
        return http != null
                ? endpointName + " answered HTTP " + http.getStatusCode() + " for " + modelId
                : endpointName + " could not be reached for " + modelId;
    }
}
