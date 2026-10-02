/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.server.fixtures.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The exposable set: what a scan admits, what it refuses, and how explicit providers
 * merge with it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public class McpToolCatalogTest {
    static final String FIXTURES = "ai.redouble.nucleo.mcp.server.fixtures";

    @Test
    void scanAdmitsExposableToolsOnly() {
        McpToolCatalog catalog = new McpToolCatalog(List.of(FIXTURES), List.of());
        Set<String> names = new TreeSet<>();
        catalog.all().forEach(p -> names.add(p.name()));
        assertEquals(Set.of("mcp_echo", "mcp_ping", "mcp_faulty", "mcp_slow_parent", "mcp_citing", "mcp_strict", "mcp_tree", "mcp_refs", "mcp_plant"), names);
        assertNull(catalog.byName("mcp_slow_child"), "a tool without @MCP is not exposable");
        assertNull(catalog.byName("no_such_tool"));
    }

    @Test
    void sameClassViaScanAndExplicitIsOneEntry() {
        McpToolCatalog catalog = new McpToolCatalog(List.of(FIXTURES), List.of(ClassToolProvider.of(EchoTool.class)));
        assertEquals(9, catalog.all().size());
        assertEquals(EchoTool.class, catalog.byName("mcp_echo").toolClass());
    }

    @Test
    void exposableWithoutNameRefusesTheScan() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new McpToolCatalog(List.of("ai.redouble.nucleo.mcp.server.brokenfixtures"), List.of()));
        assertTrue(e.getMessage().contains("NamelessTool"), e.getMessage());
    }

    @Test
    void twoClassesOneNameRefuse() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new McpToolCatalog(List.of("ai.redouble.nucleo.mcp.server.collisionfixtures"), List.of()));
        assertTrue(e.getMessage().contains("AlphaTool") && e.getMessage().contains("BetaTool"), e.getMessage());
    }

    @Test
    void unpublishableSchemaRefuses() {
        ToolProvider schemaless = new ToolProvider() {
            @Override
            public String name() {
                return "mcp_schemaless";
            }

            @Override
            public String description() {
                return "no schema";
            }

            @Override
            public String schemaJson() {
                return null;
            }

            @Override
            public ToolWeight weight() {
                return DefaultToolWeights.API_CALL_DEFAULT;
            }

            @Override
            public String displayName() {
                return null;
            }

            @Override
            public String actionVerb() {
                return null;
            }

            @Override
            public Class<?> inputType() {
                return NoFieldsInput.class;
            }

            @Override
            public Class<? extends Tool> toolClass() {
                return PingTool.class;
            }

            @Override
            public Object parseInput(JsonNode raw) {
                return new NoFieldsInput();
            }

            @Override
            public Tool<?, ?> create(Identifiable parent) {
                return new PingTool(parent);
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new McpToolCatalog(List.of(), List.of(schemaless)));
        assertTrue(e.getMessage().contains("mcp_schemaless"), e.getMessage());
    }
}
