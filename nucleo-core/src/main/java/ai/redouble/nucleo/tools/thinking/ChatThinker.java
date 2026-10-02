/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.slf4j.*;

import java.util.*;

/**
 * Chat interaction thinker.
 *
 * <p>Processes chat messages through the LLM with tool support,
 * streaming responses back to the user.
 *
 * <p><b>Declaration.</b> A chat declares itself like every thinker, and a chat that
 * wants the best model the deployment serves declares {@link Grade#CEILING}: the picker
 * gate turns it into the deployment's strongest rung, so the same chat class runs at
 * MEGA where the deployment pins one and at XL where it does not. A chat with a
 * genuinely narrower job declares its rung instead, the same way.
 *
 * <p><b>Note:</b> Subclasses must implement {@link #declareDefaultTools()} to specify tools.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public abstract class ChatThinker extends ReactiveThinker {
    private static final Logger log = LoggerFactory.getLogger(ChatThinker.class);

    /**
     * Creates a ChatThinker.
     * This is the REQUIRED constructor for all tools.
     *
     * @param parent the parent identity for lineage tracking
     */
    public ChatThinker(Identifiable parent, ThinkerDeclaration declaration) {
        super(parent, declaration);
    }

    /**
     * Chat bound for life to tools that change nothing - a surface that can answer
     * about a corpus but never act on it. Mirrors
     * {@link ReactiveThinker#ReactiveThinker(Identifiable, ThinkerDeclaration, boolean)}; see
     * {@link AbstractThinker#AbstractThinker(Identifiable, ThinkerDeclaration, boolean)} for what the
     * binding guarantees and how it travels to whatever this chat delegates to.
     *
     * @param parent        the parent identity for lineage tracking
     * @param forceReadOnly true to bind this chat to tools that change nothing
     */
    protected ChatThinker(Identifiable parent, ThinkerDeclaration declaration, boolean forceReadOnly) {
        super(parent, declaration, forceReadOnly);
    }

    @Override
    protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        // Chat conversations are not temporary
        conversation.setTemporary(false);
        // Chat conversations ARE compactable at conversation level.
        // Individual user messages and final answers are marked non-compactable
        // to preserve the conversation flow while allowing tool results to be compacted.
    }

    @Override
    protected void streamResponse(String response, Map<String, Artifact> artifacts, JobContext<VoidThinkerOutput> context) {
        log.info("Streaming chat response: {}", (response.length() > 100 ? response.substring(0, 100) + "..." : response));
        StreamChunk chunk = StreamChunk.done(response);
        ContentStreamEvent event = new ContentStreamEvent(context.getSnapshot(), chunk, artifacts);
        context.publish(event);
    }

}