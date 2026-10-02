/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.ratelimiters.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * What every EPO OPS tool shares: the one OPS rate limiter, declared against the service the
 * tool calls; an {@link EPOClient} bound to the job's HTTP client at execution; and the
 * publication segment of an OPS path, built from a patent number and its input format.
 *
 * @param <I> the tool's input
 * @param <O> the tool's output
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public abstract class AbstractEPOTool<I, O> extends AbstractTool<I, O> {
    protected static final EPORateLimiter RATE_LIMITER = RateLimiterFactory.getInstance().getRateLimiter(EPORateLimiter.class);
    protected final EPOClient client = new EPOClient();
    private final EPOService service;

    protected AbstractEPOTool(Identifiable parent, EPOService service, Duration timeout) {
        super(parent);
        this.service = service;
        setTimeout(timeout);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.requireRateLimiter(RATE_LIMITER, service);
        return req;
    }

    /** Binds the client to this execution's HTTP client. */
    protected void connect(JobResources resources) {
        client.setHttpClient(resources.getHttpClient());
    }

    /**
     * The publication segment of an OPS path, {@code <format>/<encoded number>}; the format
     * defaults to docdb. A missing patent number is the model's mistake to correct.
     */
    protected static String publication(String patentNumber, String inputFormat) throws InvalidInputException {
        if (patentNumber == null || patentNumber.trim().isEmpty()) {
            throw new InvalidInputException("patentNumber", null, "is required");
        }
        String format = inputFormat != null ? inputFormat : "docdb";
        return format + "/" + AbstractApiClient.encode(patentNumber);
    }
}
