/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped picker's rules over a catalog with grade orders ({@code /models-pins.json}): a
 * request is served by the first entry of its grade's order that can be called, is permitted by
 * the envelope, is not turned off by the probe and accepts every input the request declares; an
 * entry of the order that cannot serve is skipped for the next; with nothing of the order
 * qualifying, the grade's other qualifying entries fill in cheapest first by list price (an
 * unpriced one after every priced one); a grade with nothing qualifying is served by the nearest
 * grade above, else the nearest below, each fallback switched off by its {@link ModelSettings}
 * flag; a {@code CEILING} request is served from MEGA downward whatever the flags say; and
 * nothing the walk reached raises {@link ModelResolutionError} naming what to provide - a
 * deployment that cannot serve a seat at all is broken, not correctable. The embeddings and
 * decision declarations are single pins. The ceiling is never declared: it is the highest rung
 * with a callable entry. "Can call" is injected, so no credential store is consulted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
class DefaultModelPickerTest {

    /** The catalog under test, swapped in for the shipped one for the duration. */
    public static class PinnedCatalog extends JsonModelsBackend {
        public PinnedCatalog() {
            super("/models-pins.json");
        }
    }

    /** {@code /models-pins.json} as a test edited it, through {@link #useEdited}. */
    public static class EditedCatalog extends JsonModelsBackend {
        static volatile String json;

        public EditedCatalog() {
            super(List.of(new Layer("edited", json, false)));
        }
    }

    private Class<? extends ModelsBackend> configured;

    @BeforeEach
    void usePinnedCatalog() {
        configured = Settings.get(ModelSettings.class).backendClass;
        Settings.get(ModelSettings.class).backendClass = PinnedCatalog.class;
        Models.resetAll();
    }

    @AfterEach
    void restoreCatalog() {
        Settings.get(ModelSettings.class).backendClass = configured;
        Models.resetAll();
    }

