/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.server.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.util.*;

/**
 * Conservative JSON-Schema normalization for MCP server schemas before they are placed in
 * the LLM-facing {@code ToolDefinitionBlock}. The goal is faithful pass-through: only the
 * smallest set of transformations needed for the schema to be readable by a model and
 * accepted by every provider tool API. Semantically meaningful constructs ({@code oneOf},
 * {@code anyOf}, {@code allOf}, {@code not}, {@code if}/{@code then}/{@code else},
 * {@code const}, {@code enum}, {@code pattern}, {@code format}, numeric / array bounds,
 * {@code additionalProperties: false}, {@code dependentRequired}, {@code dependentSchemas},
 * {@code propertyNames}) flow through verbatim.
 *
 * <p>Operations:
 * <ol>
 *   <li>Inline same-document {@code $ref} targets ({@code #/definitions/Foo},
 *       {@code #/$defs/Foo}), through {@link SchemaRewrites#inlineForeignRefs}. Inlined
 *       because a model reads a definition where it is used and the reference MCP SDKs
 *       publish inlined schemas by default; a model that met a bare {@code $ref} has been
 *       seen to stringify the object or send null. External or unresolvable refs collapse
 *       to {@code {"type": "object"}}. A cyclic ref is the one shape that cannot be
 *       inlined: it keeps its {@code $ref} and its definition stays under {@code $defs},
 *       which every provider this process talks to accepts.</li>
 *   <li>Strip provider-refused keywords: {@code unevaluatedProperties},
 *       {@code unevaluatedItems}, {@code contentEncoding}, {@code contentMediaType}.
 *       Logged at debug.</li>
 *   <li>Validate top-level shape: schemas without {@code "type": "object"} or a
 *       {@code "properties"} map are rejected. The caller (typically
 *       {@code MCPToolProvider}) catches and skips the offending tool while letting
 *       the rest of the catalog register.</li>
 * </ol>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public final class MCPSchemaNormalizer {
    private static final Logger log = LoggerFactory.getLogger(MCPSchemaNormalizer.class);

    /** Keywords that some provider tool APIs refuse outright. */
    private static final Set<String> KEYWORDS_TO_STRIP = Set.of(
            "unevaluatedProperties",
            "unevaluatedItems",
            "contentEncoding",
            "contentMediaType");

    private MCPSchemaNormalizer() {
    }

    /**
     * Normalizes the supplied schema. The input is treated as immutable - the caller's
     * tree is never mutated; a deep copy is returned.
     *
     * @throws IllegalArgumentException if the schema's top-level type is not an object
     */
    public static JsonNode normalize(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            ObjectNode empty = NucleoJsonSerializer.createObjectNode();
            empty.put("type", "object");
            return empty;
        }
        if (!raw.isObject()) {
            throw new IllegalArgumentException(
                    "Top-level MCP input schema must be a JSON object, got: " + raw.getNodeType());
        }
        ObjectNode out = (ObjectNode) raw.deepCopy();
        SchemaRewrites.inlineForeignRefs(out);
        stripKeywords(out);
        requireObjectTopLevel(out);
        return out;
    }

    private static void stripKeywords(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (String k : KEYWORDS_TO_STRIP) {
                if (obj.has(k)) {
                    log.debug("MCPSchemaNormalizer: stripping unsupported keyword '{}'", k);
                    obj.remove(k);
                }
            }
            Iterator<JsonNode> children = obj.elements();
            while (children.hasNext()) {
                stripKeywords(children.next());
            }
        }
        else if (node.isArray()) {
            for (JsonNode child : node) {
                stripKeywords(child);
            }
        }
    }

    private static void requireObjectTopLevel(ObjectNode root) {
        boolean isObject = root.has("type")
                && root.get("type").isTextual()
                && "object".equals(root.get("type").asText());
        boolean hasProperties = root.has("properties") && root.get("properties").isObject();
        if (!isObject && !hasProperties) {
            String typeText = root.has("type") ? root.get("type").toString() : "<unset>";
            throw new IllegalArgumentException(
                    "MCP input schema must have top-level type=object or a properties map (got type=" + typeText + ")");
        }
        if (!isObject) {
            root.put("type", "object");
        }
    }
}
