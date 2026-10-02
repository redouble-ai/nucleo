/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.admission.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The idle-subprocess promise: an endpoint whose admission account holds no permits and
 * saw no activity since the cutoff has its client evicted from {@link MCPClientPool}
 * (closing the client kills the subprocess the SDK transport owns), while an endpoint
 * with a permit in use, or one recently active, keeps its client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class STDIOEndpointReaperTest {
    private static final List<Void> ONE = Collections.nCopies(1, null);
    private static final List<Void> NONE = Collections.emptyList();
    private final List<String> planted = new ArrayList<>();

    @AfterEach
    void unplant() {
        planted.forEach(MCPClientPool::evict);
    }

    private STDIOEndpointRateLimiter limiterWithClient(String name, FakeClient client) {
        STDIOMCPEndpoint endpoint = new STDIOMCPEndpoint();
        endpoint.setName(name);
        endpoint.setCommand("/bin/" + name);
        STDIOEndpointRateLimiter limiter = STDIOEndpointPool.getLimiter(endpoint);
        MCPClientPool.plant(endpoint.getEndpointId(), client);
        planted.add(endpoint.getEndpointId());
        return limiter;
    }

    @Test
    void anIdleEndpointPastTheCutoffLosesItsClient() {
        FakeClient client = new FakeClient();
        limiterWithClient("reaper-idle", client);
        STDIOEndpointReaper.reap(Instant.now().plusSeconds(60));
        assertTrue(client.closed, "the idle endpoint's client is closed, which kills its subprocess");
    }

    @Test
    void anEndpointWithAPermitInUseKeepsItsClient() {
        FakeClient client = new FakeClient();
        STDIOEndpointRateLimiter limiter = limiterWithClient("reaper-busy", client);
        assertTrue(limiter.tryTake(ONE, NONE));
        try {
            STDIOEndpointReaper.reap(Instant.now().plusSeconds(60));
            assertFalse(client.closed, "a held permit means the subprocess is in use; the reaper leaves it");
        }
        finally {
            limiter.give(ONE);
        }
    }

    @Test
    void aRecentlyActiveEndpointKeepsItsClient() {
        FakeClient client = new FakeClient();
        limiterWithClient("reaper-warm", client);
        STDIOEndpointReaper.reap(Instant.now().minusSeconds(1));
        assertFalse(client.closed, "activity newer than the cutoff means the endpoint is warm; the reaper leaves it");
    }

    /** A client whose only observable behavior is whether it was closed. */
    private static final class FakeClient implements MCPClient {
        volatile boolean closed;

        @Override
        public RateLimiter<Void> getRateLimiter() {
            return null;
        }

        @Override
        public List<MCPToolDescriptor> listTools() {
            throw new UnsupportedOperationException("not part of the reaper contract");
        }

        @Override
        public MCPToolResult callTool(String toolName, JsonNode arguments) {
            throw new UnsupportedOperationException("not part of the reaper contract");
        }

        @Override
        public MCPServerInfo getServerInfo() {
            return null;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public MCPEndpoint getEndpoint() {
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