    /** Serves {@code /models-pins.json} with the edit applied to its root. */
    private static void useEdited(Consumer<ObjectNode> edit) {
        try (InputStream is = DefaultModelPickerTest.class.getResourceAsStream("/models-pins.json")) {
            ObjectNode root = (ObjectNode) NucleoJsonSerializer.readTree(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            edit.accept(root);
            EditedCatalog.json = root.toString();
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Settings.get(ModelSettings.class).backendClass = EditedCatalog.class;
        Models.resetAll();
    }

    private static DefaultModelPicker picker(Predicate<ModelSpec> callable) {
        return new DefaultModelPicker(callable) {
            @Override
            protected double routeSlowdown(ModelSpec spec) {
                return 1.0;
            }
        };
    }

    private static Seat seat(Grade grade) {
        return new Seat(DefaultModelPickerTest.class, grade, ModelKind.LLM);
    }

    private static Situation situation() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    private static Situation sending(Input... inputs) {
        Situation situation = situation();
        situation.setSends(Set.of(inputs));
        return situation;
    }

    /** A catalog whose pins name entries of providers this classpath does not carry. */
    public static class AbsentProviderCatalog extends JsonModelsBackend {
        public AbsentProviderCatalog() {
            super("/models-pins-absent-provider.json");
        }
    }

    @Test
    void anEntryNoProviderOnThisClasspathServesIsSkippedForTheGradesCallableEntry() {
        Settings.get(ModelSettings.class).backendClass = AbsentProviderCatalog.class;
        Models.resetAll();
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("live-medium", picker.provide(seat(Grade.MEDIUM), situation()).getId(),
                "nothing here can call the ordered entry, so the grade's callable entry serves");
        assertEquals("live-embed", picker.embeddingsSpec().getId(), "the same for the embeddings pin");
        assertEquals("live-decide", picker.decisionSpec().getId(), "and for the decision pin");
    }

    @Test
    void theFirstCallableEntryOfTheOrderServesAndAnUncallableOneIsSkipped() {
        useEdited(root -> ((ObjectNode) root.get("pins")).putArray("SMALL").add("pinned-small").add("other-small"));
        assertEquals("pinned-small", picker(spec -> true).provide(seat(Grade.SMALL), situation()).getId(), "the first of the order is the grade's default");
        assertEquals("other-small", picker(spec -> !spec.getId().equals("pinned-small")).provide(seat(Grade.SMALL), situation()).getId(),
                "the first cannot be called: the order names the next as the fallback");
    }

    @Test
    void anEntryTheEnvelopeRefusesIsSkippedLikeOneThatCannotBeCalled() {
        useEdited(root -> ((ObjectNode) root.get("pins")).putArray("SMALL").add("pinned-small").add("other-small"));
        Situation refusing = new Situation();
        refusing.setEnvelope(spec -> !spec.getId().equals("pinned-small"));
        assertEquals("other-small", picker(spec -> true).provide(seat(Grade.SMALL), refusing).getId(),
                "the grade is served by the order's next entry, never refused for its first");
    }

    @Test
    void aGradeWithNoOrderTakesItsCheapestCallableEntryAndReportsIt() {
        // LARGE has no order: retired-large is skipped for being deprecated, live-large is served
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("live-large", picker.provide(seat(Grade.LARGE), situation()).getId());
        assertEquals("live-large", picker.describePins().get(Grade.LARGE).getId());
        assertEquals("pinned-small", picker.describePins().get(Grade.SMALL).getId());
    }

    /**
     * The fill-in, when nothing of the order qualifies or the grade has none, is by list price
     * per million, input plus output: cheapest first, an unpriced entry after every priced one,
     * and an entry of the order is never part of it.
     */
    @Test
    void theUnplacedEntriesFillInCheapestFirstAndAnUnpricedOneLast() {
        useEdited(root -> {
            ((ObjectNode) root.get("pins")).remove("SMALL");
            ArrayNode models = (ArrayNode) root.get("models");
            for (JsonNode entry : models) {
                if (entry.get("id").asText().equals("pinned-small")) {
                    priced((ObjectNode) entry, 1.0, 5.0);
                }
            }
            models.add(priced(entry("cheap-small", "SMALL"), 0.1, 0.4));
        });
        assertEquals("cheap-small", picker(spec -> true).provide(seat(Grade.SMALL), situation()).getId(), "the cheapest of the grade");
        assertEquals("pinned-small", picker(spec -> !spec.getId().equals("cheap-small")).provide(seat(Grade.SMALL), situation()).getId(),
                "the next by price, ahead of other-small, which is unpriced");
        assertEquals("other-small", picker(spec -> spec.getId().equals("other-small")).provide(seat(Grade.SMALL), situation()).getId(),
                "an unpriced entry still serves when it is the only one callable");
    }

    @Test
    void nothingCallableRefusesNamingTheCredentialToProvide() {
        // XL's only entry rides the registered test provider: the refusal names its credential.
        DefaultModelPicker picker = picker(spec -> false);
        ModelResolutionError ex = assertThrows(ModelResolutionError.class,
                () -> picker.provide(seat(Grade.XL), situation()));
        assertTrue(ex.getMessage().contains("XL"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[fake-llm]"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FAKE_LLM_KEY"), ex.getMessage());
    }

    @Test
    void entryOfAnAbsentProviderIsNotCallableAndTheRefusalNamesTheArtifact() {
        // MEDIUM's only entry names a provider no artifact on this classpath declares: never
        // served, and with nothing callable anywhere else, the refusal says which artifact is missing.
        DefaultModelPicker picker = picker(spec -> false);
        ModelResolutionError ex = assertThrows(ModelResolutionError.class,
                () -> picker.provide(seat(Grade.MEDIUM), situation()));
        assertTrue(ex.getMessage().contains("MEDIUM, above or below"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[openai]"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no provider artifact"), ex.getMessage());
    }

    @Test
    void aGradeWithNothingCallableIsServedByTheNearestGradeAbove() {
        // MEDIUM's only entry is unservable; LARGE has live-large: MEDIUM seats run on it, over-qualified and legal
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("live-large", picker.provide(seat(Grade.MEDIUM), situation()).getId());
        assertEquals("live-large", picker.describePins().get(Grade.MEDIUM).getId(), "the stand-in is reported as the grade's choice");
        // an order whose entries cannot be called is climbed past like an empty grade: MICRO, with the SMALL order uncallable, lands on XL
        assertEquals("only-xl", picker(spec -> spec.getGrade() == Grade.XL).provide(seat(Grade.MICRO), situation()).getId());
    }

    @Test
    void aGradeWithNothingAtOrAboveIsServedByTheNearestGradeBelow() {
        // MEGA has no entry and nothing is above it: the seat runs on only-xl, below the grade it declared
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("only-xl", picker.provide(seat(Grade.MEGA), situation()).getId());
        assertEquals("only-xl", picker.describePins().get(Grade.MEGA).getId(), "the stand-in is reported as the grade's choice");
        // with XL gone too, the walk continues down to live-large
        assertEquals("live-large", picker(spec -> spec.getGrade() != Grade.XL).provide(seat(Grade.MEGA), situation()).getId());
    }

    /**
     * The two fallbacks are deployment policy: with stronger fallback off, a grade with nothing
     * callable is served from below or refused; with weaker fallback off, from above or refused;
     * with both off, the grade serves itself or the request is refused naming the grade alone.
     */
    @Test
    void aFallbackSwitchedOffRefusesInsteadOfWalking() {
        ModelSettings settings = Settings.get(ModelSettings.class);
        try {
            settings.fallbackToStronger = false;
            // MEDIUM's only entry is unservable; above is closed, so the walk goes below to SMALL's order
            assertEquals("pinned-small", picker(spec -> true).provide(seat(Grade.MEDIUM), situation()).getId());
            settings.fallbackToWeaker = false;
            ModelResolutionError neither = assertThrows(ModelResolutionError.class,
                    () -> picker(spec -> true).provide(seat(Grade.MEDIUM), situation()));
            assertTrue(neither.getMessage().contains("grade MEDIUM can serve"), "the grade alone was searched: " + neither.getMessage());
            settings.fallbackToStronger = true;
            // MEGA has nothing and nothing above it; below is closed
            ModelResolutionError mega = assertThrows(ModelResolutionError.class,
                    () -> picker(spec -> true).provide(seat(Grade.MEGA), situation()));
            assertTrue(mega.getMessage().contains("grade MEGA can serve"), mega.getMessage());
            assertEquals("live-large", picker(spec -> true).provide(seat(Grade.MEDIUM), situation()).getId(), "above is open again");
            assertEquals("only-xl", picker(spec -> true).provide(seat(Grade.CEILING), situation()).getId(),
                    "a best-available request walks down whatever the flags say");
        }
        finally {
            settings.fallbackToStronger = true;
            settings.fallbackToWeaker = true;
        }
    }

    /** An entry the availability probe has turned off is passed over like one that cannot be called, never refused for. */
    @Test
    void aProbeDeadEntryIsPassedOver() {
        useEdited(root -> ((ObjectNode) root.get("pins")).putArray("SMALL").add("pinned-small").add("other-small"));
        DefaultModelPicker picker = picker(spec -> true);
        ProbeOutcome down = new ProbeOutcome();
        down.setSpecId("pinned-small");
        down.setStatus(ProbeOutcome.Status.FAILED);
        down.setClassification(ProbeOutcome.Classification.AVAILABILITY);
        down.setProbedAt(Instant.now().minus(Duration.ofHours(1)));
        down.setErrorClass("ExternalServiceException");
        down.setErrorMessage("connection refused");
        picker.recordProbe(down);
        assertEquals("other-small", picker.provide(seat(Grade.SMALL), situation()).getId(), "the order's next entry serves");
        assertEquals("other-small", picker.describePins().get(Grade.SMALL).getId());
    }

    /**
     * An envelope that refuses every entry of the grade and of every grade above is served from
     * below: the walk passes over a refused entry and never serves one.
     */
    @Test
    void anEnvelopeRefusedGradeWalksUpThenDown() {
        Situation refusing = new Situation();
        refusing.setEnvelope(spec -> spec.getGrade() != Grade.LARGE && spec.getGrade() != Grade.XL);
        assertEquals("pinned-small", picker(spec -> true).provide(seat(Grade.LARGE), refusing).getId(),
                "LARGE and XL refused, MEGA empty: the nearest permitted entry is SMALL's default");
    }

    /**
     * A CEILING request is served by the strongest entry this application may call: the MEGA entry
     * where the envelope permits it, the XL entry where the envelope refuses the MEGA one - the
     * application whose envelope forbids data sharing while the only MEGA model requires it.
     */
    @Test
    void aCeilingRequestIsServedByTheStrongestEntryTheEnvelopePermits() {
        useEdited(root -> ((ArrayNode) root.get("models")).add(entry("only-mega", "MEGA")));
        DefaultModelPicker picker = picker(spec -> true);
        assertEquals("only-mega", picker.provide(seat(Grade.CEILING), situation()).getId());
        Situation refusing = new Situation();
        refusing.setEnvelope(spec -> !spec.getId().equals("only-mega"));
        assertEquals("only-xl", picker.provide(seat(Grade.CEILING), refusing).getId());
        assertEquals("only-xl", picker.provide(seat(Grade.MEGA), refusing).getId(), "and so is an explicit MEGA request, below its grade");
        assertEquals(Grade.MEGA, picker.ceiling(), "the reported ceiling is what the deployment can call, judged before any envelope");
    }

    @Test
    void embeddingsFollowThePinThenTheFirstCallableEntry() {
        assertEquals("embed-b", picker(spec -> false).embeddingsSpec().getId());
        Models.resetAll();
        Settings.get(ModelSettings.class).backendClass = UnpinnedCatalog.class;
        assertEquals("embed-a", picker(spec -> true).embeddingsSpec().getId());
        assertNull(picker(spec -> false).embeddingsSpec());
    }

    @Test
    void anUnpinnedDecisionIsTheFirstCallableEntryTheEndpointServes() {
        // the catalog carries a hosted and a local decision entry on one key; the endpoint
        // behind the credential's host serves one family, and the picker asks which
        try {
            FakeDecisionProvider.served = wire -> true;
            assertEquals("decide-hosted", picker(spec -> true).decisionSpec().getId(), "first in catalog order when the endpoint serves both");
            FakeDecisionProvider.served = "decide-local"::equals;
            assertEquals("decide-local", picker(spec -> true).decisionSpec().getId(), "the entry the endpoint lists, never the one it would refuse");
            FakeDecisionProvider.served = wire -> false;
            assertNull(picker(spec -> true).decisionSpec(), "an endpoint serving neither leaves the seat to refuse by name");
            assertNull(picker(spec -> false).decisionSpec(), "and so does a provider that is not configured");
        }
        finally {
            FakeDecisionProvider.served = wire -> true;
        }
    }

    @Test
    void theDecisionPinIsTheDeclarationAndAnUnpinnedChoiceIsMadeOnce() {
        try {
            useEdited(root -> ((ObjectNode) root.get("pins")).put("decision", "decide-local"));
            FakeDecisionProvider.served = "decide-hosted"::equals;
            assertEquals("decide-local", picker(spec -> true).decisionSpec().getId(), "the pin, whatever the endpoint lists: the deployment's word");
            Settings.get(ModelSettings.class).backendClass = PinnedCatalog.class;
            Models.resetAll();
            FakeDecisionProvider.served = wire -> true;
            DefaultModelPicker picker = picker(spec -> true);
            assertEquals("decide-hosted", picker.decisionSpec().getId());
            FakeDecisionProvider.served = "decide-local"::equals;
            assertEquals("decide-hosted", picker.decisionSpec().getId(), "chosen once per picker: the first answer stands");
            assertEquals("decide-local", picker(spec -> true).decisionSpec().getId(), "a fresh picker asks again");
        }
        finally {
            FakeDecisionProvider.served = wire -> true;
        }
    }

    /** The ceiling is derived, never declared: the highest rung with a callable entry, following the credentials as they change. */
    @Test
    void ceilingIsTheHighestRungWithACallableEntry() {
        // Nothing callable: the SMALL order places an entry, and an order is no rung the deployment serves.
        assertNull(picker(spec -> false).ceiling());
        // Everything callable: XL has a live entry, MEGA none, so XL is the top.
        assertEquals(Grade.XL, picker(spec -> true).ceiling());
        Models.resetAll();
        Settings.get(ModelSettings.class).backendClass = UnpinnedCatalog.class;
        assertEquals(Grade.XL, picker(spec -> true).ceiling());
        // Only SMALL callable: the top is SMALL.
        assertEquals(Grade.SMALL, picker(spec -> spec.getGrade() == Grade.SMALL).ceiling());
        assertNull(picker(spec -> false).ceiling());
    }

    /**
     * A request is served by what it declares it sends, never by what it carries: the first
     * entry of the order that accepts every declared input, an entry that does not being passed
     * over; then the grade's other entries that accept them, cheapest first; then the grade
     * above; and a refusal names the inputs. The same seat declaring nothing gets the order's
     * first entry.
     */
    @Test
    void aRequestIsServedByTheFirstEntryOfTheOrderThatAcceptsWhatItSends() {
        useEdited(root -> {
            ArrayNode models = (ArrayNode) root.get("models");
            models.add(priced(accepting(entry("seeing-cheap-small", "SMALL"), true, false), 0.06, 0.24));
            models.add(accepting(entry("seeing-unpriced-small", "SMALL"), true, false));
            models.add(priced(accepting(entry("seeing-dear-small", "SMALL"), true, false), 3.0, 15.0));
            models.add(priced(accepting(entry("seeing-small", "SMALL"), true, false), 1.0, 5.0));
            models.add(priced(accepting(entry("reading-large", "LARGE"), true, true), 6.0, 30.0));
            ((ObjectNode) root.get("pins")).putArray("SMALL").add("pinned-small").add("seeing-small");
        });
        DefaultModelPicker all = picker(spec -> true);
        assertEquals("seeing-small", all.provide(seat(Grade.SMALL), sending(Input.IMAGES)).getId(),
                "pinned-small does not accept images and is passed over for the order's next");
        assertEquals("pinned-small", all.provide(seat(Grade.SMALL), situation()).getId(), "declaring nothing, the order's first serves");
        assertEquals("seeing-cheap-small", picker(spec -> !spec.getId().equals("seeing-small")).provide(seat(Grade.SMALL), sending(Input.IMAGES)).getId(),
                "nothing of the order qualifies: the cheapest unplaced entry that accepts images");
        assertEquals("seeing-dear-small", picker(spec -> Set.of("seeing-dear-small", "seeing-unpriced-small").contains(spec.getId()))
                .provide(seat(Grade.SMALL), sending(Input.IMAGES)).getId(), "a priced entry ahead of an unpriced one");
        assertEquals("reading-large", all.provide(seat(Grade.SMALL), sending(Input.DOCUMENTS)).getId(),
                "nothing of SMALL accepts documents: the nearest grade above that has one");
        assertEquals("reading-large", all.provide(seat(Grade.SMALL), sending(Input.IMAGES, Input.DOCUMENTS)).getId(),
                "both inputs: only an entry accepting both serves");
        ModelResolutionError ex = assertThrows(ModelResolutionError.class,
                () -> picker(spec -> false).provide(seat(Grade.SMALL), sending(Input.DOCUMENTS)));
        assertTrue(ex.getMessage().contains("that accepts documents"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[fake-llm]"), ex.getMessage());
    }

    private static ObjectNode priced(ObjectNode entry, double input, double output) {
        entry.put("currency", "USD");
        entry.put("input_price_per_million", input);
        entry.put("output_price_per_million", output);
        return entry;
    }

    private static ObjectNode accepting(ObjectNode entry, boolean images, boolean documents) {
        entry.put("supports_vision", images);
        entry.put("supports_documents", documents);
        return entry;
    }

    private static ObjectNode entry(String id, String grade) {
        ObjectNode entry = NucleoJsonSerializer.createObjectNode();
        entry.put("id", id);
        entry.put("identity", id);
        entry.put("grade", grade);
        entry.put("provider_key", "fake-llm");
        entry.put("wire_model_id", id);
        entry.put("max_context_tokens", 1000);
        entry.put("max_output_tokens", 100);
        entry.put("thinking_mode", "NONE");
        entry.put("tpm", 5000);
        entry.put("rpm", 100);
        return entry;
    }

    /** The same entries with no pins object, for the fallback rules. */
    public static class UnpinnedCatalog extends JsonModelsBackend {
        public UnpinnedCatalog() {
            super(List.of(new Layer("unpinned", unpinned(), false)));
        }

        private static String unpinned() {
            try (InputStream is = DefaultModelPickerTest.class.getResourceAsStream("/models-pins.json")) {
                ObjectNode root = (ObjectNode) NucleoJsonSerializer.readTree(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                root.remove("pins");
                return root.toString();
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
