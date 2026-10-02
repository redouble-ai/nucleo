/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static ai.redouble.nucleo.mcp.server.SchemaRewrites.*;

/**
 * The spellings in which one tool contract is published over MCP.
 *
 * <p>A tool's input and output schema is one contract. The canonical spelling is the one
 * the Model Context Protocol specifies, JSON Schema 2020-12 with every keyword the
 * generator emits, and it is what a request that names no dialect gets. Every other
 * dialect is a narrowing of it for a consumer that accepts less: a client that cannot
 * resolve a reference, a grammar-constrained mode that forbids recursion or demands closed
 * objects, a model API that admits three root keys. A client picks its dialect per
 * request, as an optional parameter: the {@code _meta} key {@link #META_KEY} on any MCP
 * request, or whatever the host's transport forwards into the same slot (a host may
 * forward a {@code dialect} query parameter). The value is {@link #wireName()}; an
 * unknown value is refused with the accepted ones named.
 *
 * <p>All of it is serialization. The set of valid argument documents and the results never
 * change between dialects, and the gate judges every call against the canonical schema
 * whichever spelling the caller read. One dialect changes what a caller SENDS rather than
 * what it reads: {@link #OPENAI_STRICT} spells an optional property as required plus
 * nullable, so a model behind it sends an explicit null where a canonical caller omits the
 * key; {@link #arguments} is that dialect's inverse, guided by the canonical schema so that
 * only a declared optional's null becomes an omission, and the identity everywhere else.
 *
 * <p>Each dialect's rules come from the vendor's published subset and are pinned by test
 * against it. When a vendor moves, the test moves and the dialect keeps its name.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public enum SchemaDialect {
    /** JSON Schema 2020-12 as generated. The foundation's form; what a request that names no dialect gets. */
    CANONICAL {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            return copyOf(canonical);
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            return copyOf(canonical);
        }
    },

    /**
     * No reference anywhere, for a client that cannot resolve one. The first occurrence of
     * a cyclic type stays in full and the reference that closed the cycle becomes a titled
     * object with no shape. The harness keywords go too: a client this far down the ladder
     * is not one of ours.
     */
    FLAT {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            ObjectNode schema = copyOf(canonical);
            // Flat narrows structure, not vocabulary, so the only rule it stops spelling is
            // the recursion it inlines away; shallowObject says that where it happens.
            inlineLocalRefs(schema, SchemaRewrites::shallowObject);
            removeNucleoKeywords(schema);
            return schema;
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            return inputSchema(canonical);
        }
    },

    /**
     * OpenAI's strict subset: every object closed, every property required with the
     * optional ones nullable, references and recursion kept, only the keywords its
     * supported list names.
     */
    OPENAI_STRICT {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            ObjectNode schema = copyOf(canonical);
            describeWhatWillBeStripped(schema, OPENAI_KEYWORDS, VENDOR_FORMATS);
            removeKeywords(schema, name -> !OPENAI_KEYWORDS.contains(name));
            restrictFormats(schema, VENDOR_FORMATS);
            closeObjects(schema);
            requireEveryProperty(schema);
            return schema;
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            ObjectNode schema = copyOf(canonical);
            removeKeywords(schema, name -> !OPENAI_KEYWORDS.contains(name));
            restrictFormats(schema, VENDOR_FORMATS);
            return schema;
        }

        @Override
        public JsonNode arguments(JsonNode wire, JsonNode canonicalInputSchema) {
            JsonNode copy = wire.deepCopy();
            dropNullsAtOptionalProperties(copy, canonicalInputSchema, canonicalInputSchema);
            return copy;
        }
    },

    /**
     * Anthropic's strict subset: every object closed, no recursion, no numeric or length
     * constraints, only the listed string formats. References are allowed but a recursive
     * one is not, so the document is inlined and the cycle broken.
     */
    ANTHROPIC_STRICT {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            return vendorStrictInput(canonical);
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            return vendorStrictOutput(canonical);
        }
    },

    /**
     * Bedrock's strict subset, published by Amazon as the same rule set as Anthropic's. A
     * separate name because the two vendors publish it separately and may diverge.
     */
    BEDROCK_STRICT {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            return vendorStrictInput(canonical);
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            return vendorStrictOutput(canonical);
        }
    },

    /**
     * Amazon Nova on the Converse API: the root carries only {@code type}, {@code properties}
     * and {@code required}; nothing is referenced; no titles. Nova documents the root rule
     * only, and titles are removed at every level because it documents nothing for them.
     */
    NOVA {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            ObjectNode schema = copyOf(canonical);
            describeWhatWillBeStripped(schema, NOVA_KEYWORDS, VENDOR_FORMATS);
            inlineLocalRefs(schema, SchemaRewrites::shallowObject);
            removeKeywords(schema, name -> TITLE.equals(name) || name.startsWith(ai.redouble.nucleo.harness.schema.NucleoSchemaKeywords.PREFIX));
            restrictRoot(schema, NOVA_ROOT);
            return schema;
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            return inputSchema(canonical);
        }
    },

    /**
     * Gemini function declarations: the reference keywords spelled {@code ref} and
     * {@code defs}, only the attributes its list names, {@code format} for date-time alone.
     * Recursion stays as a reference, which Gemini follows to depth two.
     */
    GEMINI {
        @Override
        public JsonNode inputSchema(JsonNode canonical) {
            ObjectNode schema = copyOf(canonical);
            describeWhatWillBeStripped(schema, GEMINI_KEYWORDS, Set.of("date-time"));
            removeKeywords(schema, name -> !GEMINI_KEYWORDS.contains(name));
            restrictFormats(schema, Set.of("date-time"));
            renameRefsForGemini(schema);
            return schema;
        }

        @Override
        public JsonNode outputSchema(JsonNode canonical) {
            return inputSchema(canonical);
        }
    };

    /** OpenAI's supported keywords for strict schemas, per its supported-schemas section. */
    private static final Set<String> OPENAI_KEYWORDS = Set.of(
            TYPE, TITLE, DESCRIPTION, PROPERTIES, REQUIRED, ITEMS, ANY_OF, REF, DEFS, ADDITIONAL_PROPERTIES,
            "enum", "const", "format", "pattern",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minItems", "maxItems");
    /** Keywords Anthropic and Bedrock strict accept; numeric and length constraints are not among them. */
    private static final Set<String> VENDOR_STRICT_KEYWORDS = Set.of(
            TYPE, TITLE, DESCRIPTION, PROPERTIES, REQUIRED, ITEMS, ANY_OF, ALL_OF, ADDITIONAL_PROPERTIES,
            "enum", "const", "format", "default", "minItems");
    /** The string formats the strict modes list. */
    private static final Set<String> VENDOR_FORMATS = Set.of(
            "date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid");
    /** Gemini's supported schema attributes, per its function-calling reference. */
    private static final Set<String> GEMINI_KEYWORDS = Set.of(
            TYPE, DESCRIPTION, PROPERTIES, REQUIRED, ITEMS, ANY_OF, REF, DEFS,
            "nullable", "format", "enum");
    private static final Set<String> NOVA_ROOT = Set.of(TYPE, PROPERTIES, REQUIRED);
    /** What Nova keeps below its root; the root itself keeps only {@link #NOVA_ROOT}. */
    private static final Set<String> NOVA_KEYWORDS = Set.of(
            TYPE, DESCRIPTION, PROPERTIES, REQUIRED, ITEMS, ADDITIONAL_PROPERTIES,
            "enum", "format", "minimum", "maximum");

    /**
     * The {@code _meta} key a request selects its dialect with, on any MCP request:
     * {@code "ai.redouble/dialect"}, in the reverse-DNS form the protocol reserves for
     * implementation metadata. The value is a {@link #wireName()}; absent means
     * {@link #CANONICAL}. A host's transport may forward the same value from wherever its
     * clients can reach - a {@code dialect} query parameter, for a client whose only
     * configurable surface is its URL - by putting it on the transport context under this
     * same key.
     */
    public static final String META_KEY = "ai.redouble/dialect";

    /** The value a request names this dialect by: the enum name, lowercase. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The dialect a request named, or null for a name that is no dialect. Null rather than
     * a throw because the name is the caller's text: the refusal is composed by the caller
     * of this method, from {@link #wireNames()}, which are ours.
     */
    public static SchemaDialect named(String wireName) {
        for (SchemaDialect dialect : values()) {
            if (dialect.wireName().equals(wireName)) {
                return dialect;
            }
        }
        return null;
    }

    /** Every accepted dialect name, comma separated: what a refusal of an unknown one may say. */
    public static String wireNames() {
        List<String> names = new ArrayList<>();
        for (SchemaDialect dialect : values()) {
            names.add(dialect.wireName());
        }
        return String.join(", ", names);
    }

    /** The published input schema, as a new tree. */
    public abstract JsonNode inputSchema(JsonNode canonical);

    /** The published output schema, as a new tree. Strict modes bind what a model generates, and a model never generates a result. */
    public abstract JsonNode outputSchema(JsonNode canonical);

    /**
     * The caller's arguments as canonical arguments: the inverse of whatever this dialect
     * changed about the wire form, guided by the canonical input schema so it undoes exactly
     * the spelling and nothing more. Identity for every dialect that changes only the
     * description. OPENAI_STRICT removes a null where the schema declares an optional
     * property; a null on a required property or under an undeclared name is left in place,
     * because judging those is the gate's, and the gate refuses both.
     *
     * @param wire the arguments as the caller sent them
     * @param canonicalInputSchema the tool's canonical input schema, the one the gate judges by
     */
    public JsonNode arguments(JsonNode wire, JsonNode canonicalInputSchema) {
        return wire;
    }

    private static ObjectNode vendorStrictInput(JsonNode canonical) {
        ObjectNode schema = copyOf(canonical);
        describeWhatWillBeStripped(schema, VENDOR_STRICT_KEYWORDS, VENDOR_FORMATS);
        // Closed before the cycles are broken, never after. Inlining copies these closed
        // definitions into place, but the node it stands a broken cycle up as declares no
        // properties on purpose: closing that one would say the shape it replaces accepts
        // nothing, which is the opposite of what it replaces.
        closeObjects(schema);
        inlineLocalRefs(schema, SchemaRewrites::shallowObject);
        removeKeywords(schema, name -> !VENDOR_STRICT_KEYWORDS.contains(name));
        restrictFormats(schema, VENDOR_FORMATS);
        return schema;
    }

    private static ObjectNode vendorStrictOutput(JsonNode canonical) {
        ObjectNode schema = copyOf(canonical);
        inlineLocalRefs(schema, SchemaRewrites::shallowObject);
        removeKeywords(schema, name -> !VENDOR_STRICT_KEYWORDS.contains(name));
        restrictFormats(schema, VENDOR_FORMATS);
        return schema;
    }
}
