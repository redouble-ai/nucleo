/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.annotation.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the snapshot-map reader's restore semantics: the concrete artifact type is recovered
 * from the map KEY's ref alias (no {@code @type} field in the payload), and a key whose alias
 * resolves at no level is skipped with a warning instead of failing the read - restoring a
 * persisted conversation must survive an artifact class removed since the snapshot was
 * written, at the cost of that one registry entry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class ArtifactMapDeserializerTest {

    /** The shape of the snapshot's artifact map field, with the same deserializer binding. */
    public static class Holder {
        @JsonDeserialize(using = ArtifactMapDeserializer.class)
        private Map<String, Artifact> artifacts;

        public Map<String, Artifact> getArtifacts() {
            return artifacts;
        }

        public void setArtifacts(Map<String, Artifact> artifacts) {
            this.artifacts = artifacts;
        }
    }

    @BeforeAll
    static void aliases() {
        TypeAliasRegistry.register(WebPageArtifact.class);
    }

    @Test
    void concreteTypeIsRecoveredFromTheMapKeysAlias() throws IOException {
        String json = """
                {"artifacts": {"«artifact:link:page~abc123»": {
                    "artifact_ref": "«artifact:link:page~abc123»", "title": "T", "url": "https://e"}}}""";

        Holder holder = NucleoJsonSerializer.parse(json, Holder.class);

        Artifact restored = holder.getArtifacts().get("«artifact:link:page~abc123»");
        WebPageArtifact page = assertInstanceOf(WebPageArtifact.class, restored,
                "the key's alias names the concrete class - the payload carries no @type field");
        assertEquals("T", page.getTitle());
    }

    @Test
    void aKeyWhoseAliasResolvesNowhere_isSkippedAndTheRestOfTheMapSurvives() throws IOException {
        String json = """
                {"artifacts": {
                    "«artifact:vanished:kind~zzz111»": {"anything": 1},
                    "«artifact:link:page~abc123»": {"artifact_ref": "«artifact:link:page~abc123»", "title": "T", "url": "https://e"}}}""";

        Holder holder = NucleoJsonSerializer.parse(json, Holder.class);

        assertEquals(1, holder.getArtifacts().size(),
                "the unresolvable entry is dropped, never the whole restore");
        assertInstanceOf(WebPageArtifact.class, holder.getArtifacts().get("«artifact:link:page~abc123»"));
    }
}
