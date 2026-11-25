/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the persistence shape of every content block: {@link ContentBlockSnapshot}
 * round-trips each sealed permit losslessly, and an unknown stored type is refused
 * rather than guessed at. The thinking blocks matter most: Anthropic requires the
 * signature (and a redacted block's data) echoed back VERBATIM when the block appears
 * in a later turn's history, or the request is refused with a 400 - so a snapshot
 * that loses either makes the stored conversation unanswerable. An assistant message's own
 * output count, the one per-message actual, rides {@link MessageSnapshot} with the message,
 * and stays null when the provider reported none.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class ContentBlockSnapshotRoundTripTest {

    private static ContentBlock roundTrip(ContentBlock block) {
        ArtifactRegistry registry = new ArtifactRegistry();
        return ContentBlockSnapshot.fromBlock(block, registry).toBlock(registry);
    }

    private static Message roundTrip(Message message) {
        ArtifactRegistry registry = new ArtifactRegistry();
        return MessageSnapshot.fromMessage(message, registry).toMessage(StringResponseHandler.instance, null, registry);
    }

    @TypeAlias("test:snapshot:doc")
    public static class Doc extends AbstractArtifact {
        @LLMSummarizable(value = "a document", threshold = 10, llmSafe = false)
        private String body;

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    public static class Brief {
        private Doc doc;
        @LLMContextIgnore
        private String internalNote;

        public Doc getDoc() { return doc; }
        public void setDoc(Doc doc) { this.doc = doc; }
        public String getInternalNote() { return internalNote; }
        public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
    }

    @Test
    void aPromptObjectIsStoredWithItsArtifactsAsReferencesAndTakesThemBackFromTheRegistry() {
        Doc doc = new Doc();
        doc.setBody("the full body of a document, far past the threshold");
        Brief brief = new Brief();
        brief.setDoc(doc);
        brief.setInternalNote("never shown to a model");
        ArtifactRegistry registry = new ArtifactRegistry();
        ContentBlockSnapshot stored = ContentBlockSnapshot.fromBlock(new PojoBlock(brief), registry);
        assertSame(doc, registry.get(doc.getArtifactRef()), "storing the object registers the artifact it holds");
        assertFalse(stored.getPojoJson().contains("the full body"), "the artifact's content is stored in the registry, never in the block: " + stored.getPojoJson());
        assertFalse(stored.getPojoJson().contains("[SUMMARY"), "and no summary is stored in its place");
        assertTrue(stored.getPojoJson().contains(doc.getArtifactRef()), "the block holds the reference");
        Brief restored = (Brief) ((PojoBlock) stored.toBlock(registry)).pojo();
        assertSame(doc, restored.getDoc(), "the restored object holds the registry's own artifact, with its full value");
        assertEquals("never shown to a model", restored.getInternalNote(), "a field hidden from a model is stored all the same");
        ContentBlock orphan = stored.toBlock(new ArtifactRegistry());
        assertInstanceOf(JsonBlock.class, orphan, "an object whose artifact the registry does not hold is restored as its stored JSON");
    }

    @Test
    void anAssistantMessagesOwnOutputCountSurvivesTheSnapshot() {
        IncomingMessage<String> answer = new IncomingMessage<>(StringResponseHandler.instance);
        answer.overwriteRawContent("the answer");
        answer.setActualOutputTokens(42);
        Message restored = roundTrip(answer);
        assertEquals(42, ((IncomingMessage<?>)restored).getActualOutputTokens(),
                "the one per-message actual, what the answer took to generate, is kept with the message");
        IncomingMessage<String> unstamped = new IncomingMessage<>(StringResponseHandler.instance);
        unstamped.overwriteRawContent("no usage came back");
        assertNull(((IncomingMessage<?>)roundTrip(unstamped)).getActualOutputTokens(),
                "and an answer the provider never accounted stays unaccounted, never zero");
    }

    @Test
    void textJsonAndToolDefinitionRoundTripLosslessly() {
        assertEquals(new TextBlock("plain words"), roundTrip(new TextBlock("plain words")));
        assertEquals(new JsonBlock("{\"a\":1}"), roundTrip(new JsonBlock("{\"a\":1}")));
        assertEquals(new ToolDefinitionBlock("probe", "finds things", "{\"type\":\"object\"}"),
                roundTrip(new ToolDefinitionBlock("probe", "finds things", "{\"type\":\"object\"}")));
    }

    @Test
    void imageAndFileKeepPayloadAndMetadata() {
        assertEquals(new ImageBlock("aWRhdGE=", "image/png", "a probe"),
                roundTrip(new ImageBlock("aWRhdGE=", "image/png", "a probe")));
        assertEquals(new FileBlock("ZmRhdGE=", "application/pdf", "doc.pdf"),
                roundTrip(new FileBlock("ZmRhdGE=", "application/pdf", "doc.pdf")));
    }

    @Test
    void toolUseAndToolResultKeepTheLinkingId() {
        assertEquals(new ToolUseBlock("toolu_01", "search", "{\"q\":\"ports\"}"),
                roundTrip(new ToolUseBlock("toolu_01", "search", "{\"q\":\"ports\"}")),
                "the id is what the following tool_result references");
        assertEquals(new ToolUseBlock("search#1", "search", "{\"q\":\"ports\"}", true),
                roundTrip(new ToolUseBlock("search#1", "search", "{\"q\":\"ports\"}", true)),
                "a call parsed out of the turn's text stays marked as such, so a restored conversation does not send it twice");
        assertEquals(new ToolResultBlock("toolu_01", "{\"hits\":3}", true),
                roundTrip(new ToolResultBlock("toolu_01", "{\"hits\":3}", true)),
                "result payload and the error flag survive");
    }

    @Test
    void thinkingBlocksKeepTheirOpaqueRoundTripPayloads() {
        assertEquals(new ThinkingBlock("the model's reasoning", "sig-opaque-token"),
                roundTrip(new ThinkingBlock("the model's reasoning", "sig-opaque-token")),
                "the signature must replay verbatim or the provider 400s the next turn");
        assertEquals(new RedactedThinkingBlock("opaque-redacted-payload"),
                roundTrip(new RedactedThinkingBlock("opaque-redacted-payload")));
    }

    @Test
    void pojoRestoresTyped_orDegradesToItsJsonWhenTheClassIsGone() {
        Payload payload = new Payload();
        payload.setLabel("port 5");
        ContentBlock restored = roundTrip(new PojoBlock(payload));
        assertInstanceOf(PojoBlock.class, restored, "a resolvable class comes back as the typed pojo");
        assertEquals("port 5", ((Payload) ((PojoBlock) restored).pojo()).getLabel());

        ContentBlockSnapshot renamed = ContentBlockSnapshot.fromBlock(new PojoBlock(payload), new ArtifactRegistry());
        renamed.setPojoClass("ai.redouble.no.such.Payload");
        ContentBlock degraded = renamed.toBlock(new ArtifactRegistry());
        assertInstanceOf(JsonBlock.class, degraded,
                "a class the runtime no longer has degrades to its stored JSON instead of failing the whole restore");
        assertTrue(((JsonBlock) degraded).json().contains("port 5"), "the data itself is not lost");
    }

    public static class Payload {
        private String label;
        public String getLabel() {
            return label;
        }
        public void setLabel(String label) {
            this.label = label;
        }
    }

    @Test
    void unknownStoredTypeIsRefused() {
        ContentBlockSnapshot snapshot = new ContentBlockSnapshot();
        snapshot.setType("hologram");
        assertThrows(IllegalStateException.class, () -> snapshot.toBlock(new ArtifactRegistry()),
                "an unrecognized stored type is a corrupted snapshot, never silently skipped");
    }
}
