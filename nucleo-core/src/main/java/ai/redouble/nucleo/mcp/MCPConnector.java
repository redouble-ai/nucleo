/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.errors.*;
import org.slf4j.*;

import java.util.*;

/**
 * Single attached MCP server, identified by an {@link MCPHandle}. Holds the
 * {@link MCPEndpoint} configuration and the lazily-resolved list of
 * {@link MCPToolProvider}s pulled from {@code listTools}.
 *
 * <p>The connector is <em>not</em> {@link AutoCloseable} from the user's perspective;
 * its lifecycle is owned by {@link MCPConnectorRegistry}. The registry releases the
 * underlying {@link MCPClient} via {@link MCPClientPool#evict(String)} only when the
 * refcount on the endpoint id drops to zero, so connectors that share an endpoint
 * (different handles, same URL+headers) share one underlying client.
 *
 * <p>{@link #providers()} is {@code synchronized} so concurrent attaches racing the same
 * handle issue exactly one {@code listTools} JSON-RPC roundtrip; subsequent callers see
 * the cached list. On failure the connector returns the last-known-good list (empty if
 * never connected) and lets the next call retry; a network blip never permanently shrinks
 * the LLM's tool surface.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public class MCPConnector {
    private static final Logger log = LoggerFactory.getLogger(MCPConnector.class);
    private final MCPHandle handle;
    private final MCPEndpoint endpoint;
    private List<MCPToolProvider> cachedProviders;
    private MCPServerInfo serverInfo;
    private boolean lastAttemptHealthy;

    MCPConnector(MCPHandle handle, MCPEndpoint endpoint) {
        this.handle = handle;
        this.endpoint = endpoint;
        this.cachedProviders = Collections.emptyList();
    }

    public MCPHandle handle() {
        return handle;
    }

    public MCPEndpoint endpoint() {
        return endpoint;
    }

    /**
     * Returns providers, lazily fetching the tool list on first call and on each
     * subsequent call after {@link #invalidate()} has dropped the cache. On transport
     * failure returns the last-known-good list (empty if never connected) and logs the
     * failure - the caller is not responsible for retry handling.
     */
    public synchronized List<MCPToolProvider> providers() {
        try {
            MCPClient client = MCPClientPool.getConnectedClient(endpoint);
            if (this.serverInfo == null) {
                this.serverInfo = client.getServerInfo();
            }
            if (this.cachedProviders.isEmpty() || !lastAttemptHealthy) {
                List<MCPToolDescriptor> descriptors = client.listTools();
                List<MCPToolProvider> built = new ArrayList<>(descriptors.size());
                for (MCPToolDescriptor descriptor : descriptors) {
                    try {
                        built.add(new MCPToolProvider(handle, endpoint, descriptor));
                    }
                    catch (IllegalArgumentException e) {
                        log.warn("MCPConnector[{}] skipped tool '{}': {}", handle.value(), descriptor.getName(), e.getMessage());
                    }
                    catch (RuntimeException e) {
                        log.error("MCPConnector[{}] errored on tool '{}'", handle.value(), descriptor.getName());
                        log.error(e.getMessage(), e);
                    }
                }
                this.cachedProviders = List.copyOf(built);
            }
            this.lastAttemptHealthy = true;
        }
        catch (LLMReadableCheckedException e) {
            log.warn("MCPConnector[{}] listTools failed: {}", handle.value(), e.getMessage());
            this.lastAttemptHealthy = false;
        }
        return cachedProviders;
    }

    /**
     * Drops the cached provider list. The next {@link #providers()} call re-fetches.
     */
    public synchronized void invalidate() {
        this.cachedProviders = Collections.emptyList();
        this.lastAttemptHealthy = false;
    }

    public synchronized MCPServerInfo serverInfo() {
        return serverInfo;
    }

    public synchronized boolean isHealthy() {
        return lastAttemptHealthy;
    }
}
