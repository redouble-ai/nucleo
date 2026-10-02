/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import ai.redouble.nucleo.*;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.core5.http.io.*;
import org.apache.hc.core5.pool.*;
import org.apache.hc.core5.util.*;
import org.slf4j.*;

/**
 * Shared HTTP connection pool for all API calls.
 *
 * <p>Single pool serves all workloads (LLM, embeddings, general HTTP).
 * Apache HttpClient's internal connection pooling handles concurrency. The pool holds
 * {@link HttpSettings#poolSize} connections, as the total and as the per-route maximum
 * alike. Its timeouts: 30 seconds to open a connection, 15 minutes for a response and for
 * socket reads, 60 minutes to obtain a connection from the pool when every one is leased.
 * A connection idle for more than 5 seconds is validated before reuse; one idle for 5
 * minutes is evicted.
 *
 * <p>Usage: Just call {@link #getClient()} from anywhere that needs HTTP.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-01-10)
 */
public class HttpConnectionPools {
    private static final Logger log = LoggerFactory.getLogger(HttpConnectionPools.class);
    private static final HttpConnectionPools INSTANCE = new HttpConnectionPools();

    // Timeout configuration
    private static final int CONNECTION_REQUEST_TIMEOUT_MINUTES = 60;
    /** How long a connection may take to open; SDK transports a provider builds itself use the same. */
    public static final int CONNECT_TIMEOUT_SECONDS = 30;
    /**
     * How long a response may take to arrive: a model call writing a long answer is silent until it
     * is done, so this is the window of the longest call, and SDK transports a provider builds
     * itself use the same rather than their own shorter defaults.
     */
    public static final int RESPONSE_TIMEOUT_MINUTES = 15;
    private static final int VALIDATE_AFTER_INACTIVITY_SECONDS = 5;
    private static final int IDLE_EVICTION_MINUTES = 5;

    private final PoolingHttpClientConnectionManager connectionManager;
    private final SocketConfig socketConfig;
    private final ConnectionConfig connectionConfig;
    private final RequestConfig requestConfig;
    private final TimeValue idleEviction;
    private final CloseableHttpClient httpClient;
    private final int maxConnections;
    private final HttpConnectionGate gate;

    private HttpConnectionPools() {
        this.maxConnections = Settings.get(HttpSettings.class).poolSize;
        this.gate = new HttpConnectionGate(maxConnections);
        this.connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnections);

        this.socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.ofMinutes(RESPONSE_TIMEOUT_MINUTES))
                .build();
        connectionManager.setDefaultSocketConfig(socketConfig);

        // Validate connections idle >5s before reuse to prevent NoHttpResponseException from stale connections.
        // The connect timeout is a property of opening a connection, so it lives here, not on the request.
        this.connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .setValidateAfterInactivity(TimeValue.ofSeconds(VALIDATE_AFTER_INACTIVITY_SECONDS))
                .build();
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        this.requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMinutes(CONNECTION_REQUEST_TIMEOUT_MINUTES))
                .setResponseTimeout(Timeout.ofMinutes(RESPONSE_TIMEOUT_MINUTES))
                .build();
        this.idleEviction = TimeValue.ofMinutes(IDLE_EVICTION_MINUTES);

        // Retries are the dispatcher's, never the transport's: Apache's default strategy would
        // re-send a 429 or 503 once on its own, sleeping the retry-after header (or a second)
        // inside the call while the job holds its seat and its connection, and only then hand
        // the failure to the client that classifies it for the retry the framework schedules.
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(idleEviction)
                .build();

        log.info("HttpConnectionPools initialized with {} max connections", maxConnections);
    }

    // The four seams below exist for the package's tests, which pin the pool's numbers and
    // watch a request move through it; the built client exposes none of this.

    /** The connection manager behind the client: route statistics and the per-route maximum. */
    PoolingHttpClientConnectionManager connectionManager() {
        return connectionManager;
    }

    /** The socket config every pooled connection reads with: the socket timeout. */
    SocketConfig socketConfig() {
        return socketConfig;
    }

    /** The connection config every pooled connection is opened with: connect timeout, validate-after-inactivity. */
    ConnectionConfig connectionConfig() {
        return connectionConfig;
    }

    /** The request config every request runs with: connection request timeout, response timeout. */
    RequestConfig requestConfig() {
        return requestConfig;
    }

    /** How long a connection may sit idle before the evictor closes it. */
    TimeValue idleEviction() {
        return idleEviction;
    }

    public static HttpConnectionPools getInstance() {
        return INSTANCE;
    }

    /**
     * Gets the shared HTTP client.
     */
    public CloseableHttpClient getClient() {
        return httpClient;
    }

    /**
     * The admission account a job's demand names when it makes HTTP calls: one permit per
     * admitted job, sized to this pool, so the pool is never the narrower door.
     */
    public HttpConnectionGate gate() {
        return gate;
    }

    /**
     * Gets current pool statistics.
     */
    public PoolStats getStats() {
        return connectionManager.getTotalStats();
    }
}
