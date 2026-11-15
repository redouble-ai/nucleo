/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.*;

import java.io.*;
import java.util.*;

/**
 * What a chat answers a person's message with.
 *
 * <p>The answer is an object like every other thinker's, so the model selects the
 * artifacts that leave the chat with it: the inherited {@code artifact_refs}, and any
 * reference it writes in a field of the answer. {@code message_text} and
 * {@code artifact_refs} are the two fields every chat answer has, and a front end can rely
 * on both.
 *
 * <p>An application whose chat returns more than text extends this class with fields of
 * its own and names the subclass to its {@link ReactiveThinker}. A front end that knows
 * only the two fields above ignores the others.
 *
 * <p>The answer's fields are written in snake case by any Jackson mapper, the framework's
 * own or a host's, so the names a front end reads are the names the model wrote.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
@LLMDescription("The reply to the person. artifact_refs names the artifacts that support the reply; they are shown to the person with it, and no other artifact is.")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ChatAnswer extends ThinkerOutput<SimpleReasoning> {
    @LLMRequired
    @LLMDescription("The reply shown to the person, as markdown")
    private String messageText;

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    /**
     * Reads the answer a chat gave out of the assistant message a conversation stored for it,
     * with the artifacts it selected taken from the conversation's registry, so that a replay
     * of the conversation shows what the live exchange showed.
     *
     * <p>A message that holds no final answer (a turn that called tools, or one with no
     * reply object in it) reads as null. An answer stored as a plain string
     * reads as an answer with that string as its {@code message_text} and no artifacts.
     *
     * @param rawContent the stored assistant message, as the model wrote it
     * @param answerClass the chat's answer class
     * @param registry the registry of the stored conversation
     * @return the answer with its selected artifacts resolved, or null when the message is not an answer
     * @throws IOException when the message's reply object cannot be read, or an artifact it
     *         holds in a typed field is not in the registry
     */
    public static <A extends ChatAnswer> A stored(String rawContent, Class<A> answerClass, ArtifactRegistry registry) throws IOException {
        String json = NucleoJsonSerializer.extractJsonWithProse(rawContent).json();
        if (json.isEmpty()) {
            return null;
        }
        JsonNode reply = NucleoJsonSerializer.readTree(json);
        JsonNode written = reply.get("answer");
        if (!reply.path("final_answer").asBoolean(false) || written == null || written.isNull()) {
            return null;
        }
        A answer;
        if (written.isTextual()) {
            answer = NucleoJsonSerializer.convert(Map.of("message_text", written.asText()), answerClass);
        }
        else {
            // The same two gates the live reply passed: an artifact written in a typed field is
            // replaced by the registry's object, and the declared references and those written
            // in the reply's values, read from the reply as it was stored, become one
            // canonical list
            List<String> violations = new ArrayList<>();
            answer = registry.held(NucleoJsonSerializer.convert(written, answerClass), violations);
            if (!violations.isEmpty()) {
                throw new IOException("The stored answer refers to an artifact its conversation does not hold: " + String.join("; ", violations));
            }
            answer.canonicalizeArtifactRefs(ArtifactRegistry.refsWrittenIn(written));
            answer.resolveArtifacts(registry);
        }
        return answer;
    }
}
