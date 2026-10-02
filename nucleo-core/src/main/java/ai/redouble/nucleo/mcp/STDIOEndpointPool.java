/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Global pool for STDIO MCP endpoints. Owns the STDIO-specific concerns:
 * <ul>
 *   <li>Global concurrency cap on subprocess count.</li>
 *   <li>Per-endpoint rate limiters (subprocess slots).</li>
 *   <li>Idle subprocess reaper.</li>
 * </ul>
 *
 * <p>Connected-client caching and circuit breaking live in
 * {@link MCPClientPool} - that pool is transport-agnostic and serves both STDIO
 * and HTTP endpoints; callers obtain clients via
 * {@link MCPClientPool#getConnectedClient(MCPEndpoint)}.
 *
 * <p>Configuration comes from {@link McpSettings}:
 * <ul>
 *   <li>{@code stdioPoolMax} - global max concurrent STDIO processes</li>
 *   <li>{@code stdioMaxConcurrentPerEndpoint} - per-endpoint limit</li>
 *   <li>{@code stdioIdleTimeoutSeconds} - idle timeout before the reaper evicts the endpoint's client</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class STDIOEndpointPool {
    private static final Logger log = LoggerFactory.getLogger(STDIOEndpointPool.class);
    /** The pool-wide cap on concurrent STDIO subprocesses, as its own admission account. */
    private static final class GlobalGate extends CountingGate {
        GlobalGate(int capacity) {
            super(capacity);
        }

        @Override
        public String limiterName() {
            return "mcp:stdio";
        }
    }

    private static volatile GlobalGate globalGate;
    private static final Map<String, STDIOEndpointRateLimiter> limiters = new ConcurrentHashMap<>();
    private static final Map<String, Integer> endpointMaxConcurrent = new ConcurrentHashMap<>();
    private static volatile boolean reaperStarted = false;
    private static volatile boolean initialized = false;

    private STDIOEndpointPool() {
    }

    private static synchronized void ensureInitialized() {
        if (!initialized) {
            int poolMax = Settings.get(McpSettings.class).stdioPoolMax;
            globalGate = new GlobalGate(poolMax);
            initialized = true;
            log.info("MCP STDIO pool initialized with max {} concurrent endpoints", poolMax);
        }
    }

    /**
     * The pool-wide subprocess cap. A tool that uses a STDIO endpoint declares this account next
     * to the endpoint's own limiter, so admission reserves both for the head of its queue.
     */
    public static RateLimiter<Void> globalGate() {
        ensureInitialized();
        return globalGate;
    }

    /**
     * Sets a custom max concurrent limit for a specific endpoint.
     *
     * @param endpointId the endpoint identifier (command for STDIO)
     * @param max        maximum concurrent requests for this endpoint
     */
    public static void setEndpointMax(String endpointId, int max) {
        endpointMaxConcurrent.put(endpointId, max);
    }

    /**
     * Gets or creates a rate limiter for the given endpoint.
     * The endpoint id is derived from the endpoint configuration.
     *
     * @param endpoint the STDIO endpoint
     * @return rate limiter for the endpoint
     */
    public static STDIOEndpointRateLimiter getLimiter(STDIOMCPEndpoint endpoint) {
        ensureInitialized();
        ensureReaperStarted();
        String endpointId = endpoint.getEndpointId();
        return limiters.computeIfAbsent(endpointId, id -> {
            int maxConcurrent = endpointMaxConcurrent.getOrDefault(id, Settings.get(McpSettings.class).stdioMaxConcurrentPerEndpoint);
            log.info("Creating MCP STDIO rate limiter for endpoint '{}' with max concurrent: {}", id, maxConcurrent);
            return new STDIOEndpointRateLimiter(endpoint, maxConcurrent);
        });
    }

    /**
     * Gets the idle timeout for subprocess cleanup.
     */
    public static Duration getIdleTimeout() {
        return Duration.ofSeconds(Settings.get(McpSettings.class).stdioIdleTimeoutSeconds);
    }

    /**
     * Returns all active limiters. Used by the reaper.
     */
    static Iterable<STDIOEndpointRateLimiter> getAllLimiters() {
        return limiters.values();
    }

    /**
     * Shuts down all STDIO endpoints, kills subprocesses, and drains the shared
     * {@link MCPClientPool}.
     */
    public static void shutdown() {
        log.info("Shutting down MCP STDIO endpoint pool with {} endpoints", limiters.size());
        STDIOEndpointReaper.shutdown();
        MCPClientPool.shutdownAll();
        for (STDIOEndpointRateLimiter limiter : limiters.values()) {
            limiter.close();
        }
        limiters.clear();
    }

    /**
     * Gets the current number of available global permits.
     */
    public static int getAvailableGlobalPermits() {
        ensureInitialized();
        return (int) (globalGate.capacity() - globalGate.currentInUse());
    }

    /**
     * Gets the current number of active endpoints.
     */
    public static int getActiveEndpointCount() {
        return limiters.size();
    }

    private static synchronized void ensureReaperStarted() {
        if (!reaperStarted) {
            STDIOEndpointReaper.start();
            reaperStarted = true;
        }
    }
}
