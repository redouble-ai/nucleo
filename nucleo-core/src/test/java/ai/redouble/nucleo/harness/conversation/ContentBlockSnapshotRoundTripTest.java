/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the persistence shape of every content block: {@link ContentBlockSnapshot}
 * round-trips each sealed permit losslessly, and an unknown stored type is refused
 * rather than guessed at. The thinking blocks matter most: Anthropic requires the
 * signature (and a redacted block's data) echoed back VERBATIM when the block appears
 * in a later turn's history, or the request is refused with a 400 - so a snapshot
 * that loses either makes the stored conversation unanswerable.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class ContentBlockSnapshotRoundTripTest {

    private static ContentBlock roundTrip(ContentBlock block) {
        return ContentBlockSnapshot.fromBlock(block).toBlock();
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

        ContentBlockSnapshot renamed = ContentBlockSnapshot.fromBlock(new PojoBlock(payload));
        renamed.setPojoClass("ai.redouble.no.such.Payload");
        ContentBlock degraded = renamed.toBlock();
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
        assertThrows(IllegalStateException.class, snapshot::toBlock,
                "an unrecognized stored type is a corrupted snapshot, never silently skipped");
    }
}
