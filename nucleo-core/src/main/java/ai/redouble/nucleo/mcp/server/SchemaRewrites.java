/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.*;

/**
 * The rewrites the dialects are composed from, and the reference inliner the inbound
 * normalizer shares with them. Every rewrite works on a copy the caller made and mutates it
 * in place; none reads anything but the tree it is handed.
 *
 * <p>A "schema node" is an object that describes a value: the root, each value under
 * {@code properties}, {@code items}, each entry under {@code $defs}, and each branch of
 * {@code anyOf}, {@code oneOf}, {@code allOf}. The {@code properties} map itself is not one,
 * which is why the walk is written out rather than visiting every object in the tree.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public final class SchemaRewrites {
    static final String TYPE = "type";
    static final String TITLE = "title";
    static final String DESCRIPTION = "description";
    static final String MINIMUM = "minimum";
    static final String MAXIMUM = "maximum";
    static final String FORMAT = "format";
    static final String PROPERTIES = "properties";
    static final String REQUIRED = "required";
    static final String ITEMS = "items";
    static final String ANY_OF = "anyOf";
    static final String ONE_OF = "oneOf";
    static final String ALL_OF = "allOf";
    static final String REF = "$ref";
    static final String DEFS = "$defs";
    static final String DEFINITIONS = "definitions";
    static final String DEFS_POINTER = "#/$defs/";
    static final String DEFINITIONS_POINTER = "#/definitions/";
    static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    static final String OBJECT = "object";
    static final String NULL = "null";

    private SchemaRewrites() {
    }

    static ObjectNode copyOf(JsonNode canonical) {
        if (!(canonical instanceof ObjectNode object)) {
            throw new IllegalArgumentException("A schema is a JSON object, got " + canonical.getNodeType());
        }
        return object.deepCopy();
    }

    /** Visits every schema node under {@code schema}, {@code schema} first. */
    static void forEachSchemaNode(JsonNode schema, Consumer<ObjectNode> visitor) {
        if (!(schema instanceof ObjectNode node)) {
            return;
        }
        visitor.accept(node);
        JsonNode properties = node.get(PROPERTIES);
        if (properties instanceof ObjectNode props) {
            for (JsonNode property : props) {
                forEachSchemaNode(property, visitor);
            }
        }
        forEachSchemaNode(node.get(ITEMS), visitor);
        for (String section : List.of(DEFS, DEFINITIONS)) {
            JsonNode defs = node.get(section);
            if (defs instanceof ObjectNode definitions) {
                for (JsonNode definition : definitions) {
                    forEachSchemaNode(definition, visitor);
                }
            }
        }
        for (String composition : List.of(ANY_OF, ONE_OF, ALL_OF)) {
            JsonNode branches = node.get(composition);
            if (branches instanceof ArrayNode array) {
                for (JsonNode branch : array) {
                    forEachSchemaNode(branch, visitor);
                }
            }
        }
    }

    /**
     * Writes into each node's {@code description} the rules that node is about to stop
     * spelling, so a caller reading only this dialect still learns what will be enforced.
     *
     * <p>A dialect narrows the description, never the acceptance: the gate refuses the same
     * documents whichever dialect published them. Left alone, that means a caller can obey the schema it
     * fetched and still be refused for a bound its dialect had no keyword for. Every dialect
     * carries {@code description} on every property, so that is where the rule goes when its
     * keyword is taken away.
     *
     * @param keptKeywords the keywords this dialect still spells
     * @param keptFormats the {@code format} values this dialect still spells
     */
    static void describeWhatWillBeStripped(ObjectNode schema, Set<String> keptKeywords, Set<String> keptFormats) {
        forEachSchemaNode(schema, node -> {
            List<String> rules = new ArrayList<>();
            if (!keptKeywords.contains(MINIMUM) && node.get(MINIMUM) != null && node.get(MAXIMUM) != null) {
                rules.add("Accepted range: " + node.get(MINIMUM).asText() + " to " + node.get(MAXIMUM).asText() + ".");
            }
            JsonNode format = node.get(FORMAT);
            if (format != null && (!keptKeywords.contains(FORMAT) || !keptFormats.contains(format.asText()))) {
                rules.add("Must be an ISO-8601 " + format.asText() + ".");
            }
            if (!keptKeywords.contains(ADDITIONAL_PROPERTIES) && Boolean.FALSE.equals(booleanOf(node.get(ADDITIONAL_PROPERTIES)))) {
                rules.add("No other properties are accepted.");
            }
            if (!rules.isEmpty()) {
                append(node, String.join(" ", rules));
            }
        });
    }

    /** Adds a sentence to a node's description, keeping whatever the author wrote first. */
    static void append(ObjectNode node, String sentence) {
        JsonNode existing = node.get(DESCRIPTION);
        String before = existing == null ? "" : existing.asText().trim();
        node.put(DESCRIPTION, before.isEmpty() ? sentence : before + (before.endsWith(".") ? " " : ". ") + sentence);
    }

    private static Boolean booleanOf(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    /** Removes, from every schema node, each keyword the predicate names. */
    static void removeKeywords(ObjectNode schema, Predicate<String> doomed) {
        forEachSchemaNode(schema, node -> {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (doomed.test(name)) {
                    node.remove(name);
                }
            }
        });
    }

    static void removeNucleoKeywords(ObjectNode schema) {
        removeKeywords(schema, name -> name.startsWith(NucleoSchemaKeywords.PREFIX));
    }

    /** Keeps {@code format} only where its value is one the target understands. */
    static void restrictFormats(ObjectNode schema, Set<String> understood) {
        forEachSchemaNode(schema, node -> {
            JsonNode format = node.get(FORMAT);
            if (format != null && !understood.contains(format.asText())) {
                node.remove(FORMAT);
            }
        });
    }

    /**
     * What to do at the two places a reference cannot simply be replaced by its definition.
     */
    private interface RefPolicy {
        /** The node standing in for a reference the document cannot resolve. */
        ObjectNode unresolvable(String ref);

        /** The node standing in for a reference that closes a cycle. */
        ObjectNode atCycle(String name, ObjectNode definition);
    }

    /**
     * Replaces every reference in a CANONICAL schema, one this process generated, with the
     * definition it points at, and removes {@code $defs}. A reference that closes a cycle
     * cannot be inlined; it becomes the node {@code atCycle} builds from the definition,
     * which is how a target that forbids recursion gets the shallow object it accepts. A
     * reference our own generator cannot resolve is a defect and throws as one.
     */
    static void inlineLocalRefs(ObjectNode root, Function<ObjectNode, ObjectNode> atCycle) {
        RefPolicy canonical = new RefPolicy() {
            @Override
            public ObjectNode unresolvable(String ref) {
                throw new IllegalStateException("A canonical schema carries a reference it cannot resolve: " + ref);
            }

            @Override
            public ObjectNode atCycle(String name, ObjectNode definition) {
                return atCycle.apply(definition);
            }
        };
        ObjectNode definitions = definitionsOf(root);
        replaceRoot(root, inline(root, definitions, new HashSet<>(), canonical));
        root.remove(DEFS);
        root.remove(DEFINITIONS);
    }

    /**
     * Replaces every local reference in a FOREIGN schema, one another server published, with
     * its definition. Written for what real servers send: {@code definitions} as well as
     * {@code $defs}, references nothing in the document resolves, references to other
     * documents. An unresolvable reference becomes a bare object, because a tool whose
     * schema we cannot fully read is still a tool worth offering. A cycle keeps its
     * reference and its definition, under {@code $defs}, because that is the one thing
     * that cannot be spelled any other way and every model API we serve accepts it.
     */
    public static void inlineForeignRefs(ObjectNode root) {
        Set<String> retained = new LinkedHashSet<>();
        RefPolicy foreign = new RefPolicy() {
            @Override
            public ObjectNode unresolvable(String ref) {
                ObjectNode bare = NucleoJsonSerializer.createObjectNode();
                bare.put(TYPE, OBJECT);
                return bare;
            }

            @Override
            public ObjectNode atCycle(String name, ObjectNode definition) {
                retained.add(name);
                ObjectNode back = NucleoJsonSerializer.createObjectNode();
                back.put(REF, DEFS_POINTER + name);
                return back;
            }
        };
        ObjectNode definitions = definitionsOf(root);
        replaceRoot(root, inline(root, definitions, new HashSet<>(), foreign));
        root.remove(DEFS);
        root.remove(DEFINITIONS);
        if (retained.isEmpty()) {
            return;
        }
        // A retained definition is inlined like everything else, with itself already on the
        // path, so its own back-reference stays a reference and terminates.
        ObjectNode kept = NucleoJsonSerializer.createObjectNode();
        Deque<String> pending = new ArrayDeque<>(retained);
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (kept.has(name)) {
                continue;
            }
            Set<String> visiting = new HashSet<>();
            visiting.add(name);
            int before = retained.size();
            kept.set(name, inline(definitions.get(name).deepCopy(), definitions, visiting, foreign));
            if (retained.size() > before) {
                pending.addAll(retained);
            }
        }
        root.set(DEFS, kept);
    }

    private static ObjectNode definitionsOf(ObjectNode root) {
        ObjectNode merged = NucleoJsonSerializer.createObjectNode();
        for (String section : List.of(DEFINITIONS, DEFS)) {
            if (root.get(section) instanceof ObjectNode defs) {
                merged.setAll(defs);
            }
        }
        return merged;
    }

    private static void replaceRoot(ObjectNode root, JsonNode rebuilt) {
        root.removeAll();
        root.setAll((ObjectNode)rebuilt);
    }

    /** The definition name a same-document pointer names, or null for any other reference. */
    private static String localName(String ref) {
        if (ref.startsWith(DEFS_POINTER)) {
            return ref.substring(DEFS_POINTER.length());
        }
        if (ref.startsWith(DEFINITIONS_POINTER)) {
            return ref.substring(DEFINITIONS_POINTER.length());
        }
        return null;
    }

    private static JsonNode inline(JsonNode node, ObjectNode definitions, Set<String> visiting, RefPolicy policy) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get(REF);
            if (ref != null && ref.isTextual()) {
                String pointer = ref.asText();
                String name = localName(pointer);
                ObjectNode replacement;
                if (name == null || !(definitions.get(name) instanceof ObjectNode target)) {
                    replacement = policy.unresolvable(pointer);
                }
                else if (visiting.contains(name)) {
                    replacement = policy.atCycle(name, target);
                }
                else {
                    visiting.add(name);
                    replacement = (ObjectNode)inline(target.deepCopy(), definitions, visiting, policy);
                    visiting.remove(name);
                }
                // Keywords beside the reference (a description written at the use site)
                // belong to the use site and win over the definition's, per 2020-12.
                for (Map.Entry<String, JsonNode> sibling : object.properties()) {
                    if (!REF.equals(sibling.getKey())) {
                        replacement.set(sibling.getKey(), sibling.getValue());
                    }
                }
                return replacement;
            }
            ObjectNode rebuilt = NucleoJsonSerializer.createObjectNode();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                rebuilt.set(field.getKey(), inline(field.getValue(), definitions, visiting, policy));
            }
            return rebuilt;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode rebuilt = NucleoJsonSerializer.createArrayNode();
            for (JsonNode element : array) {
                rebuilt.add(inline(element, definitions, visiting, policy));
            }
            return rebuilt;
        }
        return node;
    }

    /**
     * The node a broken cycle leaves behind: the type's name, and a sentence saying the shape
     * repeats, because a dialect that cannot spell recursion still owes the caller the fact
     * that this position takes the same object again.
     */
    static ObjectNode shallowObject(ObjectNode definition) {
        ObjectNode shallow = NucleoJsonSerializer.createObjectNode();
        shallow.put(TYPE, OBJECT);
        JsonNode title = definition.get(TITLE);
        String named = title == null ? "the enclosing object" : title.asText();
        if (title != null) {
            shallow.set(TITLE, title);
        }
        append(shallow, "Repeats the structure of " + named + ", to any depth.");
        return shallow;
    }

    /**
     * Every object node becomes closed: the publisher's statement that the gate refuses an
     * undeclared property at every depth. A map is the one object whose keys are not declared
     * and whose {@code additionalProperties} already states what every value must be; it stays
     * as written, since closing it would forbid every entry.
     */
    public static void closeObjects(ObjectNode schema) {
        forEachSchemaNode(schema, node -> {
            if (isObjectSchema(node) && node.get(ADDITIONAL_PROPERTIES) == null) {
                node.put(ADDITIONAL_PROPERTIES, false);
            }
        });
    }

    /**
     * Every property becomes required, and a property that was optional becomes nullable,
     * so the document still admits the values the canonical schema admits: an omitted
     * optional is spelled as an explicit null.
     */
    static void requireEveryProperty(ObjectNode schema) {
        forEachSchemaNode(schema, node -> {
            JsonNode properties = node.get(PROPERTIES);
            if (!(properties instanceof ObjectNode props)) {
                return;
            }
            Set<String> required = new LinkedHashSet<>();
            JsonNode declared = node.get(REQUIRED);
            if (declared instanceof ArrayNode names) {
                names.forEach(name -> required.add(name.asText()));
            }
            ArrayNode all = NucleoJsonSerializer.createArrayNode();
            List<String> names = new ArrayList<>();
            props.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                all.add(name);
                if (!required.contains(name)) {
                    props.set(name, nullable((ObjectNode)props.get(name)));
                }
            }
            node.set(REQUIRED, all);
        });
    }

    private static ObjectNode nullable(ObjectNode property) {
        ObjectNode wrapped = NucleoJsonSerializer.createObjectNode();
        // The use-site annotations stay on the wrapper, where a reader looks first; the
        // branch keeps only what describes the value.
        for (String annotation : List.of(DESCRIPTION, TITLE)) {
            JsonNode value = property.remove(annotation);
            if (value != null) {
                wrapped.set(annotation, value);
            }
        }
        ArrayNode branches = NucleoJsonSerializer.createArrayNode();
        branches.add(property);
        ObjectNode nul = NucleoJsonSerializer.createObjectNode();
        nul.put(TYPE, NULL);
        branches.add(nul);
        wrapped.set(ANY_OF, branches);
        return wrapped;
    }

    /**
     * The inverse of {@link #requireEveryProperty}: removes a null exactly where the canonical
     * schema declares an optional property, following the schema below the root through
     * {@code properties}, {@code items} and {@code $ref}. Nothing else is touched. A null on a
     * required property stays and is a missing property to the gate; a null under a name the
     * schema does not declare stays and is an undeclared property to the gate. The inverse
     * undoes the spelling and never makes a judgement the gate owns.
     */
    static void dropNullsAtOptionalProperties(JsonNode value, JsonNode schema, JsonNode root) {
        JsonNode resolved = resolveRef(schema, root);
        if (resolved == null) {
            return;
        }
        if (value instanceof ObjectNode object && resolved.get(PROPERTIES) instanceof ObjectNode properties) {
            Set<String> required = new HashSet<>();
            if (resolved.get(REQUIRED) instanceof ArrayNode names) {
                names.forEach(name -> required.add(name.asText()));
            }
            List<String> omitted = new ArrayList<>();
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                JsonNode supplied = object.get(property.getKey());
                if (supplied == null) {
                    continue;
                }
                if (supplied.isNull()) {
                    if (!required.contains(property.getKey())) {
                        omitted.add(property.getKey());
                    }
                }
                else {
                    dropNullsAtOptionalProperties(supplied, property.getValue(), root);
                }
            }
            object.remove(omitted);
        }
        else if (value instanceof ArrayNode array && resolved.get(ITEMS) != null) {
            for (JsonNode element : array) {
                dropNullsAtOptionalProperties(element, resolved.get(ITEMS), root);
            }
        }
    }

    /** The schema node a reference stands for, resolved against the root's {@code $defs}; a non-reference is itself. */
    private static JsonNode resolveRef(JsonNode schema, JsonNode root) {
        if (schema == null || schema.get(REF) == null) {
            return schema;
        }
        String pointer = schema.get(REF).asText();
        if (!pointer.startsWith(DEFS_POINTER)) {
            throw new IllegalStateException("A canonical schema refers outside its own $defs: " + pointer);
        }
        JsonNode target = root.path(DEFS).get(pointer.substring(DEFS_POINTER.length()));
        if (target == null) {
            throw new IllegalStateException("A canonical schema carries a dangling reference: " + pointer);
        }
        return target;
    }

    /** Keeps only the root keywords named; everything else at the root goes. */
    static void restrictRoot(ObjectNode root, Set<String> kept) {
        List<String> names = new ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (!kept.contains(name)) {
                root.remove(name);
            }
        }
    }

    /**
     * Gemini spells the reference keywords without the dollar sign and points at
     * {@code #/defs/}. Same graph, other names.
     */
    static void renameRefsForGemini(ObjectNode root) {
        // References first, while the walk still recognizes the definitions section by
        // its canonical name and descends into it.
        forEachSchemaNode(root, node -> {
            JsonNode ref = node.remove(REF);
            if (ref != null) {
                node.put("ref", "#/defs/" + ref.asText().substring(DEFS_POINTER.length()));
            }
        });
        JsonNode defs = root.remove(DEFS);
        if (defs != null) {
            root.set("defs", defs);
        }
    }

    static boolean isObjectSchema(ObjectNode node) {
        JsonNode type = node.get(TYPE);
        return type != null && OBJECT.equals(type.asText());
    }
}
