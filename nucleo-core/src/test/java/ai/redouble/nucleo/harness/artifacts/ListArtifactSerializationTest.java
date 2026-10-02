/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ListArtifact serialization contract: full form on plain write
 * (persistence/replay), bounded digest in summarized mode, @ref replacement in
 * refs mode, embedded list fields on regular artifacts untouched, and the
 * deserialization round-trip including the fail-not-skip rule for unresolvable
 * member types.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public class ListArtifactSerializationTest {

    @TypeAlias("test:ser-item")
    public static class SerItem extends AbstractArtifact {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @TypeAlias("test:ser-holder")
    public static class HolderArtifact extends AbstractArtifact {
        private List<SerItem> embedded;

        public List<SerItem> getEmbedded() {
            return embedded;
        }

        public void setEmbedded(List<SerItem> embedded) {
            this.embedded = embedded;
        }
    }

    public static class OutputPojo {
        private ListArtifact<SerItem> results;

        public ListArtifact<SerItem> getResults() {
            return results;
        }

        public void setResults(ListArtifact<SerItem> results) {
            this.results = results;
        }
    }

    @BeforeAll
    static void aliases() {
        TypeAliasRegistry.register(ListArtifact.class);
        TypeAliasRegistry.register(SerItem.class);
        TypeAliasRegistry.register(HolderArtifact.class);
    }

    private static ListArtifact<SerItem> listOf(int n) {
        List<SerItem> iterands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SerItem item = new SerItem();
            item.setName("member-" + i);
            iterands.add(item);
        }
        ListArtifact<SerItem> list = new ListArtifact<>();
        list.setIterands(iterands);
        list.setIterandTypeAlias("test:ser-item");
        return list;
    }

    @Test
    void plainWriteKeepsEveryMember() {
        ListArtifact<SerItem> list = listOf(5);
        String json = NucleoJsonSerializer.write(list);
        for (int i = 0; i < 5; i++) {
            assertTrue(json.contains("member-" + i), "full form must keep member " + i);
        }
        assertFalse(json.contains("\"sample\""), "full form must not be a digest");
    }

    @Test
    void summarizedWriteIsBoundedDigest() {
        ListArtifact<SerItem> list = listOf(5);
        new ArtifactRegistry().register(list);
        String json = NucleoJsonSerializer.writeSummarized(list);
        assertTrue(json.contains("\"count\" : 5") || json.contains("\"count\":5"), "digest must carry the count: " + json);
        assertTrue(json.contains("iterand_type"), "digest must carry the iterand type field");
        assertTrue(json.contains("test:ser-item"), "digest must carry the iterand type alias");
        assertTrue(json.contains("\"sample\""), "digest must carry a sample");
        assertTrue(json.contains("member-0"));
        assertTrue(json.contains("member-2"));
        assertFalse(json.contains("member-3"), "digest sample is bounded to the head: " + json);
        assertFalse(json.contains("member-4"));
    }

    @Test
    void refsModeReplacesListWithRef() {
        ListArtifact<SerItem> list = listOf(2);
        OutputPojo output = new OutputPojo();
        output.setResults(list);
        ArtifactRegistry registry = new ArtifactRegistry();
        String json = NucleoJsonSerializer.writeSummarizedWithRefs(output, registry);
        assertTrue(json.contains("@ref"), "list artifact field must serialize as @ref: " + json);
        assertFalse(json.contains("member-0"), "members must not leak into refs-mode output");
        assertNotNull(list.getArtifactRef());
    }

    @Test
    void embeddedListFieldsOnRegularArtifactsKeepFullRendering() {
        HolderArtifact holder = new HolderArtifact();
        List<SerItem> embedded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SerItem item = new SerItem();
            item.setName("embedded-" + i);
            embedded.add(item);
        }
        holder.setEmbedded(embedded);
        String json = NucleoJsonSerializer.writeSummarized(holder);
        for (int i = 0; i < 5; i++) {
            assertTrue(json.contains("embedded-" + i), "embedded list fields keep full rendering");
        }
        assertFalse(json.contains("\"sample\""));
    }

    @Test
    void registeredListRoundTripsWithTypedMembers() throws IOException {
        ListArtifact<SerItem> list = listOf(3);
        new ArtifactRegistry().register(list);
        String json = NucleoJsonSerializer.write(list);
        ListArtifact<?> rehydrated = NucleoJsonSerializer.parse(json, ListArtifact.class);
        assertEquals(3, rehydrated.getIterands().size());
        assertEquals("test:ser-item", rehydrated.getIterandTypeAlias());
        for (int i = 0; i < 3; i++) {
            Artifact iterand = rehydrated.getIterands().get(i);
            assertInstanceOf(SerItem.class, iterand, "iterands must rehydrate to their concrete type");
            assertEquals("member-" + i, ((SerItem) iterand).getName());
            assertEquals(list.getIterands().get(i).getArtifactRef(), iterand.getArtifactRef(), "iterand refs must be stable");
        }
    }

    @Test
    void iterandWithoutResolvableTypeFailsInsteadOfSkipping() {
        // Iterands serialized without refs (never registered) cannot resolve a type.
        ListArtifact<SerItem> list = listOf(2);
        String json = NucleoJsonSerializer.write(list);
        assertThrows(IOException.class, () -> NucleoJsonSerializer.parse(json, ListArtifact.class),
                "an unresolvable iterand type must fail the read, never shorten the list");
    }

    @Test
    void digestSampleOfNestedListsStaysBounded() {
        ListArtifact<ListArtifact<SerItem>> outer = new ListArtifact<>();
        outer.setIterands(List.of(listOf(5), listOf(5)));
        outer.setIterandTypeAlias("list");
        new ArtifactRegistry().register(outer);
        String json = NucleoJsonSerializer.writeSummarized(outer);
        assertTrue(json.contains("\"count\" : 2") || json.contains("\"count\":2"), "outer digest counts inner lists: " + json);
        // Inner lists inside the sample render as digests themselves.
        assertFalse(json.contains("member-4"), "nested digests stay bounded: " + json);
    }

    /** JSON-shape check used by the prompt renderer: digest carries the ref so the LLM can address the list. */
    @Test
    void digestCarriesArtifactRef() throws IOException {
        ListArtifact<SerItem> list = listOf(1);
        new ArtifactRegistry().register(list);
        JsonNode digest = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(list));
        assertEquals(list.getArtifactRef(), digest.get("artifact_ref").asText());
    }
}
