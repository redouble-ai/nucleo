/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat.messages;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the CONNECTED frame's wire shape through a Jackson mapper, which every host codec
 * is built on: the payload's properties - the inherited status/message pair AND the added
 * conversation identity - all land at the TOP level of the JSON, because deployed clients
 * validate top-level {@code status}/{@code message} and would reject a nested shape. The
 * whole additive-compatibility story of {@link ConnectedStatus} hangs on Jackson
 * unwrapping subclass properties, so it is pinned with an assertion.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ConnectedStatusShapeTest {

    @Test
    void connectedFrameCarriesIdentityAtTopLevel() throws Exception {
        ConnectedStatus status = new ConnectedStatus("connected", "Chat connection established", "conv-42", 7L);
        WebSocketMessage<ConnectedStatus> frame = WebSocketMessage.create(WebSocketMessageType.CONNECTED, "wf-1", status);
        // findAndRegisterModules for the envelope's Instant; the unwrapping under test is core Jackson
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(frame));
        assertEquals("connected", json.get("type").asText(), "the frame type serializes as its lowercase wire value");
        assertEquals("wf-1", json.get("workflowId").asText());
        assertEquals("connected", json.get("status").asText(), "the inherited pair is unwrapped to the top level");
        assertEquals("Chat connection established", json.get("message").asText());
        assertEquals("conv-42", json.get("conversationId").asText(),
                "the subclass properties unwrap beside them - additive on the wire");
        assertEquals(7L, json.get("scopeId").asLong());
        assertNull(json.get("data"), "a POJO payload never nests; only a Map payload lands under data");
    }
}
