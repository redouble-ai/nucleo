/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/**
 * Dynamic {@link ToolProvider} for {@link RequestToolsTool}. Unlike
 * {@link ClassToolProvider}, the JSON schema is built fresh from the supplied
 * catalog of compatible providers, so the LLM sees the current catalog as an
 * enum of valid tool names with per-value descriptions inside the
 * {@code tool_names} array - no free-text catalog dump in the system prompt.
 *
 * <p>The catalog travels in the tool's input schema and nowhere else - never as free
 * text in the prompt - and the LLM provider enforces it natively: any name the model
 * proposes that isn't in the current enum is rejected upstream of
 * {@code request_tools.execute}.
 *
 * <p>One instance per turn - registered by the {@code reconcileToolRegistry} hook of
 * {@link AbstractThinker} with the freshly reconciled
 * catalog, so changes to depth, input, or admission state propagate to the
 * next LLM turn without a server round-trip.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-24)
 */
public final class RequestToolsProvider implements ToolProvider {

    /** Stable name used for registry lookups and unregistration. */
    public static final String NAME = "request_tools";

    /** The input's one field as the wire spells it: {@link RequestToolsInput#getToolNames()} in snake_case. */
    public static final String TOOL_NAMES = "tool_names";

    private static final String DESCRIPTION =
            "Activate tools from the catalog. Pass tool names to load their full schemas so you can call them.";

    private final Set<ToolProvider> catalog;
    private final String schemaJson;

    public RequestToolsProvider(Set<ToolProvider> catalog) {
        this.catalog = catalog;
        this.schemaJson = buildSchema(catalog);
    }

    private static String buildSchema(Set<ToolProvider> catalog) {
        ObjectNode root = NucleoJsonSerializer.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        // snake_case, the spelling every class-backed schema publishes and the one the
        // serializer reads back; a camelCase key here is silently dropped at parse time
        ObjectNode toolNames = properties.putObject(TOOL_NAMES);
        toolNames.put("type", "array");
        toolNames.put("description", "Names of tools to activate from the catalog");

        ObjectNode items = toolNames.putObject("items");
        ArrayNode oneOf = items.putArray("oneOf");
        for (ToolProvider provider : catalog) {
            ObjectNode entry = oneOf.addObject();
            entry.put("const", provider.name());
            entry.put("description", provider.description());
        }

        ArrayNode required = root.putArray("required");
        required.add(TOOL_NAMES);

        return root.toString();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String schemaJson() {
        return schemaJson;
    }

    @Override
    public ToolWeight weight() {
        return DefaultToolWeights.API_CALL_DEFAULT;
    }

    @Override
    public String displayName() {
        return "Request Tools";
    }

    @Override
    public String actionVerb() {
        return "Requesting Tools";
    }

    @Override
    public Class<?> inputType() {
        return RequestToolsInput.class;
    }

    @Override
    public Class<? extends Tool> toolClass() {
        return RequestToolsTool.class;
    }

    @Override
    public Object parseInput(JsonNode raw) throws CorrectableLLMException {
        if (raw == null) {
            throw new InvalidInputException(NAME, "null", "input is null");
        }
        try {
            return NucleoJsonSerializer.convert(raw, RequestToolsInput.class);
        }
        catch (Exception e) {
            // The refusal is composed from the contract, never from the payload: the
            // decoder's complaint quotes what the model sent and stays on the cause
            throw new InvalidInputException(TOOL_NAMES, RequestToolsInput.class.getSimpleName(),
                    "an array of tool names from the catalog", e);
        }
    }

    @Override
    public Tool<?, ?> create(Identifiable parent) throws LLMReadableCheckedException {
        return new RequestToolsTool(parent);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RequestToolsProvider;
    }

    @Override
    public int hashCode() {
        return RequestToolsProvider.class.hashCode();
    }

    @Override
    public String toString() {
        return "RequestToolsProvider[catalog=" + catalog.size() + "]";
    }
}
