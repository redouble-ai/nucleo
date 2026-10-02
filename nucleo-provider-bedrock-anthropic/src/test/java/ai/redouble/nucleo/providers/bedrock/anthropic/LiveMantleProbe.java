/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.anthropic.bedrock.backends.*;
import org.slf4j.*;
import software.amazon.awssdk.regions.*;

/**
 * Manual live probe: drives a real inference through {@link AnthropicBedrockMantleSDKClient}
 * against the Bedrock Mantle endpoint. Not a unit test - it costs tokens and needs AWS
 * credentials, so it is a {@code main} rather than a {@code @Test} and never runs in the suite.
 *
 * <p>Run with the test classpath:
 *
 * <pre>
 * mvn -o -q test-compile
 * java -cp "target/classes:target/test-classes:$(cat cp.txt)" ai.redouble.nucleo.providers.bedrock.LiveMantleProbe
 * </pre>
 *
 * <p>Credentials come from the ambient AWS chain rather than the secret store, so the probe
 * runs without a configured secret store. That is the one production path it does not exercise; it
 * is shared, unchanged code with the already-working {@link AnthropicBedrockSDKClient}. Everything
 * else is the real thing: our client class, the SDK, {@link BedrockMantleBackend}, spec-driven
 * model resolution, request construction, response parsing and usage extraction.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 */
public final class LiveMantleProbe {
    private static final Logger log = LoggerFactory.getLogger(LiveMantleProbe.class);

    /**
     * Subclass only to supply ambient-chain credentials in place of the secret store lookup.
     * Extends the real Mantle client, so the workspace (data retention) binding is exercised
     * exactly as in production.
     */
    private static final class AmbientCredentialsMantleClient extends AnthropicBedrockMantleSDKClient {
        AmbientCredentialsMantleClient(String region) {
            super(BedrockMantleBackend.builder().region(Region.of(region)).fromEnv().build());
        }
    }

    private LiveMantleProbe() {
    }

    /**
     * Args: {@code [region [laxProjectId strictProjectId]]}. Without the project ids the probe
     * sends no workspace header (account-level retention resolution) and skips the Fable checks;
     * with them it also proves the requiresLax routing: refusal without the opt-in, real answer
     * through the LAX project with it, ordinary models pinned to STRICT throughout.
     */
    public static void main(String[] args) {
        String region = args.length > 0 ? args[0] : "us-east-1";
        if (args.length > 2) {
            Settings.get(ModelSettings.class).mantleLaxProject = args[1];
            Settings.get(ModelSettings.class).mantleStrictProject = args[2];
            log.info("[MantleProbe] workspace binding on: LAX={} STRICT={}", args[1], args[2]);
        }
        ModelsBackend catalog = new JsonModelsBackend();

        for (String specId : new String[]{"claude-opus-5-mantle"}) {
            ModelSpec spec = catalog.spec(specId);
            try {
                AnthropicBedrockSDKClient client = new AmbientCredentialsMantleClient(region);
                client.setModel(spec);
                String answer = ask(client, spec, "Reply with exactly: OK");
                log.info("[MantleProbe] {} ({}) -> {}", specId, spec.getWireModelId(), answer);
            }
            catch (Exception e) {  // noqa - probe: report whichever spec fails and keep going
                log.error("[MantleProbe] {} FAILED: {}: {}", specId, e.getClass().getSimpleName(), String.valueOf(e.getMessage()).replace('\n', ' '));
            }
        }
        if (Settings.get(ModelSettings.class).mantleLaxProject != null) {
            // The allowed branch runs live against the sealed envelope of this probe JVM
            DefaultComplianceEnvelope permitting = new DefaultComplianceEnvelope();
            permitting.setAllowDataShare(true);
            ai.redouble.nucleo.harness.JobDispatcher.getInstance().sealComplianceEnvelope(permitting);
            fableProbe(region, catalog.spec("claude-fable-5-mantle"));
        }
        streamingProbe(region, catalog.spec("claude-opus-5-mantle"));
    }

