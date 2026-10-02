/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;

import java.io.*;
import java.net.*;
import java.time.*;

/**
 * The template every decision client runs: the one wire call inside the rate-limit
 * feedback, the upstream's verdict classified for the dispatcher the way the embeddings
 * template classifies it (a 429 and a 5xx as transparent retry signals, an exhausted
 * account as its own failure, an endpoint DNS never heard of as uncorrectable), and the
 * call recorded on the response. A subclass carries only its endpoint: how the request
 * body reaches the server and how its failures are read.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public abstract class AbstractDecisionClient extends AbstractRateLimitedClient implements DecisionClient {

    /**
     * What one wire call returned: the decoded answers, the input tokens the endpoint says it
     * billed (null where it reports none), and the identity it reported, each null when absent.
     */
    public record RawDecision(SystemOneWire.Decoded decoded, String providerRequestId) {}

    @Override
    public final DecisionResponse decide(DecisionRequest request) throws IOException, InterruptedException {
        DecisionResponse response = new DecisionResponse(model, request);
        RawDecision raw;
        try {
            raw = executeWithRateLimit(() -> {
                try {
                    return doDecide(request);
                }
                catch (IOException | InterruptedException e) {
                    if (e instanceof IOException && isQuotaError(e)) {
                        throw quotaExhausted(e);
                    }
                    if (e instanceof IOException && is429Error(e)) {
                        RateLimitInfo info = extractRateLimitInfo(e);
                        throw new RateLimitRetryException("Rate limit exceeded: " + e.getMessage(), info, 0, e.getMessage(),
                                info != null ? info.getRetryAfter() : null, e);
                    }
                    // Deployment configuration, not weather: the wrong host builds names DNS
                    // has never heard of, and no retry changes DNS.
                    UnknownHostException unknownHost = e instanceof LLMReadable ? null : AbstractLLMClient.findUnknownHost(e);
                    if (unknownHost != null) {
                        throw new UncorrectableRuntimeLLMException("The endpoint does not exist: " + unknownHost.getMessage()
                                + ". " + (model != null ? model.getId() : "This model")
                                + " is not served where this deployment points. Not retried: no retry changes DNS.", e);
                    }
                    if (e instanceof IOException && isServerError(e)) {
                        String provider = model != null ? model.getId() : "unknown";
                        String details = UpstreamRetryException.details(e);
                        throw new TransientErrorRetryException("Server error: " + details, provider, details, 0, 0, e);
                    }
                    throw new RuntimeException(e);
                }
            });
        }
        catch (UpstreamRetryException | QuotaExhaustedException e) {
            // control signals the dispatcher acts on by type: a retry to pace, an out-of-money failure never to retry
            throw e;
        }
        catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw e;
        }
        response.setAnswers(raw.decoded().answers());
        response.setActualInputTokens(raw.decoded().inputTokens());
        response.setServedModelId(raw.decoded().servedModelId());
        response.setProviderRequestId(raw.providerRequestId());
        response.setEndTime(Instant.now());
        response.setSuccessful(true);
        return response;
    }

    /**
     * The wire call: sends the request to the endpoint and reads the answers back. A body
     * that is not an answer is an {@link IOException} whose message names what was missing
     * and quotes nothing of the state.
     */
    protected abstract RawDecision doDecide(DecisionRequest request) throws IOException, InterruptedException;

    /** Whether the failure (or any cause) is the endpoint refusing for rate; provider-specific. */
    protected abstract boolean is429Error(Exception e);

    /**
     * True if the failure (or any cause) is an HTTP 5xx from the endpoint, or a failure to
     * reach it at all: a server that is down is retried on the dispatcher's pace, and reported
     * by name when the retries run out. Clients on the framework's own HTTP transport fail with
     * a typed {@link HttpErrorResponse} for any status the service answered and with an
     * {@link ExternalServiceException} carrying no status when it was not reached, so the
     * verdict is read off the types.
     */
    protected boolean isServerError(Exception e) {
        boolean answered = false;
        boolean serviceFailure = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof HttpErrorResponse http) {
                answered = true;
                if (http.getStatusCode() >= 500) {
                    return true;
                }
            }
            if (c instanceof ExternalServiceException) {
                serviceFailure = true;
            }
        }
        return serviceFailure && !answered;
    }

    /** The rate-limit facts a 429 carried, or null when the failure was not one. */
    protected abstract RateLimitInfo extractRateLimitInfo(Exception e);
}
