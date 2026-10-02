/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import ai.redouble.nucleo.*;
import com.sun.net.httpserver.*;
import org.apache.hc.client5.http.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.pool.*;
import org.apache.hc.core5.util.*;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpConnectionPools}: one instance, one shared client, a pool whose connection count
 * is {@code HttpSettings.poolSize} as the total and as the per-route maximum, its timeouts (30
 * seconds to connect, 15 minutes for a response and for socket reads, 60 minutes to obtain a
 * connection when every one is leased, which is also the bound on HTTP users that are not jobs),
 * validation after 5 seconds idle and eviction after 5 minutes idle, live statistics that move
 * with a request, and one admission gate named {@code http} whose capacity is the pool's count.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class HttpConnectionPoolsTest {

    private static HttpServer server;
    private static HttpHost host;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pooled", exchange -> {
            byte[] bytes = "ok".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        host = new HttpHost("http", "127.0.0.1", server.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void oneInstanceOneClientOneGate() {
        HttpConnectionPools pools = HttpConnectionPools.getInstance();
        assertSame(pools, HttpConnectionPools.getInstance(), "the pool is a singleton");
        assertSame(pools.getClient(), pools.getClient(), "one shared client");
        assertSame(pools.gate(), pools.gate(), "one admission account");
    }

    @Test
    void thePoolAndTheGateAreSizedByHttpSettingsTotalAndPerRoute() {
        HttpConnectionPools pools = HttpConnectionPools.getInstance();
        assertEquals(Settings.get(HttpSettings.class).poolSize, pools.getStats().getMax(), "the pool holds as many connections as HttpSettings.poolSize says");
        assertEquals(Settings.get(HttpSettings.class).poolSize, pools.connectionManager().getDefaultMaxPerRoute(), "one route may use the whole pool");
        assertEquals(Settings.get(HttpSettings.class).poolSize, pools.gate().capacity(), "the gate admits as many HTTP-using jobs as the pool has connections");
        assertEquals("http", pools.gate().limiterName());
    }

    @Test
    void theTimeoutsAreTheDocumentedNumbers() {
        HttpConnectionPools pools = HttpConnectionPools.getInstance();
        assertEquals(Timeout.ofSeconds(30), pools.connectionConfig().getConnectTimeout(), "30 seconds to open a connection");
        assertEquals(Timeout.ofMinutes(15), pools.requestConfig().getResponseTimeout(), "15 minutes for a response");
        assertEquals(Timeout.ofMinutes(15), pools.socketConfig().getSoTimeout(), "15 minutes for socket reads");
        assertEquals(Timeout.ofMinutes(60), pools.requestConfig().getConnectionRequestTimeout(),
                "60 minutes to obtain a connection when every one is leased: the bound on HTTP users that are not jobs");
        assertEquals(TimeValue.ofSeconds(5), pools.connectionConfig().getValidateAfterInactivity(), "validated before reuse after 5 seconds idle");
        assertEquals(TimeValue.ofMinutes(5), pools.idleEviction(), "evicted after 5 minutes idle");
    }

    @Test
    void theStatisticsMoveWithARequestThroughThePool() throws Exception {
        HttpConnectionPools pools = HttpConnectionPools.getInstance();
        HttpRoute route = new HttpRoute(host);
        PoolStats routeBefore = pools.connectionManager().getStats(route);
        PoolStats totalBefore = pools.getStats();
        String body = pools.getClient().execute(new HttpGet(host.toURI() + "/pooled"), HttpReply.reader()).body();
        assertEquals("ok", body);
        PoolStats routeAfter = pools.connectionManager().getStats(route);
        PoolStats totalAfter = pools.getStats();
        assertEquals(held(routeBefore) + 1, held(routeAfter), "the request opened one pooled connection on the server's route, returned to the pool after the reader");
        assertEquals(held(totalBefore) + 1, held(totalAfter), "getStats is the live view of the same pool: the connection counts in the total");
    }

    private static int held(PoolStats stats) {
        return stats.getLeased() + stats.getAvailable();
    }
}
