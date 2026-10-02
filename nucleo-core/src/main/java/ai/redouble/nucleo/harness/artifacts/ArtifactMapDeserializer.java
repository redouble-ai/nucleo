/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;

/**
 * Custom deserializer for the artifact map in {@link ConversationPersistenceSnapshot}.
 *
 * <p>Resolves concrete artifact types from the map key (the artifact ref) rather than
 * from an embedded {@code @type} field. The ref format {@code «artifact:alias~uuid»}
 * contains the hierarchical type alias, which is resolved via
 * {@link TypeAliasRegistry#resolveAliasWithFallback(String)}.
 *
 * <p>A key whose alias resolves at no level is SKIPPED with a warning, unlike the list
 * and interface-field readers, which fail loud: this reader restores a persisted
 * conversation snapshot, where an artifact class removed since the snapshot was written
 * must cost one registry entry, never the whole conversation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
public class ArtifactMapDeserializer extends StdDeserializer<Map<String, Artifact>> {
    private static final Logger log = LoggerFactory.getLogger(ArtifactMapDeserializer.class);
    public ArtifactMapDeserializer() {
        super((Class<?>) null);
    }
    @Override
    public Map<String, Artifact> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        Map<String, Artifact> result = new HashMap<>();
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new JsonMappingException(p, "Expected START_OBJECT for artifact map, got " + p.currentToken());
        }
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String key = p.currentName();
            p.nextToken(); // move to value

            // Extract alias from the ref key
            String alias = ArtifactRegistry.extractAliasFromRef(key);
            Class<?> concreteClass = null;
            if (alias != null) {
                concreteClass = TypeAliasRegistry.resolveAliasWithFallback(alias);
            }
            if (concreteClass == null) {
                log.warn("ArtifactMapDeserializer: cannot resolve type for ref \"{}\" (alias={}), falling back to AbstractArtifact subtree skip", key, alias);
                p.skipChildren();
                continue;
            }
            Artifact artifact = (Artifact) ctxt.readValue(p, concreteClass);
            result.put(key, artifact);
        }
        return result;
    }
}
