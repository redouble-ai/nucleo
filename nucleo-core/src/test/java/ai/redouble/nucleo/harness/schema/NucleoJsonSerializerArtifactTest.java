/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link NucleoJsonSerializer#writeSummarizedWithRefs} correctly
 * discovers, registers, and replaces {@link Artifact} instances at any nesting
 * depth: plain POJO fields, deeply nested compositions, list/set/array/map
 * members, heterogeneous {@code Map<String, Object>} values, and artifacts
 * nested inside other artifacts.
 *
 * <p>Each test uses a fresh {@link ArtifactRegistry} and inspects both the
 * produced JSON (for {@code @ref} replacements) and the registry (for
 * registered instances).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class NucleoJsonSerializerArtifactTest {

    @TypeAlias("test:simple")
    public static class SimpleArtifact extends AbstractArtifact {
        private String value;
        public SimpleArtifact() {}
        public SimpleArtifact(String value) { this.value = value; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @TypeAlias("test:other")
    public static class OtherArtifact extends AbstractArtifact {
        private int number;
        public OtherArtifact() {}
        public OtherArtifact(int number) { this.number = number; }
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
    }

    @TypeAlias("test:parent")
    public static class ParentArtifact extends AbstractArtifact {
        private String title;
        private List<SimpleArtifact> children;
        public ParentArtifact() {}
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<SimpleArtifact> getChildren() { return children; }
        public void setChildren(List<SimpleArtifact> children) { this.children = children; }
    }

    public static class Container {
        private String label;
        private SimpleArtifact artifact;
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public SimpleArtifact getArtifact() { return artifact; }
        public void setArtifact(SimpleArtifact artifact) { this.artifact = artifact; }
    }

    public static class Level1 { public Level2 next; }
    public static class Level2 { public Level3 next; }
    public static class Level3 { public SimpleArtifact payload; }

    public static class WithCollections {
        public List<SimpleArtifact> list;
        public Set<SimpleArtifact> set;
        public SimpleArtifact[] array;
    }

    public static class WithMap {
        public Map<String, SimpleArtifact> byKey;
    }

    public static class WithHeterogeneousMap {
        public Map<String, Object> mixed;
    }

    public static class WithNullableArtifact {
        public SimpleArtifact maybe;
        public String name;
    }

    // ========================= Tests =========================

    @Test
    public void topLevelArtifactGetsRegisteredAndReplacedWithRef() {
        ArtifactRegistry registry = new ArtifactRegistry();
        SimpleArtifact a = new SimpleArtifact("hello");

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(a, registry);

        assertTrue(json.contains("@ref"), "top-level artifact should serialize as @ref: " + json);
        assertFalse(json.contains("\"value\" : \"hello\""), "artifact body should be replaced: " + json);
        assertNotNull(a.getArtifactRef(), "artifact should have a ref assigned");
        assertEquals(1, registry.size());
        assertSame(a, registry.get(a.getArtifactRef()));
    }

    @Test
    public void artifactInPojoFieldIsReplaced() {
        ArtifactRegistry registry = new ArtifactRegistry();
        Container c = new Container();
        c.setLabel("wrapper");
        c.setArtifact(new SimpleArtifact("inside"));

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(c, registry);

        assertTrue(json.contains("\"label\" : \"wrapper\""), "plain field preserved: " + json);
        assertTrue(json.contains("@ref"), "nested artifact replaced: " + json);
        assertFalse(json.contains("\"value\" : \"inside\""), "artifact body should not leak: " + json);
        assertEquals(1, registry.size());
    }

    @Test
    public void deeplyNestedArtifactIsDiscovered() {
        ArtifactRegistry registry = new ArtifactRegistry();
        Level1 l1 = new Level1();
        l1.next = new Level2();
        l1.next.next = new Level3();
        l1.next.next.payload = new SimpleArtifact("deep");

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(l1, registry);

        assertTrue(json.contains("@ref"), "deeply nested artifact should be replaced: " + json);
        assertFalse(json.contains("\"value\" : \"deep\""));
        assertEquals(1, registry.size());
    }

    @Test
    public void artifactsInListAreAllReplacedAndRegistered() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithCollections w = new WithCollections();
        w.list = List.of(new SimpleArtifact("one"), new SimpleArtifact("two"), new SimpleArtifact("three"));
        w.set = new HashSet<>();
        w.array = new SimpleArtifact[]{};

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        int refCount = countOccurrences(json, "@ref");
        assertEquals(3, refCount, "three list entries should each become a @ref: " + json);
        assertEquals(3, registry.size());
        assertFalse(json.contains("\"value\" : \"one\""));
        assertFalse(json.contains("\"value\" : \"two\""));
        assertFalse(json.contains("\"value\" : \"three\""));
    }

    @Test
    public void artifactsInSetAreAllReplacedAndRegistered() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithCollections w = new WithCollections();
        w.list = new ArrayList<>();
        w.set = new LinkedHashSet<>(List.of(new SimpleArtifact("a"), new SimpleArtifact("b")));
        w.array = new SimpleArtifact[]{};

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        assertEquals(2, countOccurrences(json, "@ref"));
        assertEquals(2, registry.size());
    }

    @Test
    public void artifactsInArrayAreAllReplacedAndRegistered() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithCollections w = new WithCollections();
        w.list = new ArrayList<>();
        w.set = new HashSet<>();
        w.array = new SimpleArtifact[]{new SimpleArtifact("x"), new SimpleArtifact("y")};

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        assertEquals(2, countOccurrences(json, "@ref"));
        assertEquals(2, registry.size());
    }

    @Test
    public void artifactsInMapValuesAreAllReplacedAndRegistered() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithMap w = new WithMap();
        w.byKey = new LinkedHashMap<>();
        w.byKey.put("first", new SimpleArtifact("1"));
        w.byKey.put("second", new SimpleArtifact("2"));

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        assertEquals(2, countOccurrences(json, "@ref"));
        assertEquals(2, registry.size());
        assertTrue(json.contains("\"first\""), "map keys preserved");
        assertTrue(json.contains("\"second\""));
    }

    @Test
    public void heterogeneousMapReplacesOnlyArtifacts() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithHeterogeneousMap w = new WithHeterogeneousMap();
        w.mixed = new LinkedHashMap<>();
        w.mixed.put("scalar", 42);
        w.mixed.put("text", "plain");
        w.mixed.put("artifact", new SimpleArtifact("only-this"));
        w.mixed.put("pojo", makeContainer("inner", new SimpleArtifact("deep-only-this")));

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        // Scalars survive untouched
        assertTrue(json.contains("42"));
        assertTrue(json.contains("\"plain\""));
        // Both artifacts replaced
        assertEquals(2, countOccurrences(json, "@ref"), "two artifacts (one direct, one nested): " + json);
        assertEquals(2, registry.size());
        assertFalse(json.contains("\"value\" : \"only-this\""));
        assertFalse(json.contains("\"value\" : \"deep-only-this\""));
    }

    @Test
    public void aTopLevelArtifactIsItsRefAndItsOwnArtifactsAreReachableNotTopLevel() {
        ArtifactRegistry registry = new ArtifactRegistry();
        ParentArtifact parent = new ParentArtifact();
        parent.setTitle("parent-title");
        SimpleArtifact child = new SimpleArtifact("child-1");
        parent.setChildren(List.of(child, new SimpleArtifact("child-2")));

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(parent, registry);

        assertEquals(1, countOccurrences(json, "@ref"), "the whole output is the parent's ref: " + json);
        assertSame(parent, registry.get(parent.getArtifactRef()));
        assertEquals(1, registry.size(), "the parent is the one top-level entry");
        assertNotNull(child.getArtifactRef(), "registering the parent minted a ref for each artifact reachable inside it");
        assertSame(child, registry.get(child.getArtifactRef()), "resolvable through the parent's custody without being top-level");
    }

    @Test
    public void anArtifactInsideAMapIsItsRefAndItsOwnArtifactsAreReachableNotTopLevel() {
        ArtifactRegistry registry = new ArtifactRegistry();
        ParentArtifact parent = new ParentArtifact();
        parent.setTitle("parent-title");
        SimpleArtifact child = new SimpleArtifact("child-1");
        parent.setChildren(List.of(child));
        WithHeterogeneousMap het = new WithHeterogeneousMap();
        het.mixed = new LinkedHashMap<>();
        het.mixed.put("parent", parent);

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(het, registry);

        assertEquals(1, countOccurrences(json, "@ref"), "the map value is the parent's ref: " + json);
        assertSame(parent, registry.get(parent.getArtifactRef()));
        assertEquals(1, registry.size(), "the parent is the one top-level entry");
        assertSame(child, registry.get(child.getArtifactRef()), "its child is reachable through it");
    }

    @Test
    public void nullArtifactFieldSerializesCleanly() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithNullableArtifact w = new WithNullableArtifact();
        w.name = "only-name";
        w.maybe = null;

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        assertTrue(json.contains("\"name\" : \"only-name\""));
        assertFalse(json.contains("@ref"));
        assertEquals(0, registry.size());
    }

    @Test
    public void emptyCollectionOfArtifactsProducesNoRegistrations() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithCollections w = new WithCollections();
        w.list = new ArrayList<>();
        w.set = new HashSet<>();
        w.array = new SimpleArtifact[]{};

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        assertFalse(json.contains("@ref"));
        assertEquals(0, registry.size());
    }

    @Test
    public void multipleDistinctArtifactTypesMixedTogether() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WithHeterogeneousMap w = new WithHeterogeneousMap();
        w.mixed = new LinkedHashMap<>();
        w.mixed.put("simple", new SimpleArtifact("s"));
        w.mixed.put("other", new OtherArtifact(99));

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(w, registry);

        assertEquals(2, countOccurrences(json, "@ref"));
        assertEquals(2, registry.size());
        // Verify both type aliases appear in the assigned refs
        boolean sawSimple = false, sawOther = false;
        for (String key : registry.getAllArtifacts().keySet()) {
            if (key.contains("test:simple")) sawSimple = true;
            if (key.contains("test:other")) sawOther = true;
        }
        assertTrue(sawSimple, "simple artifact alias should appear in registry keys");
        assertTrue(sawOther, "other artifact alias should appear in registry keys");
    }

    @Test
    public void repeatedSerializationOfSameArtifactReusesRef() {
        ArtifactRegistry registry = new ArtifactRegistry();
        SimpleArtifact a = new SimpleArtifact("stable");

        String firstJson = NucleoJsonSerializer.writeSummarizedWithRefs(a, registry);
        String firstRef = a.getArtifactRef();
        assertNotNull(firstRef);

        String secondJson = NucleoJsonSerializer.writeSummarizedWithRefs(a, registry);
        String secondRef = a.getArtifactRef();

        assertEquals(firstRef, secondRef, "ref should be stable across serializations");
        assertEquals(firstJson, secondJson, "output should be identical for the same artifact");
        assertEquals(1, registry.size(), "re-registering the same artifact should not duplicate");
    }

    // ========================= Helpers =========================

    private static Container makeContainer(String label, SimpleArtifact artifact) {
        Container c = new Container();
        c.setLabel(label);
        c.setArtifact(artifact);
        return c;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
