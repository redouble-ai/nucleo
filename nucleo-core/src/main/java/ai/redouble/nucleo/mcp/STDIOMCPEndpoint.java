/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson2.*;
import io.modelcontextprotocol.spec.*;

import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * STDIO transport endpoint - spawns a subprocess and communicates via stdin/stdout.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class STDIOMCPEndpoint implements MCPEndpoint {
    private String name;
    private String command;
    private List<String> args;
    private Map<String, String> environment;
    private Duration requestTimeout;

    public STDIOMCPEndpoint() {
    }

    @Override
    public MCPTransportType getTransportType() {
        return MCPTransportType.STDIO;
    }

    @Override
    public String getEndpointId() {
        StringBuilder sb = new StringBuilder(command);
        if (args != null && !args.isEmpty()) {
            sb.append(' ').append(String.join(" ", args));
        }
        // Two endpoints that spawn the same binary with the same args but a
        // different environment (e.g. APP_FS_ROOT pointing at different sandbox
        // roots) are genuinely different processes: env is startup scope for most
        // MCP servers. Without env in the identity they collapse to one cached
        // subprocess in STDIOEndpointPool / MCPClientPool and their state
        // cross-contaminates. The env is hashed rather than inlined because it
        // commonly carries secrets (API keys) and this identity is logged and
        // used as a map key. Endpoints with no env keep their original id.
        if (environment != null && !environment.isEmpty()) {
            sb.append(" #env:").append(hashEnvironment());
        }
        return sb.toString();
    }

    /**
     * Deterministic, order-independent short digest of the environment map:
     * entries are sorted by key and rendered as {@code key=value\n} before
     * hashing. Returns the first 12 hex chars of the SHA-256, enough to separate
     * the handful of endpoints that differ only by env without bloating the id.
     */
    private String hashEnvironment() {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(environment).entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform spec; absence is a broken JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Human-readable logical name (e.g. {@code "brave-search"}, {@code "playwright"})
     * used by {@link STDIOEndpointRateLimiter#limiterName()} as the health-snapshot
     * label. Distinct from {@link #getEndpointId()}, which is the command-line-derived
     * uniqueness key for client caching and refcounting.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public McpClientTransport createTransport(ObjectMapper objectMapper) {
        ServerParameters.Builder builder = ServerParameters.builder(command);
        if (args != null) {
            builder.args(args);
        }
        if (environment != null) {
            builder.env(environment);
        }
        ServerParameters params = builder.build();
        return new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public Duration getRequestTimeout() {
        return requestTimeout != null ? requestTimeout : MCPEndpoint.super.getRequestTimeout();
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
