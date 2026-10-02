/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Admission account for a single STDIO MCP endpoint: the per-endpoint cap on concurrent
 * subprocess slots. The pool-wide cap on subprocesses is a separate account,
 * {@link STDIOEndpointPool#globalGate()}, which a tool declares next to this one so
 * admission reserves both for the head of its queue.
 *
 * <p>The subprocess itself is owned by the SDK transport inside the endpoint's
 * {@link MCPClient}; this account only meters admission. {@link STDIOEndpointReaper}
 * reads {@link #isIdle()} and {@link #getLastActivity()} to decide when that client
 * gets evicted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class STDIOEndpointRateLimiter extends CountingGate {
    private static final Logger log = LoggerFactory.getLogger(STDIOEndpointRateLimiter.class);
    private final STDIOMCPEndpoint endpoint;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    STDIOEndpointRateLimiter(STDIOMCPEndpoint endpoint, int maxConcurrent) {
        super(maxConcurrent);
        this.endpoint = endpoint;
    }

    @Override
    public boolean fits(List<Void> mine, List<Void> reservedAhead) {
        refuseIfClosed();
        return super.fits(mine, reservedAhead);
    }

    @Override
    public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
        refuseIfClosed();
        return super.tryTake(mine, reservedAhead);
    }

    private void refuseIfClosed() {
        if (closed.get()) {
            throw new UncorrectableRuntimeLLMException("MCP endpoint " + endpoint.getEndpointId() + " is closed");
        }
    }

    @Override
    public String limiterName() {
        String name = endpoint.getName();
        if (name == null) {
            throw new IllegalStateException("STDIOMCPEndpoint is missing a logical name; "
                    + "set it via STDIOMCPEndpoint#setName before registering with STDIOEndpointPool. "
                    + "Endpoint id: " + endpoint.getEndpointId());
        }
        return "mcp:" + name;
    }

    @Override
    public void onRateLimitError(UpstreamFailure failure) {
        log.warn("MCP STDIO upstream failure for endpoint '{}': {}", endpoint.getEndpointId(), failure.summary());
    }

    /**
     * Returns the endpoint associated with this rate limiter.
     */
    public STDIOMCPEndpoint getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the last activity timestamp.
     */
    public Instant getLastActivity() {
        return lastActivity();
    }

    /**
     * Checks if all permits are available (no active users).
     */
    public boolean isIdle() {
        return currentInUse() == 0;
    }

    /**
     * Marks the limiter as closed; further admission on this account is refused.
     */
    public void close() {
        closed.set(true);
    }
}
