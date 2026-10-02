/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.errors.*;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Owns "ensure a token" for one transport: attaches the held token to every request, mints
 * one first when none is held or the held one has expired, and forgets a token the server
 * rejected so the next request mints again. Minting is single flight: concurrent requests
 * on a transport without a token wait for one mint rather than each running the flow.
 * <p>
 * The MCP SDK's own authorization retry re-sends the original request unchanged, so a
 * token minted in reaction to a 401 could not ride the retry; the customizer minting
 * ahead of the request is what makes the first request of a transport carry a token,
 * and {@link #invalidate()} is all the 401 handler does.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class McpTokenSupplier {
    private static final String AUTHORIZATION = "Authorization";
    private final URI mcp;
    private final McpClientCredential credential;
    private final McpTokenClient client;
    private final AtomicReference<McpAccessToken> token = new AtomicReference<>();
    private final ReentrantLock minting = new ReentrantLock();

    public McpTokenSupplier(URI mcp, McpClientCredential credential, McpTokenClient client) {
        this.mcp = mcp;
        this.credential = credential;
        this.client = client;
    }

    /** Sets the bearer on a request the SDK is about to send, minting first when needed. */
    public void attach(HttpRequest.Builder request) throws LLMReadableCheckedException {
        request.header(AUTHORIZATION, "Bearer " + current());
    }

    /** The token to send now: the held one while it is usable, else a freshly minted one. */
    public String current() throws LLMReadableCheckedException {
        McpAccessToken held = token.get();
        if (held != null && held.usableAt(Instant.now())) {
            return held.value();
        }
        minting.lock();
        try {
            held = token.get();
            if (held != null && held.usableAt(Instant.now())) {
                return held.value();
            }
            McpAccessToken minted = client.acquire(mcp, credential);
            token.set(minted);
            return minted.value();
        }
        finally {
            minting.unlock();
        }
    }

    /** Forgets the held token; the server refused it, so the next request mints again. */
    public void invalidate() {
        token.set(null);
    }
}
