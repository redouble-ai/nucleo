/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.conversation.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model role over the pinned test catalog ({@code /models-pins.json}): a seat carrying a
 * role is served by the role's entry from whichever job class does the work, whatever grade
 * the entry carries; an entry that cannot serve - absent from this catalog, closed, without
 * a credential, refused by the envelope, turned off by the probe, not accepting a declared
 * input - falls back to the role's grade, which the shipped picker walks as for any seat; a
 * {@code CEILING} role falls back to the strongest entry; the gate hands the role through;
 * and a conversation declares a role as it declares a grade.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-11-09)
 */
class ModelRoleTest {

    private static final class IngestJob {}

    private static final class RepairJob {}

    private Class<? extends ModelsBackend> configured;

    @BeforeEach
    void usePinnedCatalog() {
        configured = Settings.get(ModelSettings.class).backendClass;
        Settings.get(ModelSettings.class).backendClass = DefaultModelPickerTest.PinnedCatalog.class;
        Models.resetAll();
    }

    @AfterEach
    void restoreCatalog() {
        Settings.get(ModelSettings.class).backendClass = configured;
        Models.resetAll();
        ModelPickers.resetForTests();
    }

    private static DefaultModelPicker picker(Predicate<ModelSpec> callable) {
        return new DefaultModelPicker(callable) {
            @Override
            protected double routeSlowdown(ModelSpec spec) {
                return 1.0;
            }
        };
    }

    private static Seat seat(Class<?> jobClass, ModelRole role) {
        return new Seat(jobClass, role.grade(), ModelKind.LLM, role);
    }

    private static Situation situation() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    @Test
    void aRoleIsServedByItsEntryFromAnyJobClassWhateverTheEntrysGrade() {
        ModelRole tableRow = new ModelRole("table-row", "live-large", Grade.SMALL);
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("live-large", picker.provide(seat(IngestJob.class, tableRow), situation()).getId(), "the work's model, during ingestion");
        assertEquals("live-large", picker.provide(seat(RepairJob.class, tableRow), situation()).getId(), "the same work's model, during a repair");
        ModelRole cheap = new ModelRole("cheap-check", "pinned-small", Grade.XL);
        assertEquals("pinned-small", picker.provide(seat(IngestJob.class, cheap), situation()).getId(),
                "an entry below the role's grade serves: the role chose it by measurement");
    }

    @Test
    void anEntryThatCannotServeFallsBackToTheRolesGrade() {
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("pinned-small", picker.provide(seat(IngestJob.class, new ModelRole("absent", "no-such-entry", Grade.SMALL)), situation()).getId(),
                "an entry this deployment's catalog lacks: the grade's default serves");
        assertEquals("pinned-small", picker.provide(seat(IngestJob.class, new ModelRole("retired", "retired-large", Grade.SMALL)), situation()).getId(),
                "a closed entry");
        assertEquals("pinned-small", picker(spec -> !spec.getId().equals("live-large"))
                .provide(seat(IngestJob.class, new ModelRole("uncallable", "live-large", Grade.SMALL)), situation()).getId(), "an entry with no credential");
        Situation refusing = new Situation();
        refusing.setEnvelope(spec -> !spec.getId().equals("live-large"));
        assertEquals("pinned-small", picker.provide(seat(IngestJob.class, new ModelRole("refused", "live-large", Grade.SMALL)), refusing).getId(),
                "an entry the envelope refuses");
        ProbeOutcome down = new ProbeOutcome();
        down.setSpecId("live-large");
        down.setStatus(ProbeOutcome.Status.FAILED);
        down.setClassification(ProbeOutcome.Classification.AVAILABILITY);
        down.setProbedAt(Instant.now().minus(Duration.ofHours(1)));
        down.setErrorClass("ExternalServiceException");
        down.setErrorMessage("connection refused");
        picker.recordProbe(down);
        assertEquals("pinned-small", picker.provide(seat(IngestJob.class, new ModelRole("dead", "live-large", Grade.SMALL)), situation()).getId(),
                "an entry the probe reports down");
    }

    @Test
    void anEntryNotAcceptingADeclaredInputFallsBackToTheRolesGrade() {
        DefaultModelPickerTest.useEdited(root -> ((ArrayNode) root.get("models"))
                .add(DefaultModelPickerTest.accepting(DefaultModelPickerTest.entry("seeing-small", "SMALL"), true, false)));
        Situation sending = situation();
        sending.setSends(Set.of(Input.IMAGES));
        assertEquals("seeing-small", picker(spec -> true).provide(seat(IngestJob.class, new ModelRole("blind", "live-large", Grade.SMALL)), sending).getId(),
                "live-large does not accept images: the grade's entry that does serves");
    }

    @Test
    void aCeilingRoleFallsBackToTheStrongestEntry() {
        assertEquals("only-xl", picker(spec -> true).provide(seat(IngestJob.class, new ModelRole("frontier", "no-such-entry", Grade.CEILING)), situation()).getId());
    }

    @Test
    void theGateHandsTheRoleToThePicker() {
        ModelRole tableRow = new ModelRole("table-row", "live-large", Grade.SMALL);
        assertEquals("live-large", ModelPickers.resolveWith(picker(spec -> true), seat(IngestJob.class, tableRow), situation()).getId());
    }

    @Test
    void aRoleSeatsGradeIsTheRoles() {
        ModelRole tableRow = new ModelRole("table-row", "live-large", Grade.SMALL);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Seat(IngestJob.class, Grade.LARGE, ModelKind.LLM, tableRow));
        assertTrue(ex.getMessage().contains("a role seat's grade is the role's"), ex.getMessage());
        assertNull(new Seat(IngestJob.class, Grade.SMALL, ModelKind.LLM).role(), "a grade seat declares no role");
    }

    @Test
    void aConversationDeclaresARoleAsItDeclaresAGrade() {
        ModelRole tableRow = new ModelRole("table-row", "live-large", Grade.MEDIUM);
        ConversationContext conversation = new ConversationContext();
        conversation.setRole(tableRow);
        assertSame(tableRow, conversation.getRole());
        assertEquals(Grade.MEDIUM, conversation.getGrade(), "the grade becomes the role's");
        conversation.setGrade(Grade.XL);
        assertNull(conversation.getRole(), "a caller naming a rung drops the role");
        assertEquals(Grade.XL, conversation.getGrade());
    }

    @Test
    void aPersistedConversationKeepsItsRole() {
        ModelRole tableRow = new ModelRole("table-row", "live-large", Grade.MEDIUM);
        ConversationContext conversation = new ConversationContext();
        conversation.setRole(tableRow);
        ConversationContext restored = ConversationPersistenceSnapshot.fromConversation(conversation).toConversation(StringResponseHandler.instance);
        assertEquals(tableRow, restored.getRole(), "the role is stored by its parts and restored whole");
        assertEquals(Grade.MEDIUM, restored.getGrade());
        ConversationContext graded = new ConversationContext();
        graded.setGrade(Grade.SMALL);
        assertNull(ConversationPersistenceSnapshot.fromConversation(graded).toConversation(StringResponseHandler.instance).getRole());
    }

    @Test
    void aRoleNamesItsWorkItsEntryAndItsGrade() {
        assertThrows(IllegalArgumentException.class, () -> new ModelRole(" ", "live-large", Grade.SMALL));
        assertThrows(IllegalArgumentException.class, () -> new ModelRole("table-row", null, Grade.SMALL));
        assertThrows(IllegalArgumentException.class, () -> new ModelRole("table-row", "live-large", null));
    }
}
