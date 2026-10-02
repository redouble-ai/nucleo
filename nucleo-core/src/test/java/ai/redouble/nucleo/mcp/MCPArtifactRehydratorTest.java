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
 * Artifact identity surviving a process boundary. The wire form already carries everything
 * needed - a ref, whose alias names the type that wrote it - so this is the same round trip
 * a conversation restore performs, pointed at an MCP result.
 *
 * <p>The degradation ladder is the interesting part: a consumer holding an ancestor but not
 * the exact class still gets a typed artifact, and a consumer holding neither gets null so
 * the caller keeps the generic {@link MCPArtifact} path.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public class MCPArtifactRehydratorTest {

    @TypeAlias("mcp-rehydrate-fixture")
    public static class ServedThing extends AbstractArtifact {
        @LLMDescription("What it is called")
        private String label;
        @LLMDescription("An identifier a paraphrase would ruin")
        private String doi;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDoi() { return doi; }
        public void setDoi(String doi) { this.doi = doi; }
    }

    @BeforeEach
    void registerTypes() {
        TypeAliasRegistry.register(ServedThing.class);
        TypeAliasRegistry.register(LinkArtifact.class);
    }

    /** What the serving process writes: the result, full form, refs minted. */
    private static String served(Artifact artifact) {
        new ArtifactRegistry().indexReachableFrom(artifact);
        return NucleoJsonSerializer.write(artifact);
    }

    @Test
    void theRefKeyIsWhatSerializationActuallyEmits() {
        ServedThing thing = new ServedThing();
        thing.setLabel("anything");
        assertTrue(served(thing).contains("\"" + MCPArtifactRehydrator.ARTIFACT_REF + "\""),
                "the rehydrator keys on this name; a naming-strategy change must fail here");
    }

    @Test
    void aServedArtifactComesBackTyped() {
        ServedThing sent = new ServedThing();
        sent.setLabel("Compound X");
        sent.setDoi("10.1038/s41586-024-07386-0");
        String wire = served(sent);

        Artifact received = MCPArtifactRehydrator.rehydrate(wire);
        assertInstanceOf(ServedThing.class, received);
        ServedThing typed = (ServedThing)received;
        assertEquals("Compound X", typed.getLabel());
        assertEquals("10.1038/s41586-024-07386-0", typed.getDoi(), "the identifier crosses intact, never retyped");
        assertEquals(sent.getArtifactRef(), typed.getArtifactRef(), "the producer's ref is preserved, so a trace follows across the boundary");
    }

    @Test
    void summariesDoNotCrossSoTheConsumerDecidesItsOwn() {
        ServedThing sent = new ServedThing();
        sent.setLabel("Compound X");
        sent.cacheSummary("label", new SummarizedField("full text", "summary"));

        ServedThing received = (ServedThing)MCPArtifactRehydrator.rehydrate(served(sent));
        assertNull(received.getCachedSummary("label"),
                "an arriving artifact is newly born: summarization is the consuming harness's policy");
    }

    @Test
    void anUnknownSubtypeFallsBackToTheNearestKnownAncestor() {
        String wire = """
                {"artifact_ref":"«artifact:link:cite:nonesuch~a7f3b2»",
                 "title":"A paper we have no class for","url":"https://example.org/p"}""";

        Artifact received = MCPArtifactRehydrator.rehydrate(wire);
        assertInstanceOf(LinkArtifact.class, received, "link:cite:nonesuch walks up to link");
        assertEquals("A paper we have no class for", ((LinkArtifact)received).getTitle());
    }

    @Test
    void anUnknownRootAliasKeepsTheGenericPath() {
        String wire = "{\"artifact_ref\":\"«artifact:nothing-we-know~a7f3b2»\",\"title\":\"x\"}";
        assertNull(MCPArtifactRehydrator.rehydrate(wire));
    }

    @Test
    void aListArtifactRootComesBackWithItsIterands() {
        ServedThing first = new ServedThing();
        first.setLabel("one");
        ServedThing second = new ServedThing();
        second.setLabel("two");
        ListArtifact<ServedThing> sent = new ListArtifact<>();
        sent.setIterands(List.of(first, second));
        sent.setIterandTypeAlias("mcp-rehydrate-fixture");
        TypeAliasRegistry.register(ListArtifact.class);

        Artifact received = MCPArtifactRehydrator.rehydrate(served(sent));
        assertInstanceOf(ListArtifact.class, received, "the other shape an expert may answer with");
        @SuppressWarnings("unchecked")
        ListArtifact<Artifact> list = (ListArtifact<Artifact>)received;
        assertEquals(2, list.getIterands().size());
        assertInstanceOf(ServedThing.class, list.getIterands().get(0), "iterands rebuild from their own refs");
        assertEquals("two", ((ServedThing)list.getIterands().get(1)).getLabel());
    }

    @Test
    void aPayloadThatWillNotFitItsClaimedTypeFallsBack() {
        TypeAliasRegistry.register(ListArtifact.class);
        // The ref claims a list; the body says its iterands are a string. The two sides
        // disagree about the type, which is worth a log line and never a failed call.
        String wire = "{\"artifact_ref\":\"«artifact:list~a7f3b2»\",\"iterands\":\"not an array\"}";
        assertNull(MCPArtifactRehydrator.rehydrate(wire));
    }

    @Test
    void aThirdPartyPayloadKeepsTheGenericPath() {
        assertNull(MCPArtifactRehydrator.rehydrate("{\"results\":[{\"title\":\"a normal MCP server's answer\"}]}"));
        assertNull(MCPArtifactRehydrator.rehydrate("\"a bare string\""));
        assertNull(MCPArtifactRehydrator.rehydrate("null"));
        assertNull(MCPArtifactRehydrator.rehydrate("not json at all"));
        assertNull(MCPArtifactRehydrator.rehydrate(null));
    }
}
