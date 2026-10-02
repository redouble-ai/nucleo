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
 * Manual live probe: drives a real inference through {@link AzureFoundryOpenAIClient} against
 * the Responses surface of an Azure AI Foundry resource. Not a unit test - it costs tokens and needs a resource, so it is
 * a {@code main} rather than a {@code @Test} and never runs in the suite. The Azure sibling of
 * {@link LiveOpenAIProbe}.
 *
 * <p>This is the probe worth running FIRST on a new Foundry resource, because it is the only
 * cheap way to learn three things the catalog cannot tell you: whether the resource answers on
 * the unified {@code /openai/v1} surface at all, whether {@code api-key} is the credential it
 * wants, and whether the catalog's {@code wire_model_id} is the string this resource actually
 * serves. A wrong answer to any of those is an HTTP error here, in seconds, instead of a
 * failing seat in a running app.
 *
 * <p>Runs with no database, no Tomcat and no job dispatcher: it builds the client directly and
 * never touches the harness. The resource comes from the configured secret store under
 * {@link AzureFoundryOpenAIClient#SECRET_ID}; with the default environment store that is the
 * {@code AZURE_FOUNDRY_API_KEY} variable for the key and {@code AZURE_FOUNDRY_API_KEY_HOST}
 * for the resource hostname. The catalog entry comes from the file {@code nucleo.models}
 * names, since the shipped fragment carries no Foundry entries - they are the deployment's.
 *
 * <pre>
 * mvn -o -q test-compile
 * mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt -q
 * AZURE_FOUNDRY_API_KEY=... AZURE_FOUNDRY_API_KEY_HOST=my-resource.services.ai.azure.com \
 *   java -Dnucleo.models=/path/to/models.json \
 *        -cp "target/classes:target/test-classes:$(cat cp.txt)" \
 *        ai.redouble.nucleo.providers.openai.LiveAzureFoundryProbe [specId]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public final class LiveAzureFoundryProbe {
    private static final Logger log = LoggerFactory.getLogger(LiveAzureFoundryProbe.class);

    private LiveAzureFoundryProbe() {
    }

    public static void main(String[] args) {
        ModelsBackend catalog = new JsonModelsBackend();
        String specId = args.length > 0 ? args[0] : "gpt-5.6-sol-azure";
        ModelSpec spec = catalog.spec(specId);
        if (spec == null) {
            log.error("[AzureProbe] no catalog entry '{}' - point -Dnucleo.models at a catalog that carries it", specId);
            return;
        }
        log.info("[AzureProbe] {} | wire={} | provider={} | thinking={} | vision={} | ctx={}", spec.getId(),
                spec.getWireModelId(), spec.getProviderKey(), spec.getThinkingMode(), spec.supportsVision(), spec.getMaxContextTokens());
        AzureFoundryOpenAIClient client = new AzureFoundryOpenAIClient();
        client.setModel(spec);
        ConversationContext conversation = TestModels.conversation(spec);
        // A reasoning-effort model maps the depth to its reasoning_effort word, so the conversation
        // must declare one; the pre-resolved binding carries none.
        conversation.setDepth(Depth.QUICK);
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
            log.info("[AzureProbe] {} ({}) -> {}", spec.getId(), spec.getWireModelId(), answer);
            if (answer != null && answer.contains("SYSTEM-CHANNEL-OK")) {
                log.info("[AzureProbe] PASS: the resource accepted the unified request shape, the api-key header"
                        + " authenticated, and the model obeyed the objective - so the system channel carried it."
                        + " The catalog's wire_model_id names a model this resource serves.");
            }
            else {
                log.error("[AzureProbe] SUSPECT: request accepted but the reply ignores the objective -"
                        + " check whether the system message actually reached the wire");
            }
        }
        catch (Exception e) {  // noqa - probe: the failure IS the result being collected
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ")
                     .append(String.valueOf(t.getMessage()).replace('\n', ' ')).append(" <- ");
            }
            log.error("[AzureProbe] FAILED: {}", chain);
        }
    }
}
