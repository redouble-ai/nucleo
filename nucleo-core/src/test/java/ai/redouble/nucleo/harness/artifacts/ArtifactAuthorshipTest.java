/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model never authors an artifact. Wherever a model's reply holds one - a field of its
 * answer, an element of a list, a value of a map, the answer itself, the input of a tool it
 * calls - the model chose a reference, and the artifact the caller receives is the one the
 * conversation's registry holds under that reference.
 *
 * <p><b>The model is asked for a reference and nothing else.</b> The schema a model reads
 * offers an artifact-typed field as its {@code artifact_ref} alone, in the answer schema and
 * in a tool's input schema; the artifact's content is shown to the model in the registry
 * section and appears in no schema it fills in.
 *
 * <p><b>What the model writes beside the reference never arrives.</b> A reply carrying a
 * reference the registry holds, with content the model invented beside it, yields the
 * registry's own object, the same instance, with its own content.
 *
 * <p><b>What the registry cannot supply is refused.</b> An artifact written with no
 * reference, with a reference the registry does not hold, or with a reference to an
 * artifact of another type than the place declares, is a violation named by the path of the
 * place, never by the value the model wrote.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-02)
 */
class ArtifactAuthorshipTest {
    private static final String REAL_DOI = "10.1038/s41586-024-07386-0";
    private static final String INVENTED_DOI = "10.9999/invented";

    /** An answer that hands back one citation by its concrete type, beside words of the model's own. */
    public static class ByConcreteType {
        private String summary;
        private CitationArtifact best;

        public String getSummary() {return summary;}

        public void setSummary(String summary) {this.summary = summary;}

        public CitationArtifact getBest() {return best;}

        public void setBest(CitationArtifact best) {this.best = best;}
    }

    /** An answer that hands back some artifact, whatever it turns out to be. */
    public static class ByInterface {
        private Artifact best;

        public Artifact getBest() {return best;}

        public void setBest(Artifact best) {this.best = best;}
    }

    /** An answer holding artifacts in a list, in a map, and inside a plain object of its own. */
    public static class Nested {
        private List<CitationArtifact> cited;
        private Map<String, CitationArtifact> byTopic;
        private ByConcreteType inner;

        public List<CitationArtifact> getCited() {return cited;}

        public void setCited(List<CitationArtifact> cited) {this.cited = cited;}

        public Map<String, CitationArtifact> getByTopic() {return byTopic;}

        public void setByTopic(Map<String, CitationArtifact> byTopic) {this.byTopic = byTopic;}

        public ByConcreteType getInner() {return inner;}

        public void setInner(ByConcreteType inner) {this.inner = inner;}
    }

    private ArtifactRegistry registry;
    private CitationArtifact held;
    private String ref;

    @BeforeAll
    static void aliases() {
        TypeAliasRegistry.register(CitationArtifact.class);
        TypeAliasRegistry.register(PersonArtifact.class);
    }

    @BeforeEach
    void aRegistryHoldingOneCitation() {
        registry = new ArtifactRegistry();
        held = new CitationArtifact();
        held.setDoi(REAL_DOI);
        ref = registry.register(held);
    }

    /** What a model would write for an artifact field: the reference, and content of its own invention beside it. */
    private String written(String reference) {
        return "{\"artifact_ref\": \"" + reference + "\", \"doi\": \"" + INVENTED_DOI + "\"}";
    }

    // ---- what the model is asked for ----

    @Test
    void theAnswerSchemaOffersAnArtifactFieldAsItsReferenceAlone() {
        String schema = new PojoResponseHandler<>(ByConcreteType.class).writeDefinition().toLLMSchema();
        assertTrue(schema.contains("\"artifact_ref\""), "the reference is what the model is asked for: " + schema);
        assertFalse(schema.contains("\"doi\""), "an artifact's content is not the model's to write: " + schema);
        assertFalse(schema.contains("\"authors\""), "and no other content field is offered either: " + schema);
        assertTrue(schema.contains("\"summary\""), "the answer's own fields are still the model's words: " + schema);
    }

    @Test
    void anArtifactFieldDeclaredByTheInterfaceIsOfferedTheSameWay() {
        String schema = new PojoResponseHandler<>(ByInterface.class).writeDefinition().toLLMSchema();
        assertTrue(schema.contains("\"artifact_ref\""), "an artifact of any type is referred to the same way: " + schema);
    }

    @Test
    void aToolInputSchemaOfferedToAModelAsksForTheReferenceAlone() throws IOException {
        String canonical = new PojoResponseHandler<>(Nested.class).writeDefinition().toJsonSchema();
        assertTrue(canonical.contains("\"doi\""), "the canonical schema, for a caller that sends artifacts across a process boundary, describes them in full");
        JsonNode offered = NucleoJsonSerializer.readTree(NucleoSchemaKeywords.forModel(canonical));
        JsonNode inList = offered.at("/properties/cited/items/properties");
        assertEquals(List.of("artifact_ref"), names(inList), "an array of artifacts is an array of references");
        JsonNode nested = offered.at("/properties/inner/properties/best/properties");
        assertEquals(List.of("artifact_ref"), names(nested), "an artifact inside a plain object is a reference too");
        assertTrue(offered.at("/properties/inner/properties/summary").isObject(), "and the plain object keeps its own fields");
        assertFalse(offered.toString().contains("x-nucleo-"), "the harness keywords are gone from what a model reads");
    }

