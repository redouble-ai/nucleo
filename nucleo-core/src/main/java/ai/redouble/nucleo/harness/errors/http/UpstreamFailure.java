/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;

/**
 * What an upstream service actually said when a request failed: the HTTP status and the
 * response detail, carried together so a {@link RateLimiter} can record them.
 *
 * <p>A limiter reacting to upstream pressure needs more than a retry hint. It records this
 * alongside its throttle and circuit state and quotes it back when a circuit opens, so a tool
 * that has been disabled names the upstream response responsible instead of leaving it to be
 * reconstructed from surrounding log lines.
 *
 * @param statusCode        HTTP status, or 0 when the request never got a response
 * @param service           the upstream the limiter is protecting, e.g. {@code PubMed}
 * @param detail            the service's own error text, including endpoint and response body
 * @param retryAfterSeconds the server's own instruction, or 0 when it did not give one
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-17)
 */
public record UpstreamFailure(int statusCode, String service, String detail, int retryAfterSeconds) {

    /**
     * Reads what the server actually said, wherever it sits in the cause chain. A status exists
     * only when the failure was raised from an HTTP response, an {@link HttpErrorResponse}
     * carrier; a tool that wrapped that carrier with its service and step
     * ({@code LLMReadableCheckedException.wrapWithContext}) still has it as a cause, so the
     * status and the server's detail are read off the first carrier in the chain, and the
     * retry-after off the first {@link UpstreamThrottleException}. Both are 0 only when no
     * carrier is anywhere in the chain: a transport failure - connection refused, read timeout,
     * unparseable body - genuinely has no status, and 0 records that rather than guessing. The
     * service is the outermost exception's, the one the tool named.
     */
    public static UpstreamFailure from(ExternalServiceException e) {
        HttpErrorResponse carrier = null;
        UpstreamThrottleException throttle = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (carrier == null && t instanceof HttpErrorResponse http) {
                carrier = http;
            }
            if (throttle == null && t instanceof UpstreamThrottleException ute) {
                throttle = ute;
            }
        }
        int status = carrier != null ? carrier.getStatusCode() : 0;
        String detail = carrier instanceof ExternalServiceException carried ? carried.getErrorDetails() : e.getErrorDetails();
        int retryAfter = throttle != null ? throttle.getRetryAfterSeconds() : 0;
        return new UpstreamFailure(status, e.getServiceName(), detail, retryAfter);
    }

    /** Compact one-line form for log lines, event reasons, and LLM-facing error messages. */
    public String summary() {
        StringBuilder s = new StringBuilder();
        s.append(statusCode > 0 ? "HTTP " + statusCode : "no response");
        if (service != null && !service.isBlank()) {
            s.append(" from ").append(service);
        }
        if (detail != null && !detail.isBlank()) {
            s.append(": ").append(detail);
        }
        if (retryAfterSeconds > 0) {
            s.append(" (server asked for ").append(retryAfterSeconds).append("s)");
        }
        return s.toString();
    }
}
