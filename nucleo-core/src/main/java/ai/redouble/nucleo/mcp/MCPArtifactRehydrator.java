/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.io.*;

/**
 * Rebuilds a typed {@link Artifact} from an MCP result served by another redouble process.
 *
 * <p>Nothing is negotiated and no capability is advertised. An artifact serializes with its
 * {@code artifact_ref}, and the ref carries the {@code @TypeAlias} of the class that wrote
 * it ({@code «artifact:link:cite:pubmed~a7f3b2»}), so the wire form already says what it is.
 * A generic MCP client sees one extra string field and ignores it; this reads it. The same
 * round trip already runs on every conversation restore, in {@code ArtifactMapDeserializer}.
 *
 * <p>The consumer does not need the exact class.
 * {@link TypeAliasRegistry#resolveAliasWithFallback} walks the alias up its colon hierarchy,
 * so a process holding {@code LinkArtifact} but not the provider's {@code PubMedArticle}
 * rebuilds the ancestor with the title, url and ref intact. When no level resolves, this
 * returns null and the caller keeps the generic {@link MCPArtifact} path - a degradation,
 * never a failure.
 *
 * <p>Scope is the ROOT of the result: a served tool whose output is an artifact (a single
 * one, or a {@link ai.redouble.nucleo.harness.artifacts.ListArtifact} of them) arrives as that artifact.
 * A result that is a plain POJO with artifacts in its fields takes the generic path whole,
 * because the containing type is exactly what a foreign consumer is least likely to hold.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public final class MCPArtifactRehydrator {
    private static final Logger log = LoggerFactory.getLogger(MCPArtifactRehydrator.class);
    /**
     * The serialized name of {@code Artifact.getArtifactRef()} under the framework mapper's
     * SNAKE_CASE naming strategy. Pinned by test against a real serialization.
     */
    static final String ARTIFACT_REF = "artifact_ref";

    private MCPArtifactRehydrator() {
    }

    /**
     * The typed artifact this result carries, or null when the payload is not one or its
     * type cannot be resolved at any level of the alias hierarchy.
     *
     * @param json the result's text content, as the serving process wrote it
     */
    public static Artifact rehydrate(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = NucleoJsonSerializer.readTree(json);
        }
        catch (IOException e) {
            return null;
        }
        JsonNode ref = root.isObject() ? root.get(ARTIFACT_REF) : null;
        if (ref == null || !ref.isTextual()) {
            return null;
        }
        String alias = ArtifactRegistry.extractAliasFromRef(ref.asText());
        if (alias == null) {
            return null;
        }
        Class<?> concrete = TypeAliasRegistry.resolveAliasWithFallback(alias);
        if (concrete == null || !Artifact.class.isAssignableFrom(concrete)) {
            return null;
        }
        try {
            return (Artifact) NucleoJsonSerializer.parse(json, concrete);
        }
        catch (IOException e) {
            // The ref named a type we hold and the payload would not fit it: the two sides
            // disagree about that type. Say so and fall back rather than fail the call.
            log.warn("MCP result claims artifact type \"{}\" ({}) but does not deserialize into it, falling back to the generic form: {}",
                    alias,
                    concrete.getSimpleName(),
                    e.getMessage());
            return null;
        }
    }
}
