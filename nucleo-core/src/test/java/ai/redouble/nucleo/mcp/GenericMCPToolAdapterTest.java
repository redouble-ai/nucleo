/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The adapter's branch: try typed rehydration, fall back to the generic walker. The choice
 * is made from what is in the payload, never from knowing who the server is, which is what
 * lets one code path serve both a redouble server and any third-party one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class GenericMCPToolAdapterTest {

    @TypeAlias("mcp-adapter-fixture")
    public static class AdapterThing extends AbstractArtifact {
        @LLMDescription("What it is called")
        private String label;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    /** Answers one prepared payload; every other capability is out of this test's scope. */
    static final class StubClient implements MCPClient {
        private final String payload;

        StubClient(String payload) {
            this.payload = payload;
        }

        @Override
        public MCPToolResult callTool(String toolName, JsonNode arguments) {
            MCPContent content = new MCPContent();
            content.setType("text");
            content.setText(payload);
            MCPToolResult result = new MCPToolResult();
            result.setContent(List.of(content));
            return result;
        }

        @Override
        public RateLimiter<Void> getRateLimiter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MCPToolDescriptor> listTools() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MCPServerInfo getServerInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public MCPEndpoint getEndpoint() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static MCPToolResult callWith(String payload) throws Exception {
        MCPToolDescriptor descriptor = new MCPToolDescriptor();
        descriptor.setName("probe");
        GenericMCPToolAdapter adapter = new GenericMCPToolAdapter(
                Job.workflow("adapter-test", "adapter-test"), new StubClient(payload), descriptor, new MCPHandle("stub"));
        adapter.setInput(new MCPToolInput());
        return adapter.execute(null, null);
    }

    @BeforeEach
    void registerTypes() {
        TypeAliasRegistry.register(AdapterThing.class);
    }

    @Test
    void aRedoubleServersArtifactArrivesTyped() throws Exception {
        AdapterThing sent = new AdapterThing();
        sent.setLabel("served");
        new ArtifactRegistry().indexReachableFrom(sent);

        MCPToolResult result = callWith(NucleoJsonSerializer.write(sent));
        assertInstanceOf(AdapterThing.class, result.getResultArtifact(),
                "the adapter must consult the rehydrator before the walker");
        assertEquals("served", ((AdapterThing)result.getResultArtifact()).getLabel());
    }

    @Test
    void aThirdPartyServerStillGetsTheGenericWalk() throws Exception {
        MCPToolResult result = callWith("{\"results\":[{\"title\":\"an ordinary MCP answer\"}]}");
        assertInstanceOf(MCPArtifact.class, result.getResultArtifact(),
                "nothing in that payload names a type we hold, so the generic path stands");
    }

    @Test
    void anUnresolvableAliasFallsBackRatherThanFailing() throws Exception {
        MCPToolResult result = callWith("{\"artifact_ref\":\"«artifact:nothing-we-know~a7f3b2»\",\"title\":\"x\"}");
        assertInstanceOf(MCPArtifact.class, result.getResultArtifact());
    }
}
