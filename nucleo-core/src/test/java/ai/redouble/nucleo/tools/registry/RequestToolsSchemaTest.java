/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.builtin.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@code request_tools}' schema constraint: the tool-name field enumerates the
 * reconciled catalog as {@code oneOf} const entries, so the provider's native schema
 * validation refuses an unrecognized name before it can ever reach admission - the
 * "an unknown name cannot reach admission" claim rests on this schema being exact.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class RequestToolsSchemaTest {

    @Test
    void schemaEnumeratesExactlyTheCatalog() throws IOException {
        Set<ToolProvider> catalog = Set.of(
                ClassToolProvider.of(CurrentTimeTool.class),
                ClassToolProvider.of(DateCalculatorTool.class));
        RequestToolsProvider provider = new RequestToolsProvider(catalog);

        JsonNode schema = NucleoJsonSerializer.readTree(provider.schemaJson());
        JsonNode oneOf = schema.path("properties").path(RequestToolsProvider.TOOL_NAMES).path("items").path("oneOf");
        assertTrue(oneOf.isArray(), "the names field is an enumerated oneOf, not a free string: " + schema);

        Set<String> offered = new HashSet<>();
        for (JsonNode entry : oneOf) {
            offered.add(entry.path("const").asText());
            assertFalse(entry.path("description").asText().isEmpty(),
                    "each entry carries its description so the model can choose informedly");
        }
        assertEquals(Set.of("get_current_time", "calculate_dates"), offered,
                "exactly the catalog's names - nothing more to hallucinate against, nothing missing");
    }

    /**
     * The field the schema publishes is the field the parser reads. The serializer maps
     * snake_case and drops unknown keys silently, so a schema spelling the field any other
     * way makes every model call arrive with no names at all.
     */
    @Test
    void aCallSpelledAsTheSchemaPublishesItParsesToTheNames() throws Exception {
        RequestToolsProvider provider = new RequestToolsProvider(Set.of(ClassToolProvider.of(CurrentTimeTool.class)));
        JsonNode schema = NucleoJsonSerializer.readTree(provider.schemaJson());
        String published = schema.path("required").get(0).asText();
        assertTrue(schema.path("properties").has(published), "the required field is a published property");
        Object parsed = provider.parseInput(NucleoJsonSerializer.readTree("{\"" + published + "\": [\"get_current_time\"]}"));
        assertEquals(java.util.List.of("get_current_time"), ((RequestToolsInput) parsed).getToolNames());
    }

    @Test
    void anUnparseableCallIsACorrectableRefusalThatNeverEchoesThePayload() throws IOException {
        RequestToolsProvider provider = new RequestToolsProvider(Set.of(ClassToolProvider.of(CurrentTimeTool.class)));
        assertThrows(ai.redouble.nucleo.harness.errors.InvalidInputException.class, () -> provider.parseInput(null));
        // the value in an impossible position carries a canary: the refusal is composed from
        // the contract, and the decoder's complaint - which quotes the payload - stays on the cause
        String canary = "zqx7toolcanary";
        ai.redouble.nucleo.harness.errors.InvalidInputException refusal =
                assertThrows(ai.redouble.nucleo.harness.errors.InvalidInputException.class,
                        () -> provider.parseInput(NucleoJsonSerializer.readTree(
                                "{\"" + RequestToolsProvider.TOOL_NAMES + "\": {\"" + canary + "\": 1}}")));
        assertFalse(refusal.getMessage().contains(canary),
                "the refusal never quotes what the model sent: " + refusal.getMessage());
        assertFalse(refusal.getLLMMessage().contains(canary),
                "the model-facing text is composed the same way: " + refusal.getLLMMessage());
        assertNotNull(refusal.getCause(),
                "the decoder's complaint survives on the cause, where only a stack trace carries it");
    }
}
