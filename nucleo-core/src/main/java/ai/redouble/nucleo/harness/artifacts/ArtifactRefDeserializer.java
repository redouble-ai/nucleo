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

/**
 * Reads a field whose declared type is the {@link Artifact} interface, by resolving the
 * concrete type from the artifact's own ref.
 *
 * <p>Jackson cannot construct an interface, so such a field previously failed the whole read
 * with "abstract types either need to be mapped to concrete types" - and a field typed as
 * {@code Artifact} is the natural way to declare "some artifact, whatever it turns out to
 * be", which is exactly what an agent handing back a found thing wants to say.
 *
 * <p>The resolution is the one already used for the artifact map of a conversation snapshot
 * and for a list's iterands: {@code «artifact:alias~uuid»} carries the {@code @TypeAlias} of
 * the class that wrote it, and {@link TypeAliasRegistry#resolveAliasWithFallback} walks that
 * alias up its hierarchy, so a reader holding only an ancestor still gets a typed artifact.
 * A ref that resolves at no level fails the read rather than yielding a null field, because
 * an artifact silently missing from a result is worse than a result that says it could not
 * be read.
 *
 * <p>This reader rebuilds the artifact from the payload it is given, which is what a
 * payload written by a tool or by another process calls for. It is no judge of who wrote
 * the payload: in a model's reply the object it builds is replaced by the registry's own
 * before anything reads it ({@link ArtifactRegistry#held}), since a model refers to an
 * artifact and never writes one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class ArtifactRefDeserializer extends StdDeserializer<Artifact> {

    public ArtifactRefDeserializer() {
        super((Class<?>) null);
    }

    @Override
    public Artifact deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new JsonMappingException(p, "Expected an object for an artifact-typed field, got " + node.getNodeType());
        }
        JsonNode ref = node.get(ArtifactRegistry.REF_FIELD);
        String alias = ref != null && ref.isTextual() ? ArtifactRegistry.extractAliasFromRef(ref.asText()) : null;
        Class<?> concrete = alias == null ? null : TypeAliasRegistry.resolveAliasWithFallback(alias);
        if (concrete == null) {
            throw new JsonMappingException(p, "Cannot resolve the type of an artifact-typed field"
                    + " (alias=" + alias + "). An artifact must carry a ref with a registered type alias.");
        }
        return (Artifact)ctxt.readTreeAsValue(node, concrete);
    }
}
