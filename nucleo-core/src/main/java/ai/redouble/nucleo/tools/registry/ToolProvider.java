/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;

import java.util.*;

/**
 * Single abstraction for "a tool the framework can present to an LLM and invoke."
 *
 * <p>Carries everything the thinker pipeline used to extract from a class via reflection:
 * name, description, schema, weight, display metadata, input parsing, and a factory
 * that builds the concrete {@link Tool} instance for a given parent.
 *
 * <p>Two concrete implementations live in this package:
 * <ul>
 *   <li>{@link ClassToolProvider} wraps a {@code Class<? extends Tool>} and reads its
 *       {@link ToolName}, {@link ToolDescription}, {@link ai.redouble.nucleo.harness.DisplayName}, {@link ToolWeight}
 *       annotations. The native, framework-shipped path.</li>
 *   <li>{@code MCPToolProvider} (in {@code ai.redouble.nucleo.mcp}) wraps an MCP endpoint plus
 *       a tool descriptor, namespaces the name, and routes calls through the MCP client pool.</li>
 * </ul>
 *
 * <p>{@link ToolRegistry} is keyed by {@link #name()} and stores providers, never classes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public interface ToolProvider {

    /** Unique name within a registry. Used for LLM-facing tool identity. */
    String name();

    /** Human/LLM readable description; truncation is the implementation's choice. */
    String description();

    /** JSON-Schema bytes that go into the {@code ToolDefinitionBlock} schema field. */
    String schemaJson();

    /** Effective weight metadata. */
    ToolWeight weight();

    /** Display name (human-facing UI). Falls back to {@link #name()} when no override. */
    String displayName();

    /** Action verb for status surfacing (e.g. "Searching"). Empty string when unset. */
    String actionVerb();

    /** Input POJO type, used for input-guardrail typing (instanceof matching). */
    Class<?> inputType();

    /**
     * Output POJO type, or null when the provider cannot know it. A class-backed provider
     * reads it off {@code Tool<I,O>}; an MCP-bound provider wraps someone else's tool and
     * has no Java output type, so it answers null.
     *
     * <p>Only the published-schema path consumes this. Nothing model-facing does: a model
     * is told what a tool takes, never what shape comes back, because the answer arrives
     * as a tool result it reads directly.
     */
    default Class<?> outputType() {
        return null;
    }

    /**
     * JSON Schema of {@link #outputType()}, or null when there is no output type. Published
     * as the MCP tool's {@code outputSchema} so a consumer knows what it is receiving.
     */
    default String outputSchemaJson() {
        return null;
    }

    /**
     * Concrete {@link Tool} class produced by {@link #create(Identifiable)}. Used by
     * admission predicates and guardrail registries to test class-assignability without
     * having to instantiate the tool. {@link ClassToolProvider} returns the wrapped class;
     * MCP-bound providers return {@code GenericMCPToolAdapter.class}.
     */
    Class<? extends Tool> toolClass();

    /**
     * Parses LLM-emitted input (already converted to {@link JsonNode} upstream by
     * {@code ThinkingResponseHandler}) into the typed input the tool's {@code execute}
     * expects. Class-based providers convert via the framework JSON serializer; MCP-bound
     * providers wrap the tree as an MCP input envelope without translation.
     *
     * @throws CorrectableLLMException when the LLM produced input the provider cannot parse;
     *     the LLM can correct and retry.
     */
    Object parseInput(JsonNode raw) throws CorrectableLLMException;

    /**
     * Builds the {@link Tool} instance for execution. May throw when the underlying
     * resource (e.g. an MCP client) cannot be obtained right now.
     */
    Tool<?, ?> create(Identifiable parent) throws LLMReadableCheckedException;

    /**
     * Whether invoking this tool leaves the environment unchanged. Mirrors
     * {@link ToolDescription#readOnly()} for class-backed providers.
     *
     * <p>The default is {@code false} because that is the correct answer for a provider
     * that cannot know: an MCP server describes no such property, and {@code request_tools}
     * admits arbitrary tools by construction. Both are genuinely mutating as far as the
     * framework can establish, so the default is the truth rather than a compatibility
     * fallback. A provider returns {@code true} only where something asserted it.
     *
     * <p>Consumed by {@link ReadOnlyPalette} to bound a read-only thinker's palette structurally.
     */
    default boolean readOnly() {
        return false;
    }

    /**
     * Open extension point for non-essential metadata (icons, categories, cost hints) -
     * the slot through which a host-defined provider carries tool information the
     * framework's own surface does not name. The framework reads nothing from it; a
     * deployment's catalog or UI is its consumer. Defaults to an empty map.
     */
    default Map<String, Object> metadata() {
        return Map.of();
    }
}
