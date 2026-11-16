/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;

import java.io.*;
import java.util.*;

/**
 * The typed contract of one outgoing message: the format the model is asked to reply in
 * ({@link #formatName}, {@link #formatDefinition}, {@link #replyInstructions}), and how its
 * reply parses back into {@code T} ({@link #parse}). An {@link OutgoingMessage} carries one; the matching
 * {@link IncomingMessage} uses the same handler to produce the typed response. Handlers
 * are stateless after construction and transient through persistence - a restored message
 * is dehydrated until {@code rehydrate} hands it a handler again.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public interface ResponseHandler<T> extends Serializable {

    Class<T> getResponseClass();

    T parse(String rawContent) throws IOException, LLMReadableCheckedException;

    default T parse(List<ContentBlock> contentBlocks) throws IOException, LLMReadableCheckedException {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.text());
            }
        }
        return parse(text.toString());
    }

    String write(T response);

    List<String> getValidationErrors(T response);

    /**
     * The parsed reply with every artifact in it taken from the conversation's registry. A
     * model never authors an artifact: where its reply holds one, it chose a reference, and
     * the artifact the caller receives is the one the registry holds under it
     * ({@link ArtifactRegistry#held}). An artifact the registry cannot supply is described
     * in {@code violations}, which the caller treats as it treats a missing required field.
     * A handler whose reply carries objects that are answered one by one, as a tool call's
     * input is, overrides this to report each where its own answer goes.
     *
     * @param parsed     the reply as parsed
     * @param registry   the registry of the conversation the reply belongs to
     * @param violations receives one line per artifact the registry could not supply
     * @return the reply to use in place of {@code parsed}
     */
    default T heldArtifacts(T parsed, ArtifactRegistry registry, List<String> violations) {
        return registry.held(parsed, violations);
    }

    PojoDefinition writeDefinition();

    /**
     * The name the reply format is known by in a conversation, or null when the reply has no
     * format to define: plain text. The name is the format's identity. The conversation
     * defines it once in the system content, under this name, and every turn that asks for a
     * reply names it ({@link #replyInstructions}); a handler with a name describes its reply
     * in the framework's {@code @}-notation, which is what brings the schema-notation legend
     * into the request. The name is built from classes ({@link #formatNameOf}), so it can be
     * found in the code. No default: every handler decides.
     *
     * @throws IllegalStateException when a class the name is built from has no simple name
     *         that is an identifier, as an anonymous class or an array type has none
     */
    String formatName();

    /**
     * The definition of the reply format {@link #formatName} names, written for a model with
     * the given requirements: what to reply with, then the schema of the reply, whose root
     * {@code @type} is the format name. Asked only of a handler that has a name. The text is
     * the same for the same name and requirements, and a conversation relies on that: it
     * writes a definition once per name and requirements and reuses it.
     *
     * @param requirements what the model about to answer requires of a reply
     */
    String formatDefinition(ResponseRequirements requirements);

    /**
     * The text rendered after every turn this handler is asked to answer. For a named format
     * it is one line that names it ({@link #NAMED_REPLY_INSTRUCTIONS}); the definition is in
     * the system content and is not repeated. The text is the same for the same requirements.
     *
     * @param requirements what the model about to answer requires of a reply
     */
    String replyInstructions(ResponseRequirements requirements);

    /**
     * The line after a turn that asks for a reply in a named format. It names JSON because a
     * provider's JSON mode refuses a request whose messages do not. A handler whose reply may
     * also be a tool call says so in a line of its own.
     */
    String NAMED_REPLY_INSTRUCTIONS = "\n\nReply in the JSON format %s, defined above.";

    /**
     * The part of a format name a class contributes: its simple name.
     *
     * @throws IllegalStateException when the simple name is not an identifier
     */
    static String formatNameOf(Class<?> type) {
        String name = type.getSimpleName();
        boolean identifier = !name.isEmpty() && Character.isJavaIdentifierStart(name.charAt(0));
        for (int i = 1; identifier && i < name.length(); i++) {
            identifier = Character.isJavaIdentifierPart(name.charAt(i));
        }
        if (!identifier) {
            throw new IllegalStateException("A reply format is named after its class, and " + type.getName()
                    + " has no simple name that is an identifier; make it a named class");
        }
        return name;
    }

}
