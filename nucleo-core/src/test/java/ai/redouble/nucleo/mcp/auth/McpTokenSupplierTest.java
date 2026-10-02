/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The supplier's three promises: one mint serves every concurrent request on a transport
 * without a token, an expired token is replaced before it is sent, and an invalidated
 * token is replaced on the next request.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class McpTokenSupplierTest {
    static final URI MCP = URI.create("http://h/mcp");
    static final AgentSecret CREDENTIAL = new AgentSecret("alpha-agent", "s");

    /** Mints numbered tokens with a chosen expiry, counting the mints. */
    static class CountingClient extends McpTokenClient {
        final AtomicInteger mints = new AtomicInteger();
        volatile Instant expiresAt;
        volatile CountDownLatch hold;

        @Override
        public McpAccessToken acquire(URI mcp, McpClientCredential credential) throws LLMReadableCheckedException {
            CountDownLatch h = hold;
            if (h != null) {
                try {
                    h.await();
                }
                catch (InterruptedException e) {
                    throw new SystemException("test", "interrupted", e);
                }
            }
            return new McpAccessToken("tok-" + mints.incrementAndGet(), expiresAt);
        }
    }

    static String bearer(McpTokenSupplier supplier) throws LLMReadableCheckedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(MCP);
        supplier.attach(request);
        return request.build().headers().firstValue("Authorization").orElseThrow();
    }

    @Test
    void concurrentRequestsShareOneMint() throws Exception {
        CountingClient client = new CountingClient();
        client.hold = new CountDownLatch(1);
        McpTokenSupplier supplier = new McpTokenSupplier(MCP, CREDENTIAL, client);
        int callers = 8;
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> bearer(supplier)));
            }
            Thread.sleep(200);
            client.hold.countDown();
            for (Future<String> result : results) {
                assertEquals("Bearer tok-1", result.get(5, TimeUnit.SECONDS), "every caller got the one minted token");
            }
        }
        assertEquals(1, client.mints.get(), "single flight");
    }

    @Test
    void expiredTokenIsReplacedBeforeSending() throws Exception {
        CountingClient client = new CountingClient();
        client.expiresAt = Instant.now().minusSeconds(1);
        McpTokenSupplier supplier = new McpTokenSupplier(MCP, CREDENTIAL, client);
        assertEquals("Bearer tok-1", bearer(supplier));
        assertEquals("Bearer tok-2", bearer(supplier), "an expired token is never sent");
        client.expiresAt = Instant.now().plusSeconds(60);
        assertEquals("Bearer tok-3", bearer(supplier));
        assertEquals("Bearer tok-3", bearer(supplier), "a live token is reused");
    }

    @Test
    void invalidatedTokenIsReplacedOnTheNextRequest() throws Exception {
        CountingClient client = new CountingClient();
        McpTokenSupplier supplier = new McpTokenSupplier(MCP, CREDENTIAL, client);
        assertEquals("Bearer tok-1", bearer(supplier));
        supplier.invalidate();
        assertEquals("Bearer tok-2", bearer(supplier), "a rejected token is forgotten, the next request mints");
        assertEquals("Bearer tok-2", bearer(supplier));
    }
}
