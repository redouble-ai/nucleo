/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the artifact dual-channel's entry points at the conversation boundary:
 * {@code normalizeToKey} resolves every reference variant an LLM emits to the one
 * canonical key (so a sloppy echo still hits the registry), registration keeps an
 * existing ref and mints only when missing, the alias extraction reads the hierarchical
 * type out of a ref, a referenced artifact prints as its ref, and the registry dump rides
 * the prepared conversation as a TRAILING USER TURN - data, never instruction - with the
 * documented section header and the {@code [SUMMARY: N chars]} prefix that signals
 * drill-down tools exist.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class ArtifactChannelContractTest {

    private static WebPageArtifact page(String content) {
        WebPageArtifact artifact = new WebPageArtifact();
        artifact.setTitle("Fixture Page");
        artifact.setUrl("https://example.com/fixture");
        artifact.setContent(content);
        return artifact;
    }

    @Test
    void everyReferenceVariantResolvesToTheSameRegistryEntry() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        ArtifactRegistry registry = context.getArtifactRegistry();
        WebPageArtifact artifact = page("short");
        String canonical = registry.register(artifact);
        assertTrue(canonical.startsWith("«") && canonical.endsWith("»"),
                "the canonical form carries guillemets: " + canonical);

        String bare = canonical.substring(1, canonical.length() - 1);
        String noPrefix = bare.substring("artifact:".length());
        assertSame(artifact, registry.get(canonical), "canonical form resolves");
        assertSame(artifact, registry.get(bare), "guillemet-less echo resolves to the same entry");
        assertSame(artifact, registry.get(" " + canonical + " "), "whitespace-wrapped echo resolves");
        assertSame(artifact, registry.get(noPrefix), "bare type~uuid echo resolves");
        assertSame(artifact, registry.get("«" + noPrefix + "»"), "guillemets without the artifact: prefix resolve");
    }

    @Test
    void registrationKeepsAnExistingRefAndMintsOnlyWhenMissing() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WebPageArtifact artifact = page("short");
        artifact.setArtifactRef("«artifact:link:page~fixed1»");

        assertEquals("«artifact:link:page~fixed1»", registry.register(artifact),
                "an artifact arriving with a ref keeps it - the ref is its identity across contexts");
        assertSame(artifact, registry.get("link:page~fixed1"));
    }

    @Test
    void aliasExtractionStripsWrappingAndUuid() {
        assertEquals("link:cite:pubmed", ArtifactRegistry.extractAliasFromRef("«artifact:link:cite:pubmed~x1y2z3»"),
                "the alias between the prefix and the ~uuid is the hierarchical type");
        assertNull(ArtifactRegistry.extractAliasFromRef("«artifact:no-tilde»"),
                "a ref without the ~uuid separator is malformed and yields no alias");
    }

    @Test
    void aReferencedArtifactPrintsAsItsRef() {
        ArtifactRegistry registry = new ArtifactRegistry();
        WebPageArtifact artifact = page("short");
        assertFalse(artifact.hasReference(), "fresh artifacts carry no ref until a registry mints one");

        String ref = registry.register(artifact);

        assertTrue(artifact.hasReference());
        assertEquals(ref, artifact.toString(),
                "a referenced artifact prints as its ref, so logs and prompts name it stably");
    }

    @Test
    void registryDumpRidesATrailingUserTurn_withHeaderAndSummaryPrefix() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        OutgoingMessage<String> userTurn = new OutgoingMessage<>(StringResponseHandler.instance);
        userTurn.setRole("user");
        userTurn.addText("please read the page");
        context.getMessages().add(userTurn);
        context.getArtifactRegistry().register(page("C".repeat(20_000)));

        PreparedConversation prepared = context.prepareMessagesForLLM(new GenericContentFormatter(), false);
        ProcessedMessageData last = prepared.turns().get(prepared.turns().size() - 1);
        assertEquals(TurnRole.USER, last.role(),
                "the registry is data derived from tool results - it must never be system content");
        String rendered = last.contentBlocks().stream()
                .map(b -> b instanceof ContentBlocks.TextBlock tb ? tb.text() : "")
                .reduce("", String::concat);
        assertTrue(rendered.contains("=== ARTIFACT REGISTRY ==="),
                "the documented section header opens the dump");
        assertTrue(rendered.contains("[SUMMARY:"),
                "a long summarizable field renders as a summary with the length prefix, not verbatim");
        assertFalse(rendered.contains("C".repeat(20_000)),
                "the 20K-char content itself must not flood the context");
        assertTrue(prepared.systemText() == null || !prepared.systemText().contains("=== ARTIFACT REGISTRY ==="),
                "and none of it leaks into the system channel");
    }
}
