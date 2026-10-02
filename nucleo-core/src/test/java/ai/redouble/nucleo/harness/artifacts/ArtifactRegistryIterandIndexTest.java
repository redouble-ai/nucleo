/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the registry's iterand index: iterands of registered lists resolve by
 * ref without becoming top-level entries, registration works against the Artifact
 * interface (no AbstractArtifact cast), restore-style re-registration re-indexes,
 * and filter promotion keeps lookups deterministic when an iterand exists at both
 * levels.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public class ArtifactRegistryIterandIndexTest {

    @TypeAlias("test:reg-item")
    public static class Item extends AbstractArtifact {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Interface-only Artifact implementation - must register without a cast failure. */
    @TypeAlias("test:reg-iface")
    public static class InterfaceOnlyArtifact implements Artifact {
        private String artifactRef;
        private final Map<String, SummarizedField> cache = new HashMap<>();

        @Override
        public String getArtifactRef() {
            return artifactRef;
        }

        @Override
        public void setArtifactRef(String ref) {
            this.artifactRef = ref;
        }

        @Override
        public SummarizedField getCachedSummary(String fieldName) {
            return cache.get(fieldName);
        }

        @Override
        public void cacheSummary(String fieldName, SummarizedField value) {
            cache.put(fieldName, value);
        }
    }

    @BeforeAll
    static void aliases() {
        TypeAliasRegistry.register(ListArtifact.class);
        TypeAliasRegistry.register(Item.class);
        TypeAliasRegistry.register(InterfaceOnlyArtifact.class);
    }

    private static ListArtifact<Item> listOf(String... names) {
        List<Item> iterands = new ArrayList<>();
        for (String name : names) {
            Item item = new Item();
            item.setName(name);
            iterands.add(item);
        }
        ListArtifact<Item> list = new ListArtifact<>();
        list.setIterands(iterands);
        list.setIterandTypeAlias("test:reg-item");
        return list;
    }

    @Test
    void iterandsResolveButStayOutOfTopLevel() {
        ArtifactRegistry registry = new ArtifactRegistry();
        ListArtifact<Item> list = listOf("a", "b", "c");
        registry.register(list);
        assertEquals(1, registry.getAllArtifacts().size());
        for (Item iterand : list.getIterands()) {
            assertNotNull(iterand.getArtifactRef(), "registration must mint iterand refs");
            assertSame(iterand, registry.get(iterand.getArtifactRef()));
            assertTrue(registry.contains(iterand.getArtifactRef()));
        }
        assertEquals(4, registry.getAllArtifactsIncludingReachable().size());
    }

    @Test
    void nestedListsIndexRecursively() {
        ArtifactRegistry registry = new ArtifactRegistry();
        ListArtifact<Item> inner = listOf("x", "y");
        ListArtifact<ListArtifact<Item>> outer = new ListArtifact<>();
        outer.setIterands(List.of(inner));
        outer.setIterandTypeAlias("list");
        registry.register(outer);
        assertEquals(1, registry.getAllArtifacts().size());
        assertSame(inner, registry.get(inner.getArtifactRef()));
        assertSame(inner.getIterands().get(1), registry.get(inner.getIterands().get(1).getArtifactRef()));
    }

    /** Artifact holding another artifact in a plain field, for reachable-walk tests. */
    @TypeAlias("test:reg-holder")
    public static class Holder extends AbstractArtifact {
        private Item child;

        public Item getChild() {
            return child;
        }

        public void setChild(Item child) {
            this.child = child;
        }
    }

    @Test
    void nestedFieldArtifactsAreReachable() {
        TypeAliasRegistry.register(Holder.class);
        ArtifactRegistry registry = new ArtifactRegistry();
        Item child = new Item();
        child.setName("nested");
        Holder holder = new Holder();
        holder.setChild(child);
        registry.register(holder);
        assertNotNull(child.getArtifactRef(), "registration must mint refs for nested artifacts");
        assertSame(child, registry.get(child.getArtifactRef()));
        assertEquals(1, registry.getAllArtifacts().size(), "nested artifacts never become top-level");
    }

    @Test
    void interfaceOnlyArtifactRegisters() {
        ArtifactRegistry registry = new ArtifactRegistry();
        InterfaceOnlyArtifact artifact = new InterfaceOnlyArtifact();
        String ref = registry.register(artifact);
        assertNotNull(ref);
        assertSame(artifact, registry.get(ref));
    }

    @Test
    void clearEmptiesIterandIndexToo() {
        ArtifactRegistry registry = new ArtifactRegistry();
        ListArtifact<Item> list = listOf("a");
        registry.register(list);
        String iterandRef = list.getIterands().get(0).getArtifactRef();
        registry.clear();
        assertNull(registry.get(iterandRef));
        assertEquals(0, registry.getAllArtifactsIncludingReachable().size());
    }

    @Test
    void restoreStyleReRegistrationReindexesIterands() {
        ArtifactRegistry original = new ArtifactRegistry();
        ListArtifact<Item> list = listOf("a", "b");
        original.register(list);
        String iterandRef = list.getIterands().get(0).getArtifactRef();
        // Mirrors ConversationPersistenceSnapshot.toConversation: register each top-level artifact.
        ArtifactRegistry restored = new ArtifactRegistry();
        for (Artifact artifact : original.getAllArtifacts().values()) {
            restored.register(artifact);
        }
        assertSame(list.getIterands().get(0), restored.get(iterandRef));
        assertEquals(1, restored.getAllArtifacts().size());
    }

    @Test
    void filterPromotesIterandsAndStaysDeterministicOnDualPresence() {
        ArtifactRegistry registry = new ArtifactRegistry();
        ListArtifact<Item> list = listOf("a", "b");
        registry.register(list);
        Item iterand = list.getIterands().get(0);
        // Filtering an iterand ref promotes the iterand to top-level in the destination.
        ArtifactRegistry iterandOnly = registry.filter(List.of(iterand.getArtifactRef()));
        assertSame(iterand, iterandOnly.get(iterand.getArtifactRef()));
        assertEquals(1, iterandOnly.getAllArtifacts().size());
        // Dual presence: iterand promoted AND owning list filtered - lookup resolves
        // top-level first, returning the same instance deterministically.
        ArtifactRegistry both = registry.filter(List.of(iterand.getArtifactRef(), list.getArtifactRef()));
        assertSame(iterand, both.get(iterand.getArtifactRef()));
        assertEquals(2, both.getAllArtifacts().size());
    }
}
