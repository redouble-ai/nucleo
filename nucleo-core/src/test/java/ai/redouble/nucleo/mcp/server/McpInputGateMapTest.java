/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A map crosses the boundary as an object whose keys are the caller's: the canonical closure
 * leaves it open, and the gate judges every entry against the one value shape the schema states.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-09)
 */
public class McpInputGateMapTest {
    private static final String CANARY = "zq7-canary-3vx";

    public static class Tally {
        @LLMDescription("Count per key")
        private Map<String, Long> counts;
        @LLMDescription("Whatever per key")
        private Map<String, Object> anything;

        public Map<String, Long> getCounts() {
            return counts;
        }

        public void setCounts(Map<String, Long> counts) {
            this.counts = counts;
        }

        public Map<String, Object> getAnything() {
            return anything;
        }

        public void setAnything(Map<String, Object> anything) {
            this.anything = anything;
        }
    }

    private static JsonNode published() throws Exception {
        ObjectNode schema = (ObjectNode)NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Tally.class).toJsonSchema());
        SchemaRewrites.closeObjects(schema);
        return schema;
    }

    private static JsonNode tree(String json) throws Exception {
        return NucleoJsonSerializer.readTree(json);
    }

    @Test
    void closureLeavesAMapOpenAndClosesTheObjectAroundIt() throws Exception {
        JsonNode schema = published();
        assertFalse(schema.get("additionalProperties").asBoolean(), "the root is closed");
        assertTrue(schema.path("properties").path("counts").path("additionalProperties").isObject(), "the map keeps its value shape: " + schema);
    }

    @Test
    void mapEntriesOfTheStatedShapeAreAdmitted() throws Exception {
        JsonNode args = tree("{\"counts\":{\"a\":1,\"b\":2},\"anything\":{\"k\":[1,2],\"j\":{\"deep\":true}}}");
        assertDoesNotThrow(() -> McpInputGate.admit("tally", published(), args));
    }

    @Test
    void aMapEntryOfTheWrongShapeIsRefusedUnderItsKey() throws Exception {
        JsonNode args = tree("{\"counts\":{\"a\":1,\"b\":\"x\"}}");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> McpInputGate.admit("tally", published(), args));
        assertTrue(refused.getLLMMessage().contains("counts.b"), refused.getLLMMessage());
        assertTrue(refused.getLLMMessage().contains("must be an integer"), refused.getLLMMessage());
    }

    @Test
    void theObjectAroundAMapIsStillClosed() throws Exception {
        JsonNode args = tree("{\"counts\":{\"a\":1},\"" + CANARY + "\":1}");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> McpInputGate.admit("tally", published(), args));
        assertFalse(refused.getLLMMessage().contains(CANARY), refused.getLLMMessage());
        assertTrue(refused.getLLMMessage().contains("not declared"), refused.getLLMMessage());
    }
}
