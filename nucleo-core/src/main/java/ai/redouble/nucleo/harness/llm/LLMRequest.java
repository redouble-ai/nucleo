/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.*;



/**
 * Encapsulates an LLM request tied to a {@link ConversationContext}.
 *
 * <p>{@code inputJson} and {@code outputJson} hold the provider-native wire payload of
 * the request and response - exactly what was sent to and received from the upstream
 * API. Each provider client populates them inside its {@code doSingleResponse} /
 * {@code doStreamResponse} from the SDK request/response objects it builds and receives,
 * so the shape is provider-specific (Anthropic's {@code MessageCreateParams} JSON,
 * OpenAI's chat-completions JSON, Bedrock's Converse representation). A deployment's
 * usage recorder persists these verbatim as an audit trail.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public class LLMRequest<T> {
    protected final ConversationContext context;
    protected Double temperature;

    /** Provider-native wire JSON of the request sent to the upstream API. Populated by the provider client. */
    protected String inputJson;
    /** Provider-native wire JSON of the response received from the upstream API. Populated by the provider client. */
    protected String outputJson;

    /**
     * Creates a request with a conversation context.
     * The conversation should already contain any messages to be sent.
     *
     * @param context the conversation context with messages
     */
    public LLMRequest(ConversationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context;
    }

    /**
     * Gets the conversation context.
     *
     * @return the conversation context
     */
    public ConversationContext getContext() {
        return context;
    }


    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getInputJson() {
        return inputJson;
    }
    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }
    public String getOutputJson() {
        return outputJson;
    }
    public void setOutputJson(String outputJson) {
        this.outputJson = outputJson;
    }

}