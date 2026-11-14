/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import com.openai.errors.*;

import java.util.*;

/**
 * The one reading of a failure from OpenAI's own client, shared by the SDK chat and the SDK
 * embeddings clients so the two cannot classify the same exception differently: the typed
 * {@link OpenAIServiceException} in the cause chain decides. {@link OpenAIDialectFailures} is
 * the same reading for the endpoints reached through the framework's HTTP client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
final class OpenAISDKFailures {

    private OpenAISDKFailures() {}

    /** The service failure in the cause chain, or null when the failure never reached the service. */
    static OpenAIServiceException serviceException(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof OpenAIServiceException svc) {
                return svc;
            }
        }
        return null;
    }

    /**
     * The request id of the error response a failure carries, the first value of OpenAI's
     * {@code x-request-id} header; null when the failure never reached the service, when the
     * response named no id, or when the header was blank.
     */
    static String requestId(Throwable e) {
        OpenAIServiceException svc = serviceException(e);
        if (svc == null) {
            return null;
        }
        List<String> values = svc.headers().values("x-request-id");
        if (values.isEmpty() || values.get(0).isBlank()) {
            return null;
        }
        return values.get(0);
    }

    static boolean is429(Throwable e) {
        return serviceException(e) instanceof RateLimitException;
    }

    /** Out of money arrives as a 429 too, distinguished by the error code in the body. */
    static boolean isQuota(Throwable e) {
        OpenAIServiceException svc = serviceException(e);
        return svc != null && svc.code().filter(OpenAIDialectFailures.INSUFFICIENT_QUOTA::equals).isPresent();
    }

    /** A 5xx the service answered, or a transport failure that never got an answer. */
    static boolean isServerError(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof InternalServerException || current instanceof OpenAIIoException) {
                return true;
            }
            if (current instanceof OpenAIServiceException svc && svc.statusCode() >= 500) {
                return true;
            }
        }
        return false;
    }
}
