/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.server.fixtures.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate below the root of a recursive input. A recursive type is published once under
 * {@code $defs} and referred to from every later occurrence, and a gate that judged the
 * reference itself would judge nothing: a node with only {@code $ref} has no {@code type},
 * and everything inside it would pass. So the gate follows the reference, and the same
 * three laws hold at every depth of the tree.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class McpInputGateReferenceTest {
    private static final String CANARY = "zq7-canary-3vx";
    private static final ToolProvider PLANT = ClassToolProvider.of(PlantTool.class);

    private static JsonNode tree(String json) throws Exception {
        return NucleoJsonSerializer.readTree(json);
    }

    @Test
    void thePublishedInputSchemaCarriesAReference() throws Exception {
        JsonNode schema = tree(PLANT.schemaJson());
        assertEquals("#/$defs/TreeInput", schema.get("properties").get("children").get("items").get("$ref").asText(), schema.toString());
        assertNotNull(schema.get("$defs").get("TreeInput"));
    }

    @Test
    void aWellFormedTreeIsAdmittedAtEveryDepth() throws Exception {
        JsonNode args = tree("{\"label\":\"root\",\"children\":[{\"label\":\"a\",\"children\":[{\"label\":\"aa\"}]},{\"label\":\"b\"}]}");
        assertDoesNotThrow(() -> McpInputGate.admit(PLANT, args));
    }

    @Test
    void anUndeclaredPropertyTwoLevelsDownIsRefusedWithoutEcho() throws Exception {
        JsonNode args = tree("{\"label\":\"root\",\"children\":[{\"label\":\"a\",\"children\":[{\"label\":\"aa\",\"" + CANARY + "\":\"x\"}]}]}");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> McpInputGate.admit(PLANT, args));
        assertFalse(refused.getLLMMessage().contains(CANARY), refused.getLLMMessage());
        assertTrue(refused.getLLMMessage().contains("children[].children[]"), "the refusal names where: " + refused.getLLMMessage());
        assertTrue(refused.getLLMMessage().contains("label, children"), "and what is accepted there: " + refused.getLLMMessage());
    }

    @Test
    void aWrongTypeTwoLevelsDownIsRefused() throws Exception {
        JsonNode args = tree("{\"label\":\"root\",\"children\":[{\"label\":\"a\",\"children\":[{\"label\":42}]}]}");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> McpInputGate.admit(PLANT, args));
        assertTrue(refused.getLLMMessage().contains("children[].children[].label"), refused.getLLMMessage());
        assertTrue(refused.getLLMMessage().contains("must be a string"), refused.getLLMMessage());
    }

    @Test
    void aMissingRequiredPropertyBelowTheRootIsRefused() throws Exception {
        JsonNode args = tree("{\"label\":\"root\",\"children\":[{\"children\":[]}]}");
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> McpInputGate.admit(PLANT, args));
        assertTrue(refused.getLLMMessage().contains("'children[]'"), refused.getLLMMessage());
        assertTrue(refused.getLLMMessage().contains("'label'"), refused.getLLMMessage());
    }
}
