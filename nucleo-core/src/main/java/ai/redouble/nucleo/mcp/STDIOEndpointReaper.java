/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import org.slf4j.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Background daemon that closes the clients of idle STDIO MCP endpoints. An endpoint whose
 * admission account has been idle past {@link STDIOEndpointPool#getIdleTimeout()} has its
 * client evicted from {@link MCPClientPool}; closing the client kills the subprocess the
 * SDK transport owns, and the endpoint's next use connects fresh.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class STDIOEndpointReaper {
    private static final Logger log = LoggerFactory.getLogger(STDIOEndpointReaper.class);
    private static final long CHECK_INTERVAL_SECONDS = 60;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static ScheduledExecutorService scheduler;

    private STDIOEndpointReaper() {
    }

    /**
     * Starts the reaper daemon. Called automatically by STDIOEndpointPool.
     */
    static synchronized void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MCP-STDIO-Reaper");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                    STDIOEndpointReaper::checkIdleEndpoints,
                    CHECK_INTERVAL_SECONDS,
                    CHECK_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
            log.info("MCP STDIO endpoint reaper started (check interval: {}s)", CHECK_INTERVAL_SECONDS);
        }
    }

    /**
     * Shuts down the reaper daemon.
     */
    static synchronized void shutdown() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                }
                catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                scheduler = null;
            }
            log.info("MCP STDIO endpoint reaper stopped");
        }
    }

    private static void checkIdleEndpoints() {
        int evicted = reap(Instant.now().minus(STDIOEndpointPool.getIdleTimeout()));
        if (evicted > 0) {
            log.info("MCP STDIO reaper evicted {} idle endpoint client(s)", evicted);
        }
    }

    /**
     * One sweep: every endpoint whose account holds no permits and saw no activity since
     * the cutoff loses its cached client. Package-private for its test.
     */
    static int reap(Instant cutoff) {
        int evicted = 0;
        for (STDIOEndpointRateLimiter limiter : STDIOEndpointPool.getAllLimiters()) {
            if (limiter.isIdle() && limiter.getLastActivity().isBefore(cutoff)
                    && MCPClientPool.evict(limiter.getEndpoint().getEndpointId())) {
                evicted++;
            }
        }
        return evicted;
    }
}
