/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What survives the crossing, by SHAPE rather than by happy path.
 *
 * <p>A flat artifact and a homogeneous list were already pinned. The shapes that matter to
 * anyone actually shipping artifacts are the awkward ones: an artifact nested in an
 * artifact, a field typed as the interface rather than a class, a mixed list, and above all
 * PARTIAL provenance - a payload whose type this process knows only in part, or not at all.
 * Those are where a transport quietly loses data instead of failing, and where the answer
 * has to be known rather than assumed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class ArtifactShapeCrossingTest {

    @TypeAlias("shape:leaf")
    public static class Leaf extends AbstractArtifact {
        @LLMDescription("An identifier a paraphrase would ruin")
        private String doi;

        public String getDoi() { return doi; }
        public void setDoi(String doi) { this.doi = doi; }
    }

    /** Holds another artifact by its concrete type. */
    @TypeAlias("shape:holder")
    public static class Holder extends AbstractArtifact {
        @LLMDescription("The one inside")
        private Leaf inner;

        public Leaf getInner() { return inner; }
        public void setInner(Leaf inner) { this.inner = inner; }
    }

    /** Holds another artifact by the INTERFACE, which Jackson cannot instantiate on its own. */
    @TypeAlias("shape:abstract-holder")
    public static class AbstractHolder extends AbstractArtifact {
        @LLMDescription("The one inside, declared only as an artifact")
        private Artifact inner;

        public Artifact getInner() { return inner; }
        public void setInner(Artifact inner) { this.inner = inner; }
    }

    @BeforeEach
    void registerWhatTheConsumerKnows() {
        TypeAliasRegistry.register(Leaf.class);
        TypeAliasRegistry.register(Holder.class);
        TypeAliasRegistry.register(AbstractHolder.class);
        TypeAliasRegistry.register(LinkArtifact.class);
        TypeAliasRegistry.register(ListArtifact.class);
    }

    private static String served(Artifact artifact) {
        new ArtifactRegistry().indexReachableFrom(artifact);
        return NucleoJsonSerializer.write(artifact);
    }

    private static Leaf leaf(String doi) {
        Leaf leaf = new Leaf();
        leaf.setDoi(doi);
        return leaf;
    }

    // ---- nesting ----

    @Test
    void anArtifactNestedByItsConcreteTypeCrosses() {
        Holder sent = new Holder();
        sent.setInner(leaf("10.1038/nested"));

        Artifact received = MCPArtifactRehydrator.rehydrate(served(sent));
        assertInstanceOf(Holder.class, received);
        assertNotNull(((Holder)received).getInner(), "the nested artifact came through");
        assertEquals("10.1038/nested", ((Holder)received).getInner().getDoi());
    }

    @Test
    void anArtifactNestedBehindTheInterfaceCrossesToo() {
        // "Some artifact, whatever it turns out to be" is a shape a field should be able to
        // declare. Jackson cannot construct an interface, so this used to fail the whole read
        // and the result degraded to the generic form; ArtifactRefDeserializer resolves the
        // concrete type from the nested artifact's own ref instead.
        AbstractHolder sent = new AbstractHolder();
        sent.setInner(leaf("10.1038/abstract"));

        Artifact received = MCPArtifactRehydrator.rehydrate(served(sent));
        assertInstanceOf(AbstractHolder.class, received);
        Artifact inner = ((AbstractHolder)received).getInner();
        assertInstanceOf(Leaf.class, inner, "the concrete type came from the ref, not from the declaration");
        assertEquals("10.1038/abstract", ((Leaf)inner).getDoi());
    }

    @Test
    void anInterfaceFieldNamingAnUnknownTypeFailsRatherThanArrivingEmpty() {
        String wire = "{\"artifact_ref\":\"«artifact:shape:abstract-holder~e1»\","
                + "\"inner\":{\"artifact_ref\":\"«artifact:shape:unheld~e2»\",\"title\":\"newer\"}}";
        // Degrading the whole result is the right answer: an AbstractHolder whose inner came
        // back null would be a result quietly missing the thing it was about.
        assertNull(MCPArtifactRehydrator.rehydrate(wire));
    }

    // ---- lists ----

    @Test
    void aMixedListCrossesWithEachIterandItsOwnType() {
        Leaf leaf = leaf("10.1038/mixed");
        Holder holder = new Holder();
        holder.setInner(leaf("10.1038/inner"));
        ListArtifact<Artifact> sent = new ListArtifact<>();
        sent.setIterands(List.of(leaf, holder));

        Artifact received = MCPArtifactRehydrator.rehydrate(served(sent));
        assertInstanceOf(ListArtifact.class, received);
        @SuppressWarnings("unchecked")
        List<Artifact> iterands = ((ListArtifact<Artifact>)received).getIterands();
        assertInstanceOf(Leaf.class, iterands.get(0));
        assertInstanceOf(Holder.class, iterands.get(1), "each iterand resolves from its own ref");
    }

    /**
     * A type the producer has and this process does not, expressed the only way it can be in
     * one JVM: as wire text. Serializing a real class here would REGISTER its alias -
     * {@code TypeAliasRegistry.getAlias} registers lazily when it mints a ref - so absence
     * cannot be simulated by declining to register. Hand-built payloads are the only honest
     * stand-in for a producer we do not share a classpath with.
     */
    private static final String UNHELD_LEAF =
            "{\"artifact_ref\":\"«artifact:shape:unheld~b41c\",\"title\":\"from a newer producer\"}";

    @Test
    void oneUnknownIterandDecidesTheFateOfTheWholeList() {
        // Partial provenance in the shape that matters most: two of three members are types
        // this process holds, one is not. The two possible answers - all three degrade, or
        // the known two survive - are very different promises, so whichever it is gets pinned.
        String wire = "{\"artifact_ref\":\"«artifact:list~c0de»\",\"iterands\":["
                + "{\"artifact_ref\":\"«artifact:shape:leaf~a1»\",\"doi\":\"10.1038/a\"},"
                + UNHELD_LEAF.replace("«artifact:shape:unheld~b41c", "«artifact:shape:unheld~b2»") + ","
                + "{\"artifact_ref\":\"«artifact:shape:leaf~a3»\",\"doi\":\"10.1038/b\"}]}";

        // The answer is all-or-nothing, by design: ArtifactListDeserializer refuses a list it
        // cannot resolve every member of, rather than returning a shorter one. So two known
        // members do not survive one unknown - the caller keeps the generic form and knows it
        // has untyped data, instead of a typed list quietly missing a row.
        assertNull(MCPArtifactRehydrator.rehydrate(wire));
    }

    // ---- partial provenance at the root ----

    @Test
    void anUnknownSubtypeArrivesAsItsAncestorAndKeepsTheRefThatNamesIt() {
        String wire = "{\"artifact_ref\":\"«artifact:link:shape:unheld~d7f0»\",\"title\":\"A paper\","
                + "\"url\":\"https://example.org/p\",\"doi\":\"10.1038/only-the-subtype-knows\"}";

        Artifact received = MCPArtifactRehydrator.rehydrate(wire);
        assertInstanceOf(LinkArtifact.class, received, "walks up to the ancestor this process holds");
        assertEquals("A paper", ((LinkArtifact)received).getTitle());
        // The subtype's own field cannot survive into a type that does not declare it. What
        // must survive is the REF, because it names the real type and is what makes the loss
        // legible: a consumer can see it holds a link:shape:unheld as a plain link.
        assertTrue(received.getArtifactRef().contains("link:shape:unheld"),
                "the ref still names what it really is: " + received.getArtifactRef());
    }

    @Test
    void anAncestorArrivalDropsTheSubtypeFieldSilently() {
        // The cost of the fallback, pinned rather than assumed. Jackson is configured not to
        // fail on unknown properties, which is what lets an ancestor accept a subtype's
        // payload at all - and the same setting is why the extra field disappears without a
        // word. The ref is the only trace, which is why the previous test guards it.
        String wire = "{\"artifact_ref\":\"«artifact:link:shape:unheld~d7f0»\",\"title\":\"A paper\","
                + "\"doi\":\"10.1038/only-the-subtype-knows\"}";
        Artifact received = MCPArtifactRehydrator.rehydrate(wire);
        assertFalse(NucleoJsonSerializer.write(received).contains("only-the-subtype-knows"),
                "an ancestor has nowhere to keep it: " + NucleoJsonSerializer.write(received));
    }
}
