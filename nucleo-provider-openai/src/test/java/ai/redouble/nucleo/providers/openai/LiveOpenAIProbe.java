/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.slf4j.*;

/**
 * Manual live probe: drives a real inference through {@link OpenAISDKClient} against the
 * OpenAI API. Not a unit test - it costs tokens and needs a key, so it is a {@code main}
 * rather than a {@code @Test} and never runs in the suite. Companion to
 * the Anthropic artifact's {@code LiveMantleProbe}: together they put every client family on a live wire after a
 * request-construction change, since the shape tests prove only that our builders agree
 * with each other, not that a provider accepts the result.
 *
 * <p>The conversation exercises the channel contract end to end: a main objective
 * (system content, expressed on this provider as a leading role:system message), an
 * assistant turn and a user turn. The objective instructs a reply that proves the model
 * actually read its system content.
 *
 * <p>Run with the test classpath:
 * <pre>
 * mvn -o -q test-compile
 * OPENAI_API_KEY=... java -cp "target/classes:target/test-classes:$(cat cp.txt)" ai.redouble.nucleo.providers.openai.LiveOpenAIProbe
 * </pre>
 *
 * <p>The key comes from the {@code OPENAI_API_KEY} environment variable via
 * {@link OpenAISDKClient#setApiKey}, so the probe runs without a configured secret store;
 * without the variable the client falls back to the configured secret store.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public final class LiveOpenAIProbe {
    private static final Logger log = LoggerFactory.getLogger(LiveOpenAIProbe.class);

    private LiveOpenAIProbe() {
    }

    public static void main(String[] args) {
        ModelsBackend catalog = new JsonModelsBackend();
        ModelSpec spec = catalog.spec(args.length > 0 ? args[0] : "gpt-5-mini");
        OpenAISDKClient client = new OpenAISDKClient(WireApi.RESPONSES);
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.isEmpty()) {
            client.setApiKey(envKey);
        }
        client.setModel(spec);
        ConversationContext conversation = TestModels.conversation(spec);
        conversation.putMainObjective("task", "You are a probe target. Whatever the user says, reply with exactly:"
                + " SYSTEM-CHANNEL-OK");
        OutgoingMessage<String> earlier = new OutgoingMessage<>(StringResponseHandler.instance);
        earlier.setRole("assistant");
        earlier.addText("SYSTEM-CHANNEL-OK");
        conversation.getMessages().add(earlier);
        OutgoingMessage<String> ask = new OutgoingMessage<>(StringResponseHandler.instance);
        ask.setRole("user");
        ask.addText("What is the capital of France?");
        ask.setRequestedOutputTokens(200);
        conversation.getMessages().add(ask);
        try {
            LLMResponse<String> response = client.singleResponse(new LLMRequest<>(conversation));
            String answer = response.getResponseMessage().getRawContent();
            log.info("[OpenAIProbe] {} ({}) -> {}", spec.getId(), spec.getWireModelId(), answer);
            if (answer != null && answer.contains("SYSTEM-CHANNEL-OK")) {
                log.info("[OpenAIProbe] PASS: the model obeyed the objective, so the system channel carried it and the provider accepted the request shape");
            }
            else {
                log.error("[OpenAIProbe] SUSPECT: request accepted but the reply ignores the objective - check whether the system message actually reached the wire");
            }
        }
        catch (Exception e) {  // noqa - probe: the failure IS the result being collected
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ")
                     .append(String.valueOf(t.getMessage()).replace('\n', ' ')).append(" <- ");
            }
            log.error("[OpenAIProbe] FAILED: {}", chain);
        }
    }
}
