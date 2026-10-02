/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.anthropic.bedrock.backends.*;
import com.anthropic.models.messages.*;

/**
 * Anthropic Claude via the AWS Bedrock <b>Mantle</b> endpoint
 * ({@code bedrock-mantle.{region}.api.aws/anthropic/v1/messages}).
 *
 * <p>Mantle serves the native Anthropic Messages API, where the legacy path this class extends
 * ({@link AnthropicBedrockSDKClient}, on {@code bedrock-runtime}) reaches the same models through
 * AWS InvokeModel. Both authenticate with the same SigV4 credentials and the same region, which is
 * why only the backend differs; throttle classification, model resolution and rate-limit header
 * extraction are inherited unchanged.
 *
 * <p>What actually differs, and why a separate spec family exists rather than a swapped backend:
 *
 * <ul>
 *   <li><b>Model ids are bare.</b> {@code anthropic.claude-opus-5}, not the
 *       {@code us.anthropic.} / {@code global.anthropic.} inference profile the legacy path needs.
 *       Each surface rejects the other's form outright, so the id belongs on the spec.</li>
 *   <li><b>The catalog is narrower.</b> Only the newer Claude releases are served here; models such
 *       as Sonnet 4.6 and Opus 4.6 answer on {@code bedrock-runtime} and return "does not exist" on
 *       Mantle.</li>
 *   <li><b>Quotas are a separate pool</b>, published per direction (input TPM and output TPM) rather
 *       than as one combined figure.</li>
 *   <li><b>Data retention is a separate account-level store</b> from the control-plane one that
 *       governs {@code bedrock-runtime}. The two are set independently and can hold different
 *       values; both must be configured.</li>
 * </ul>
 *
 * <p>The Anthropic prompt cache is shared between the two surfaces: a prefix written through one is
 * read by the other, so running both in parallel costs no extra cache writes and a cutover does not
 * start cold.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 * @see AnthropicBedrockSDKClient
 */
public class AnthropicBedrockMantleSDKClient extends AnthropicBedrockSDKClient {

    public AnthropicBedrockMantleSDKClient() {
        super(BedrockMantleBackend.builder()
                .awsCredentialsProvider(awsCredentialsProvider())
                .region(bedrockRegion())
                .build());
    }

    /**
     * For harnesses that supply their own {@link BedrockMantleBackend} (e.g. ambient-chain
     * credentials instead of the secret store). Everything above the backend - including the
     * workspace binding - is inherited unchanged, which is the point: a probe through this
     * constructor exercises the same retention routing as production.
     */
    protected AnthropicBedrockMantleSDKClient(BedrockMantleBackend backend) {
        super(backend);
    }

    @Override
    public APIDialect getDialect() {
        return APIDialect.BEDROCK_MANTLE;
    }

    /**
     * Binds every Mantle request to a Bedrock project (workspace) via the
     * {@code anthropic-workspace-id} header, which decides the request's data retention mode
     * (resolution is project, then account, then model default - first non-inherit wins).
     *
     * <p>A {@code requiresLax} model routes to {@link ModelSettings#mantleLaxProject}, and only when the
     * application's sealed {@link ComplianceEnvelope} permits it; anything else routes to
     * {@link ModelSettings#mantleStrictProject}, pinning ordinary traffic to zero retention independently
     * of the account setting. A null project id sends no header and defers to the account.
     * This is the LAST LINE: the harness's resolution gate never resolves a non-compliant
     * spec (an unpinned seat is served from the best rung the envelope permits, a
     * non-compliant pin is refused), but nothing leaves the process without passing the
     * envelope here again.
     */
    @Override
    protected MessageCreateParams buildMessageCreateParams(ConversationContext context, PreparedConversation prepared) {
        MessageCreateParams params = super.buildMessageCreateParams(context, prepared);
        String workspace = resolveWorkspace(this.model, JobDispatcher.getInstance().getComplianceEnvelope(),
                Settings.get(ModelSettings.class).mantleLaxProject, Settings.get(ModelSettings.class).mantleStrictProject);
        if (workspace == null) {
            return params;
        }
        return params.toBuilder().putAdditionalHeader("anthropic-workspace-id", workspace).build();
    }

    /** The workspace decision as a pure function of its inputs, so both refusal branches unit-test without the sealed dispatcher. */
    public static String resolveWorkspace(ModelSpec model, ComplianceEnvelope envelope, String laxProject, String strictProject) {
        if (model != null && !envelope.permits(model)) {
            throw new UncorrectableRuntimeLLMException("Trying to use " + model.getId()
                    + ", but the compliance envelope " + envelope.getClass().getSimpleName()
                    + " refuses it. Declare a permitting envelope in the application's servlet to"
                    + " opt in, or use a model the envelope permits.");
        }
        if (model != null && model.requiresLax()) {
            if (laxProject == null) {
                throw new UncorrectableRuntimeLLMException("Model " + model.getId()
                        + " requires provider data sharing (requiresLax), but this deployment has no"
                        + " ModelSettings.mantleLaxProject configured. Provision a Bedrock Mantle project"
                        + " with data retention mode provider_data_share and set its id in the deployment's"
                        + " NucleoConfigurator.");
            }
            return laxProject;
        }
        return strictProject;
    }
}
