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
 * Covers the deterministic text formatting contract: same artifacts always
 * produce the same text, resolution walks the class hierarchy, unregistered
 * types fall back to canonical JSON, and list formatting recurses through
 * iterand formatters.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-12)
 */
public class TextFormatterRegistryTest {

    @TypeAlias("test:fmt-note")
    public static class Note extends AbstractArtifact {
        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    @TypeAlias("test:fmt-special-note")
    public static class SpecialNote extends Note {
    }

    @BeforeAll
    static void aliases() {
        TypeAliasRegistry.register(ListArtifact.class);
        TypeAliasRegistry.register(Note.class);
        TypeAliasRegistry.register(SpecialNote.class);
    }

    @AfterEach
    void cleanUp() {
        TextFormatterRegistry.unregister(Note.class);
    }

    private static Note note(String text) {
        Note note = new Note();
        note.setText(text);
        return note;
    }

    @Test
    void findAnswersTheNearestRegisteredFormatterOrNull() {
        assertNull(TextFormatterRegistry.find(Note.class), "nothing registered for the type or an ancestor");
        ArtifactTextFormatter<Note> formatter = n -> "note: " + n.getText();
        TextFormatterRegistry.register(Note.class, formatter);
        assertSame(formatter, TextFormatterRegistry.find(Note.class));
        assertSame(formatter, TextFormatterRegistry.find(SpecialNote.class), "resolved up the class hierarchy");
        assertNull(TextFormatterRegistry.find(String.class), "not an artifact type");
    }

    @Test
    void unregisteredTypeFallsBackToCanonicalJsonDeterministically() {
        Note note = note("hello");
        String first = TextFormatterRegistry.format(note);
        String second = TextFormatterRegistry.format(note);
        assertEquals(first, second, "same artifact must always format identically");
        assertTrue(first.contains("hello"));
    }

    @Test
    void registeredFormatterResolvesUpTheClassHierarchy() {
        TextFormatterRegistry.register(Note.class, n -> "NOTE: " + n.getText());
        assertEquals("NOTE: a", TextFormatterRegistry.format(note("a")));
        SpecialNote special = new SpecialNote();
        special.setText("b");
        assertEquals("NOTE: b", TextFormatterRegistry.format(special),
                "subclasses resolve to the nearest registered ancestor formatter");
    }

    @Test
    void listFormatterRecursesThroughIterandFormattersInOrder() {
        TextFormatterRegistry.register(Note.class, n -> "NOTE: " + n.getText());
        ListArtifact<Note> list = new ListArtifact<>();
        list.setIterands(List.of(note("x"), note("y")));
        list.setIterandTypeAlias("test:fmt-note");
        String text = TextFormatterRegistry.format(list);
        assertEquals(text, TextFormatterRegistry.format(list), "list formatting is deterministic");
        assertTrue(text.startsWith("2 test:fmt-note results"), text);
        assertTrue(text.contains("1. NOTE: x"), text);
        assertTrue(text.contains("2. NOTE: y"), text);
        assertTrue(text.indexOf("NOTE: x") < text.indexOf("NOTE: y"), "iterand order is list order");
    }

    @Test
    void nestedListsRecurse() {
        TextFormatterRegistry.register(Note.class, n -> "NOTE: " + n.getText());
        ListArtifact<Note> inner = new ListArtifact<>();
        inner.setIterands(List.of(note("deep")));
        inner.setIterandTypeAlias("test:fmt-note");
        ListArtifact<ListArtifact<Note>> outer = new ListArtifact<>();
        outer.setIterands(List.of(inner));
        outer.setIterandTypeAlias("list");
        String text = TextFormatterRegistry.format(outer);
        assertTrue(text.contains("1 list result"), text);
        assertTrue(text.contains("NOTE: deep"), text);
    }
}
