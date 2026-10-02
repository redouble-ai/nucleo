/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Process-wide registry of {@link MCPConnector}s, keyed by the composite identity
 * {@code (handle, endpointId)}. The same logical {@link MCPHandle} can therefore be
 * attached to several distinct endpoints concurrently - the case where one MCP server
 * type runs against multiple isolated backends at once (per-sandbox filesystem roots,
 * per-tenant datasets, per-world harnesses). Those attaches resolve to separate
 * connectors instead of colliding on the handle.
 *
 * <p>Two refcount levels keep lifecycle correct once connector identity and client
 * identity diverge:
 * <ul>
 *   <li><b>Per-connector</b> ({@link Entry#refCount}): how many live attaches hold a
 *       given {@code (handle, endpointId)} connector. Concurrent attaches of the same
 *       pair share one connector; {@link #detach(MCPConnector)} removes it only when the
 *       last holder lets go.</li>
 *   <li><b>Per-endpoint</b> ({@link #endpointRefcounts}): how many distinct connectors
 *       reference an endpoint id. Because {@link MCPClientPool} is keyed by endpoint id
 *       alone, two connectors that share an endpoint share one underlying
 *       {@link MCPClient}; the client is evicted (via {@link MCPClientPool#evict(String)})
 *       only when the last referencing connector is removed.</li>
 * </ul>
 *
 * <p>Concurrency: per-connector state lives in the {@code connectors} map and is mutated
 * only inside its {@link ConcurrentHashMap#compute} lambda. {@link #endpointRefcounts} is
 * touched only <em>outside</em> that lambda (the create/remove decision is exported via a
 * single-element holder), so the two maps are never locked in a nested order and cannot
 * deadlock. The eager {@link MCPConnector#providers()} call and {@link MCPClientPool#evict}
 * both run outside the lambda so network and subprocess I/O never run under a map bin lock.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public final class MCPConnectorRegistry {
    private static final Logger log = LoggerFactory.getLogger(MCPConnectorRegistry.class);

    private static final Map<ConnectorKey, Entry> connectors = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> endpointRefcounts = new ConcurrentHashMap<>();

    private MCPConnectorRegistry() {
    }

    /** Composite connector identity: the LLM-facing handle paired with the endpoint it points at. */
    private record ConnectorKey(MCPHandle handle, String endpointId) {
    }

    /** Registry map value: one connector plus the count of live attaches holding it. */
    private record Entry(MCPConnector connector, int refCount) {
    }

    /**
     * Attaches a connector for the given handle and endpoint and returns it. Concurrent
     * attaches of the same {@code (handle, endpoint.endpointId)} pair share one connector
     * and one underlying client; each attach increments the per-connector refcount and
     * must be balanced by a {@link #detach(MCPConnector)}.
     */
    public static MCPConnector attach(MCPHandle handle, MCPEndpoint endpoint) {
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        String endpointId = endpoint.getEndpointId();
        ConnectorKey key = new ConnectorKey(handle, endpointId);
        boolean[] created = {false};
        Entry entry = connectors.compute(key, (k, existing) -> {
            if (existing == null) {
                created[0] = true;
                return new Entry(new MCPConnector(handle, endpoint), 1);
            }
            return new Entry(existing.connector(), existing.refCount() + 1);
        });
        if (created[0]) {
            endpointRefcounts.computeIfAbsent(endpointId, k -> new AtomicInteger(0)).incrementAndGet();
        }
        entry.connector().providers();
        return entry.connector();
    }

    /**
     * Releases one attach of the given connector. When the last holder of its
     * {@code (handle, endpointId)} detaches, the connector is removed; when the last
     * connector referencing its endpoint id is removed, the underlying {@link MCPClient}
     * is evicted from {@link MCPClientPool}. Idempotent past zero.
     */
    public static void detach(MCPConnector connector) {
        if (connector == null) {
            return;
        }
        ConnectorKey key = new ConnectorKey(connector.handle(), connector.endpoint().getEndpointId());
        String[] removedEndpoint = {null};
        connectors.compute(key, (k, existing) -> {
            if (existing == null) {
                return null;
            }
            if (existing.refCount() - 1 <= 0) {
                removedEndpoint[0] = existing.connector().endpoint().getEndpointId();
                return null;
            }
            return new Entry(existing.connector(), existing.refCount() - 1);
        });
        if (removedEndpoint[0] != null) {
            releaseEndpoint(removedEndpoint[0]);
        }
    }

    /** Decrements the endpoint refcount and evicts the shared client when it reaches zero. */
    private static void releaseEndpoint(String endpointId) {
        AtomicInteger count = endpointRefcounts.get(endpointId);
        if (count == null) {
            log.warn("MCPConnectorRegistry.detach: refcount missing for endpoint '{}'", endpointId);
            MCPClientPool.evict(endpointId);
            return;
        }
        if (count.decrementAndGet() <= 0) {
            endpointRefcounts.remove(endpointId);
            MCPClientPool.evict(endpointId);
        }
    }

    public static Collection<MCPConnector> all() {
        List<MCPConnector> out = new ArrayList<>(connectors.size());
        for (Entry e : connectors.values()) {
            out.add(e.connector());
        }
        return Collections.unmodifiableCollection(out);
    }

    /**
     * Detaches every registered connector. Called from the deployment's shutdown
     * sequence alongside {@link STDIOEndpointPool#shutdown()}. Evicts each distinct
     * endpoint once regardless of refcount - this is process teardown, not a balanced
     * release - then clears all registry state.
     */
    public static void shutdownAll() {
        Set<String> endpointIds = new HashSet<>();
        for (Entry e : connectors.values()) {
            endpointIds.add(e.connector().endpoint().getEndpointId());
        }
        connectors.clear();
        endpointRefcounts.clear();
        for (String endpointId : endpointIds) {
            MCPClientPool.evict(endpointId);
        }
    }

    /** Test hook. Package-private. The same teardown as {@link #shutdownAll()}, between tests. */
    static void resetForTests() {
        shutdownAll();
    }
}
