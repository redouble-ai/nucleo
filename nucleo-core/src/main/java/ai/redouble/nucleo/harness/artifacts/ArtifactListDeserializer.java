/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.*;

import java.io.*;
import java.util.*;

/**
 * Custom deserializer for {@link ListArtifact#getIterands()}.
 *
 * <p>Resolves each iterand's concrete class from the iterand's own serialized
 * {@code artifact_ref} (the ref alias carries the hierarchical type, resolved via
 * {@link TypeAliasRegistry#resolveAliasWithFallback(String)}) - the same mechanism
 * {@link ArtifactMapDeserializer} uses for registry keys. Unlike the map case,
 * where the key precedes the value, each list element is buffered as a tree so the
 * ref can be read before the class is chosen.
 *
 * <p>An iterand without a ref, or with an unresolvable alias, FAILS
 * deserialization. Skipping would silently shorten the list, violating the
 * length-and-order contract list artifacts exist to guarantee. Registered lists
 * always satisfy the ref requirement: {@link ArtifactRegistry} mints iterand refs
 * at registration time.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public class ArtifactListDeserializer extends StdDeserializer<List<Artifact>> {
    public ArtifactListDeserializer() {
        super((Class<?>) null);
    }

    @Override
    public List<Artifact> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            throw new JsonMappingException(p, "Expected START_ARRAY for list artifact items, got " + p.currentToken());
        }
        List<Artifact> result = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            JsonNode node = p.readValueAsTree();
            JsonNode refNode = node.get("artifact_ref");
            String ref = refNode == null || refNode.isNull() ? null : refNode.asText();
            String alias = ArtifactRegistry.extractAliasFromRef(ref);
            Class<?> concreteClass = alias == null ? null : TypeAliasRegistry.resolveAliasWithFallback(alias);
            if (concreteClass == null) {
                throw new JsonMappingException(p, "Cannot resolve iterand type at index " + result.size()
                        + " of list artifact (ref=" + ref + ", alias=" + alias
                        + "). Iterands must carry a ref with a registered type alias.");
            }
            result.add((Artifact) ctxt.readTreeAsValue(node, concreteClass));
        }
        return result;
    }
}
