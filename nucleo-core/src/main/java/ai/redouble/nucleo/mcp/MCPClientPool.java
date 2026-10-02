/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.errors.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Transport-agnostic registry of live {@link MCPClient} instances keyed by
 * {@link MCPEndpoint#getEndpointId()}. Hands out a single shared client per endpoint
 * with a circuit breaker that fails fast after a recent connection failure so callers
 * don't pay the full handshake timeout for known-bad endpoints.
 *
 * <p>Decoupled from STDIO-specific concerns. STDIO subprocess lifecycle (rate limiters,
 * reaper, global semaphore) lives in {@link STDIOEndpointPool}; this pool only
 * manages the {@link MCPClient} cache and connect-failure cooldown.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public final class MCPClientPool {
    private static final Logger log = LoggerFactory.getLogger(MCPClientPool.class);
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 120_000;
    private static final Object CLIENT_CREATE_LOCK = new Object();
    private static final Map<String, MCPClient> clients = new ConcurrentHashMap<>();
    private static final Map<String, Long> connectionFailures = new ConcurrentHashMap<>();

    private MCPClientPool() {
    }

    /**
     * Returns a connected MCP client for the given endpoint, creating one if needed.
     * Clients are cached per endpoint id and reused across tool invocations.
     *
     * <p>Circuit breaker: if a connection to this endpoint failed within the last
     * {@value CIRCUIT_BREAKER_COOLDOWN_MS}ms the call fails fast with
     * {@link ExternalServiceException} instead of blocking on another handshake.
     *
     * @param endpoint the fully configured endpoint (including environment / headers)
     * @return connected client
     * @throws LLMReadableCheckedException on transport failure or auth rejection
     */
    public static MCPClient getConnectedClient(MCPEndpoint endpoint) throws LLMReadableCheckedException {
        String id = endpoint.getEndpointId();
        MCPClient cached = clients.get(id);
        if (cached != null && cached.isConnected()) {
            return cached;
        }
        Long lastFailure = connectionFailures.get(id);
        if (lastFailure != null) {
            long elapsed = System.currentTimeMillis() - lastFailure;
            if (elapsed < CIRCUIT_BREAKER_COOLDOWN_MS) {
                long remainingSeconds = (CIRCUIT_BREAKER_COOLDOWN_MS - elapsed) / 1000;
                throw new ExternalServiceException("MCP:" + id,
                        "Connection failed recently, retry in " + remainingSeconds + "s");
            }
        }
        synchronized (CLIENT_CREATE_LOCK) {
            cached = clients.get(id);
            if (cached != null && cached.isConnected()) {
                return cached;
            }
            if (cached != null) {
                clients.remove(id);
                try {
                    cached.close();
                }
                catch (Exception e) {
                    log.warn("Error closing stale MCP client for '{}': {}", id, e.getMessage());
                }
            }
            try {
                MCPClient client = GenericMCPClient.connect(endpoint);
                clients.put(id, client);
                connectionFailures.remove(id);
                log.info("Created shared MCP client for endpoint: {}", id);
                return client;
            }
            catch (LLMReadableCheckedException e) {
                connectionFailures.put(id, System.currentTimeMillis());
                throw e;
            }
        }
    }

    /**
     * Removes and closes the cached client for the given endpoint id; closing a STDIO
     * client kills its subprocess. Used by connector teardown when no other consumers
     * reference the endpoint, and by {@link STDIOEndpointReaper} for endpoints idle past
     * the timeout.
     *
     * @return whether a client was cached under the id and is now closed
     */
    public static boolean evict(String endpointId) {
        MCPClient client = clients.remove(endpointId);
        if (client == null) {
            return false;
        }
        try {
            client.close();
        }
        catch (Exception e) {
            log.warn("Error closing MCP client for '{}': {}", endpointId, e.getMessage());
        }
        return true;
    }

    /**
     * Test seam: plants a client in the cache as if {@link #getConnectedClient} had built it.
     */
    static void plant(String endpointId, MCPClient client) {
        clients.put(endpointId, client);
    }

    /**
     * Closes every cached client. Wired from {@link STDIOEndpointPool#shutdown()} so
     * STDIO and HTTP clients drain together at JVM shutdown.
     */
    public static void shutdownAll() {
        log.info("Shutting down MCP client pool with {} clients", clients.size());
        for (MCPClient client : clients.values()) {
            try {
                client.close();
            }
            catch (Exception e) {
                log.warn("Error closing MCP client during shutdown: {}", e.getMessage());
            }
        }
        clients.clear();
        connectionFailures.clear();
    }

    /**
     * Number of cached clients across all transport types. Diagnostics only.
     */
    public static int getActiveClientCount() {
        return clients.size();
    }
}