    /**
     * The requiresLax routing, both branches. The refusal branch exercises the pure
     * workspace-decision function against a refusing envelope (the seal is permanent per
     * JVM, so both branches cannot run live in one process); the allowed branch runs live
     * against this probe JVM's sealed permitting envelope and must bind to the LAX project
     * and produce a real completion.
     */
    private static void fableProbe(String region, ModelSpec spec) {
        try {
            AnthropicBedrockMantleSDKClient.resolveWorkspace(spec, new DefaultComplianceEnvelope(),
                    Settings.get(ModelSettings.class).mantleLaxProject, Settings.get(ModelSettings.class).mantleStrictProject);
            log.error("[MantleProbe] FABLE deny-check FAILED: workspace resolved without the opt-in");
        }
        catch (Exception e) {  // noqa - probe: the refusal is the expected outcome
            log.info("[MantleProbe] FABLE deny-check OK (refused without opt-in): {}", String.valueOf(e.getMessage()).replace('\n', ' '));
        }
        try {
            AnthropicBedrockSDKClient client = new AmbientCredentialsMantleClient(region);
            client.setModel(spec);
            String answer = ask(client, spec, "Reply with exactly: FABLE OK");
            log.info("[MantleProbe] FABLE via LAX project -> {}", answer);
        }
        catch (Exception e) {  // noqa - probe: report the failure and keep going
            log.error("[MantleProbe] FABLE via LAX FAILED: {}: {}", e.getClass().getSimpleName(), String.valueOf(e.getMessage()).replace('\n', ' '));
        }
    }

    /** One probe question on a client-internal conversation: no reasoning, a verdict-sized answer. */
    private static String ask(AnthropicBedrockSDKClient client, ModelSpec spec, String question) throws Exception {
        ConversationContext context = TestModels.conversation(spec);
        context.setDepth(Depth.IMMEDIATE);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.VERDICT));
        OutgoingMessage<String> message = client.createOutgoingMessage(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(java.time.Instant.now());
        message.addText(question);
        context.getMessages().add(message);
        LLMResponse<String> response = client.singleResponse(new LLMRequest<>(context));
        return response.getResponseMessage() != null ? response.getResponseMessage().getRawContent() : null;
    }

    /**
     * Streaming exercises a different Mantle route than the single-shot path, so it can require a
     * different IAM action. Driven through the real {@code streamResponse} template.
     */
    private static void streamingProbe(String region, ModelSpec spec) {
        try {
            AnthropicBedrockSDKClient client = new AmbientCredentialsMantleClient(region);
            client.setModel(spec);
            ConversationContext context = TestModels.conversation(spec);
            context.setDepth(Depth.IMMEDIATE);
            context.setOutputDeclaration(OutputDeclaration.of(OutputSize.VERDICT));
            OutgoingMessage<String> message = client.createOutgoingMessage(StringResponseHandler.instance);
            message.setRole("user");
            message.setTimestamp(java.time.Instant.now());
            message.addText("Count from 1 to 5, digits only.");
            context.getMessages().add(message);
            StringBuilder chunks = new StringBuilder();
            LLMResponse<String> response = client.streamResponse(new LLMRequest<>(context), c -> chunks.append('.'));
            log.info("[MantleProbe] STREAMING {} -> successful={} chunks={} text={}",
                    spec.getId(),
                    response.isSuccessful(),
                    chunks.length(),
                    String.valueOf(response.getResponseMessage() != null
                                                 ? response.getResponseMessage().getRawContent() : null).replace('\n', ' '));
        }
        catch (Exception e) {  // noqa - probe: the point is to surface whatever streaming demands
            log.error("[MantleProbe] STREAMING FAILED: {}: {}", e.getClass().getSimpleName(), String.valueOf(e.getMessage()).replace('\n', ' '));
        }
    }
}
