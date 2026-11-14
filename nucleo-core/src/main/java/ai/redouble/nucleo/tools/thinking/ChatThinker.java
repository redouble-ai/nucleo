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
 * serves it from MEGA downward, so the same chat class runs at MEGA where the deployment
 * can call one and at XL where it cannot. A chat with a
 * genuinely narrower job declares its rung instead, the same way.
 *
 * <p><b>Note:</b> Subclasses must implement {@link #declareDefaultTools()} to specify tools.
 *
 * @param <A> the class of the answer each exchange ends with
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public abstract class ChatThinker<A extends ChatAnswer> extends ReactiveThinker<A> {
    private static final Logger log = LoggerFactory.getLogger(ChatThinker.class);

    /**
     * Creates a ChatThinker.
     * This is the REQUIRED constructor for all tools.
     *
     * @param parent the parent identity for lineage tracking
     * @param answerClass the class of the answer each exchange ends with: {@link ChatAnswer},
     *        or a subclass of it when the application's answer has fields of its own
     */
    public ChatThinker(Identifiable parent, ThinkerDeclaration declaration, Class<A> answerClass) {
        super(parent, declaration, answerClass);
    }

    /**
     * Chat bound for life to tools that change nothing - a surface that can answer
     * about a corpus but never act on it. Mirrors
     * {@link ReactiveThinker#ReactiveThinker(Identifiable, ThinkerDeclaration, Class, boolean)}; see
     * {@link AbstractThinker#AbstractThinker(Identifiable, ThinkerDeclaration, boolean)} for what the
     * binding guarantees and how it travels to whatever this chat delegates to.
     *
     * @param parent        the parent identity for lineage tracking
     * @param answerClass   the class of the answer each exchange ends with
     * @param forceReadOnly true to bind this chat to tools that change nothing
     */
    protected ChatThinker(Identifiable parent, ThinkerDeclaration declaration, Class<A> answerClass, boolean forceReadOnly) {
        super(parent, declaration, answerClass, forceReadOnly);
    }

    @Override
    protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        // Chat conversations are not temporary
        conversation.setTemporary(false);
        // Chat conversations ARE compactable at conversation level.
        // Individual user messages are marked non-compactable, and each keeps the answer it
        // replies to, to preserve the conversation flow while allowing tool results to be compacted.
    }

    /**
     * Publishes the answer as one {@link ContentStreamEvent}: its text as the done chunk, the
     * answer object, and the artifacts it selected keyed by reference.
     */
    @Override
    protected void streamResponse(A answer, JobContext<VoidThinkerOutput> context) {
        String text = answer.getMessageText();
        log.info("Streaming chat response: {}", (text.length() > 100 ? text.substring(0, 100) + "..." : text));
        StreamChunk chunk = StreamChunk.done(text);
        ContentStreamEvent event = new ContentStreamEvent(context.getSnapshot(), chunk, answer, answer.artifactsByRef());
        context.publish(event);
    }

}