    private static List<String> names(JsonNode properties) {
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- what the caller receives ----

    @Test
    void aConcreteArtifactFieldArrivesAsTheRegistrysObject() throws IOException {
        ByConcreteType parsed = NucleoJsonSerializer.parseLLMResponse(
                "{\"summary\": \"the model's words\", \"best\": " + written(ref) + "}", ByConcreteType.class);
        assertEquals(INVENTED_DOI, parsed.getBest().getDoi(), "the parse alone carries what the model wrote, which is why the registry is asked");
        List<String> violations = new ArrayList<>();
        ByConcreteType answer = registry.held(parsed, violations);
        assertTrue(violations.isEmpty(), "a reference the registry holds is no violation: " + violations);
        assertSame(held, answer.getBest(), "the caller receives the object the tool built, not a copy and not the model's");
        assertEquals(REAL_DOI, answer.getBest().getDoi(), "with the content the tool gave it");
        assertEquals("the model's words", answer.getSummary(), "and the answer's own fields are untouched");
    }

    @Test
    void anInterfaceArtifactFieldArrivesAsTheRegistrysObject() throws IOException {
        ByInterface parsed = NucleoJsonSerializer.parseLLMResponse("{\"best\": " + written(ref) + "}", ByInterface.class);
        List<String> violations = new ArrayList<>();
        assertSame(held, registry.held(parsed, violations).getBest());
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    void theReferenceMayBeWrittenTheWayTheModelIsShownIt() throws IOException {
        ByConcreteType parsed = NucleoJsonSerializer.parseLLMResponse("{\"best\": {\"@ref\": \"" + ref + "\"}}", ByConcreteType.class);
        List<String> violations = new ArrayList<>();
        assertSame(held, registry.held(parsed, violations).getBest(), "an artifact is shown as {\"@ref\": ...} and may be referred to in that shape");
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    void artifactsInAListAMapAndAPlainObjectAllArriveFromTheRegistry() throws IOException {
        Nested parsed = NucleoJsonSerializer.parseLLMResponse("{\"cited\": [" + written(ref) + "], \"by_topic\": {\"crispr\": " + written(ref)
                + "}, \"inner\": {\"best\": " + written(ref) + "}}", Nested.class);
        List<String> violations = new ArrayList<>();
        Nested answer = registry.held(parsed, violations);
        assertTrue(violations.isEmpty(), violations.toString());
        assertSame(held, answer.getCited().get(0), "an element of a list");
        assertSame(held, answer.getByTopic().get("crispr"), "a value of a map");
        assertSame(held, answer.getInner().getBest(), "a field of a plain object inside the answer");
    }

    @Test
    void anAnswerThatIsItselfAnArtifactIsTheRegistrysObject() throws IOException {
        CitationArtifact parsed = NucleoJsonSerializer.parseLLMResponse(written(ref), CitationArtifact.class);
        List<String> violations = new ArrayList<>();
        assertSame(held, registry.held(parsed, violations), "an agent whose answer type is an artifact picks one, it does not write one");
        assertTrue(violations.isEmpty(), violations.toString());
    }

    // ---- what is refused ----

    @Test
    void aReferenceTheRegistryDoesNotHoldIsRefusedByThePathOfItsPlace() throws IOException {
        Nested parsed = NucleoJsonSerializer.parseLLMResponse("{\"cited\": [" + written(ref) + ", " + written("«artifact:link:cite~never1»") + "]}", Nested.class);
        List<String> violations = new ArrayList<>();
        registry.held(parsed, violations);
        assertEquals(1, violations.size(), "the one unknown reference, and not the known one beside it: " + violations);
        assertTrue(violations.get(0).contains("cited[1]"), "the place is named by its path in the answer: " + violations);
        assertFalse(violations.get(0).contains("never1"), "and never by the value the model wrote: " + violations);
        assertFalse(violations.get(0).contains(INVENTED_DOI), violations.toString());
    }

    @Test
    void anArtifactWrittenWithNoReferenceIsRefused() throws IOException {
        ByConcreteType parsed = NucleoJsonSerializer.parseLLMResponse("{\"best\": {\"doi\": \"" + INVENTED_DOI + "\"}}", ByConcreteType.class);
        List<String> violations = new ArrayList<>();
        registry.held(parsed, violations);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("'best'") && violations.get(0).contains("artifact_ref"),
                "the refusal names the place and what would have been accepted: " + violations);
    }

    @Test
    void aReferenceToAnArtifactOfAnotherTypeIsRefused() throws IOException {
        PersonArtifact person = new PersonArtifact();
        String personRef = registry.register(person);
        ByConcreteType parsed = NucleoJsonSerializer.parseLLMResponse("{\"best\": {\"artifact_ref\": \"" + ref + "\"}}", ByConcreteType.class);
        parsed.getBest().setArtifactRef(personRef);
        List<String> violations = new ArrayList<>();
        registry.held(parsed, violations);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("PersonArtifact") && violations.get(0).contains("CitationArtifact"),
                "the refusal says what was referred to and what the place takes: " + violations);
    }
}
