/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;

import java.io.*;
import java.util.*;

/**
 * The typed contract of one outgoing message: how the model is told what shape to answer
 * in ({@link #responseInstructions}), and how its reply parses back into {@code T}
 * ({@link #parse}). An {@link OutgoingMessage} carries one; the matching
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

    PojoDefinition writeDefinition();

    /**
     * Whether this handler's rendered contract describes the response structure in the
     * framework's {@code @}-notation. The conversation reads this to decide whether the
     * schema-notation legend must accompany the request - the legend follows the
     * contract, the notation's only producer, not the conversation. A handler whose
     * instructions are plain prose answers false. No default: every handler decides.
     */
    boolean usesSchemaNotation();

    String responseInstructions();

    /**
     * Returns response instructions adapted for the call's capabilities.
     * When nativeToolsAvailable is true, the handler may exclude tool-calling fields
     * from the response schema since the model will use native tool_use blocks instead.
     * When thinkingActive is true, the handler may exclude the prose reasoning field
     * since a native thinking block will carry the reasoning instead.
     *
     * @param nativeToolsAvailable true if the LLM client supports native tool calling
     * @param thinkingActive       true if a native thinking block is guaranteed for this call
     * @return response instructions string
     */
    default String responseInstructions(boolean nativeToolsAvailable, boolean thinkingActive) {
        return responseInstructions();
    }

}
