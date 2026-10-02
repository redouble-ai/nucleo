/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.*;

/**
 * The MCP package's knobs: the STDIO subprocess admission caps, the idle eviction timeout,
 * and the description ceiling on tools surfaced to the LLM.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public class McpSettings extends Settings {

    /** Pool-wide cap on concurrent STDIO subprocess slots - the {@code mcp:stdio} admission account. Read once when {@link STDIOEndpointPool} initializes. */
    public volatile int stdioPoolMax = 20;

    /** Per-endpoint cap on concurrent subprocess slots. Read when an endpoint's limiter is created. */
    public volatile int stdioMaxConcurrentPerEndpoint = 10;

    /** Seconds an endpoint sits idle before {@link STDIOEndpointReaper} evicts its client (closing it kills the subprocess). */
    public volatile int stdioIdleTimeoutSeconds = 300;

    /** Truncation cap on MCP tool descriptions surfaced to the LLM. */
    public volatile int descMaxChars = 4096;
}
