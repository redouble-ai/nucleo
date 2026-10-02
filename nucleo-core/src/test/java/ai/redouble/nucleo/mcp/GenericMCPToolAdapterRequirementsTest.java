/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a remote MCP tool holds while it runs is decided by its transport: an HTTP
 * endpoint has no subprocess limiter and must hold a shared HTTP connection instead.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public class GenericMCPToolAdapterRequirementsTest {

    /**
     * A connected HTTP client as the pool hands it out: no limiter of its own.
     */
    static class HttpClientStub implements MCPClient {
        private final HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();

        HttpClientStub() {
            endpoint.setUrl("http://localhost:1/mcp");
        }

        @Override
        public RateLimiter<Void> getRateLimiter() {
            return null;
        }

        @Override
        public List<MCPToolDescriptor> listTools() {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public MCPToolResult callTool(String toolName, JsonNode arguments) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public MCPServerInfo getServerInfo() {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public MCPEndpoint getEndpoint() {
            return endpoint;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void httpToolHoldsASharedHttpConnectionAndNoLimiter() {
        MCPToolDescriptor descriptor = new MCPToolDescriptor();
        descriptor.setName("remote_echo");
        GenericMCPToolAdapter adapter = new GenericMCPToolAdapter(Job.workflow("tester", "mcp-requirements-test"),
                new HttpClientStub(), descriptor, new MCPHandle("remote"));
        JobRequirements requirements = adapter.getRequirements();
        assertTrue(requirements.requiresHttpConnection());
        assertTrue(requirements.getCustomRateLimiters().isEmpty());
    }

    @Test
    void aNullLimiterIsRefusedAtDeclaration() {
        JobRequirements requirements = new JobRequirements();
        assertThrows(IllegalArgumentException.class, () -> requirements.requireRateLimiter(null, null));
    }
}
