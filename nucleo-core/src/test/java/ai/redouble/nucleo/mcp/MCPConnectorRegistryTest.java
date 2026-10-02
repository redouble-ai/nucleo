/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link MCPConnectorRegistry} composite-key identity and two-level refcount
 * lifecycle. Covers the two failures the registry was built against: cross-endpoint collision under one handle
 * (must not throw, must produce two connectors) and same-endpoint sharing (one connector,
 * torn down only on the last detach), plus the concurrent-attach race.
 *
 * <p>Uses a fake endpoint whose {@code createTransport} throws: {@code attach} eagerly
 * calls {@link MCPConnector#providers()}, which connects, fails, and is swallowed - so the
 * connector registers without a live server. {@link MCPClientPool#evict} on a
 * never-connected endpoint is a no-op, so detach/teardown is exercised cleanly.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-15)
 */
public class MCPConnectorRegistryTest {

    private static final MCPHandle FS = new MCPHandle("fs");

    @AfterEach
    void reset() {
        MCPConnectorRegistry.resetForTests();
    }

    /** Two worlds: same handle, distinct endpoint ids. Neither throws; both register. */
    @Test
    void sameHandleDistinctEndpointsDoNotCollide() {
        MCPConnector a = MCPConnectorRegistry.attach(FS, endpoint("world-a"));
        MCPConnector b = MCPConnectorRegistry.attach(FS, endpoint("world-b"));
        assertNotSame(a, b);
        assertEquals(2, MCPConnectorRegistry.all().size());
    }

    /** Same world: same handle and endpoint share one connector, removed on the last detach. */
    @Test
    void sameHandleAndEndpointShareOneConnector() {
        MCPEndpoint world = endpoint("world-a");
        MCPConnector first = MCPConnectorRegistry.attach(FS, world);
        MCPConnector second = MCPConnectorRegistry.attach(FS, world);
        assertSame(first, second);
        assertEquals(1, MCPConnectorRegistry.all().size());
        MCPConnectorRegistry.detach(first);
        assertEquals(1, MCPConnectorRegistry.all().size(), "still held by the second attach");
        MCPConnectorRegistry.detach(second);
        assertEquals(0, MCPConnectorRegistry.all().size(), "last detach removes the connector");
    }

    /** Detaching past zero is a harmless no-op. */
    @Test
    void detachIsIdempotentPastZero() {
        MCPConnector c = MCPConnectorRegistry.attach(FS, endpoint("world-a"));
        MCPConnectorRegistry.detach(c);
        assertEquals(0, MCPConnectorRegistry.all().size());
        assertDoesNotThrow(() -> MCPConnectorRegistry.detach(c));
        assertDoesNotThrow(() -> MCPConnectorRegistry.detach(null));
    }

    /** Concurrent attaches of the same key resolve to one connector held twice. */
    @Test
    void concurrentAttachSameKeySharesConnector() throws InterruptedException {
        MCPEndpoint world = endpoint("world-a");
        CountDownLatch start = new CountDownLatch(1);
        List<MCPConnector> seen = new CopyOnWriteArrayList<>();
        Runnable task = () -> {
            await(start);
            seen.add(MCPConnectorRegistry.attach(FS, world));
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(5_000);
        t2.join(5_000);
        assertEquals(2, seen.size());
        assertSame(seen.get(0), seen.get(1), "both threads share one connector");
        assertEquals(1, MCPConnectorRegistry.all().size());
        // Refcount reached 2: one detach leaves it live, the second removes it.
        MCPConnectorRegistry.detach(seen.get(0));
        assertEquals(1, MCPConnectorRegistry.all().size());
        MCPConnectorRegistry.detach(seen.get(1));
        assertEquals(0, MCPConnectorRegistry.all().size());
    }

    /** Concurrent attaches of one handle to distinct endpoints register both without collision. */
    @Test
    void concurrentAttachDistinctEndpointsBothRegister() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<MCPConnector> seen = new CopyOnWriteArrayList<>();
        Runnable a = () -> {
            await(start);
            seen.add(MCPConnectorRegistry.attach(FS, endpoint("world-a")));
        };
        Runnable b = () -> {
            await(start);
            seen.add(MCPConnectorRegistry.attach(FS, endpoint("world-b")));
        };
        Thread t1 = new Thread(a);
        Thread t2 = new Thread(b);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(5_000);
        t2.join(5_000);
        assertEquals(2, MCPConnectorRegistry.all().size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Fake endpoint: distinct id per world, transport creation always fails (no live server). */
    private static MCPEndpoint endpoint(String world) {
        return new MCPEndpoint() {
            @Override
            public MCPTransportType getTransportType() {
                return MCPTransportType.HTTP;
            }

            @Override
            public String getEndpointId() {
                return "test://" + world;
            }

            @Override
            public McpClientTransport createTransport(ObjectMapper objectMapper) {
                throw new UnsupportedOperationException("test endpoint has no transport");
            }

            @Override
            public Duration getRequestTimeout() {
                return Duration.ofSeconds(1);
            }
        };
    }
}
