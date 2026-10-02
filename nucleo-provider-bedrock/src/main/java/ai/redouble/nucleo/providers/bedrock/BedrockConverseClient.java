/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.skill.*;
import org.slf4j.*;
import software.amazon.awssdk.awscore.*;
import software.amazon.awssdk.services.bedrockruntime.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

/**
 * AWS Bedrock client using the Converse API.
 * Supports any Bedrock model (Nova, Llama, Mistral, etc.) through a unified interface.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-02)
 */
public class BedrockConverseClient extends AbstractLLMClient<ContentBlock> {
    private static final Logger log = LoggerFactory.getLogger(BedrockConverseClient.class);
    protected String bedrockModelId;
    private BedrockRuntimeClient bedrockClient;

    /**
     * The AWS transport, built on first use rather than at construction. Reading credentials
     * is what a request needs, not what an instance needs, so deferring it lets the request
     * builder be exercised without any. Overridable to supply a capturing transport.
     */
    protected BedrockRuntimeClient bedrockClient() {
        if (bedrockClient == null) {
            try {
                bedrockClient = BedrockClients.newRuntimeClient();
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to initialize Bedrock client", e);
            }
        }
        return bedrockClient;
    }

    @Override
    public APIDialect getDialect() {
        return APIDialect.BEDROCK_CONVERSE;
    }

    @Override
    protected TextWrapper<ContentBlock> textWrapper() {
        return new BedrockTextWrapper();
    }

    @Override
    protected Map<Class<? extends ContentBlocks.ContentBlock>, Class<? extends BlockEncoder<ContentBlock>>> buildEncoders() {
        Map<Class<? extends ContentBlocks.ContentBlock>, Class<? extends BlockEncoder<ContentBlock>>> m = super.buildEncoders();
        m.put(ContentBlocks.ImageBlock.class, BedrockImageBlockEncoder.class);
        m.put(ContentBlocks.FileBlock.class, BedrockFileBlockEncoder.class);
        return m;
    }

    @Override
    public void setModel(ModelSpec modelInfo) {
        this.model = modelInfo;
        this.bedrockModelId = modelInfo.getWireModelId();
        if (this.bedrockModelId == null) {
            throw new UncorrectableRuntimeLLMException(
                    "Model '" + modelInfo.getId() + "' has no wire model id configured.");
        }
    }

    @Override
    protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
        LLMResponse<T> response = new LLMResponse<>(request);
        ConverseResponse converseResponse;
        try {
            converseResponse = bedrockClient().converse(buildConverseRequest(request, prepared));
        }
        catch (RuntimeException e) {
            // An unresolvable endpoint hostname means the model is not served in this
            // deployment's region, because the region built the hostname; say that.
            throw BedrockClients.refineUnknownHost(e, model, BedrockClients.region());
        }
        // The AWS envelope: request id for support correlation, raw HTTP headers verbatim.
        // Converse echoes no served-model id and carries no rate-limit headers.
        if (converseResponse.responseMetadata() != null) {
            response.setProviderRequestId(converseResponse.responseMetadata().requestId());
        }
        if (converseResponse.sdkHttpResponse() != null) {
            Map<String, String> headers = new LinkedHashMap<>();
            converseResponse.sdkHttpResponse().headers().forEach((name, values) ->
                    headers.putIfAbsent(name.toLowerCase(Locale.ROOT), values.isEmpty() ? null : values.getFirst()));
            response.setProviderHeaders(headers);
        }
        // Record the normalized stop reason; the abstract client's template records the requested
        // ceiling (resolveWireMaxTokens) and centralizes the truncation decision.
        StopReason stopReason = converseResponse.stopReason();
        response.setStopReason(LLMStopReason.from(stopReason == null ? null : stopReason.toString()));
        // Extract response text
        StringBuilder responseText = new StringBuilder();
        if (converseResponse.output() != null && converseResponse.output().message() != null) {
            for (ContentBlock block : converseResponse.output().message().content()) {
                if (block.text() != null) {
                    responseText.append(block.text());
                }
            }
        }
        // Set response content
        IncomingMessage<T> incomingMessage = response.getResponseMessage();
        incomingMessage.overwriteRawContent(responseText.toString());
        // Bedrock Converse reports usage with cache counters additive to inputTokens
        // (same shape as Anthropic's Messages API, different AWS field names).
        if (converseResponse.usage() != null) {
            software.amazon.awssdk.services.bedrockruntime.model.TokenUsage usage = converseResponse.usage();
            response.setAdditiveUsage(usage.inputTokens(), usage.cacheWriteInputTokens(),
                    usage.cacheReadInputTokens(), usage.outputTokens());
            incomingMessage.setActualOutputTokens(usage.outputTokens());
        }
        log.debug("Raw content:\n{}", responseText);
        return response;
    }

    /**
     * Assembles the wire request from the prepared conversation. Separate from
     * {@link #doSingleResponse} so the mapping can be asserted without a transport.
     */
    protected ConverseRequest buildConverseRequest(LLMRequest<?> request, PreparedConversation prepared) {
        ConversationContext context = request.getContext();
        // Build system prompt: admitted skills first (top of system context for cache
        // hit and recency in the model's attention), then the conversation's system
        // content - the main objective, already rendered by the shared pipeline. This
        // client renders what it is handed and decides nothing about routing; a second
        // routing decision here could only agree with the shared one or duplicate content.
        List<SystemContentBlock> systemBlocks = new ArrayList<>();
        for (Skill skill : context.getLoadedSkills()) {
            systemBlocks.add(SystemContentBlock.builder().text(Skill.renderForSystemPrompt(skill)).build());
        }
        if (prepared.hasSystemText()) {
            systemBlocks.add(SystemContentBlock.builder().text(prepared.systemText()).build());
        }
        // The initial palette renders as text after the instructions - deliberately NOT the
        // native toolConfig: sending toolConfig switches Nova into Amazon's own tool-use
        // scaffolding, and the model answers in <thinking> prose instead of the response
        // envelope, verified live at roughly every second case. Mid-conversation
        // announcements render inline in their turns via the default encoder.
        if (!prepared.toolDefinitions().isEmpty()) {
            String palette = prepared.toolDefinitions().stream()
                    .map(ai.redouble.nucleo.harness.llm.encode.ToolDefinitionBlockEncoder::renderText)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            systemBlocks.add(SystemContentBlock.builder().text(palette).build());
        }
        // Render each turn
        List<software.amazon.awssdk.services.bedrockruntime.model.Message> messages = new ArrayList<>();
        for (ProcessedMessageData msg : prepared.turns()) {
            List<ContentBlock> bedrockBlocks = new ArrayList<>();
            for (ContentBlocks.ContentBlock block : msg.contentBlocks()) {
                ContentBlock encoded = encodeBlock(block);
                if (encoded != null) {
                    bedrockBlocks.add(encoded);
                }
            }
            if (!bedrockBlocks.isEmpty()) {
                ConversationRole role = msg.role() == TurnRole.ASSISTANT
                        ? ConversationRole.ASSISTANT : ConversationRole.USER;
                messages.add(software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                        .role(role)
                        .content(bedrockBlocks)
                        .build());
            }
        }
        // Build inference config - output budget must match what JobResources reserved locally.
        // See ConversationContext.resolveOutputBudget. Temperature travels only when a caller
        // set one; unset defers to each model's own vendor default.
        InferenceConfiguration.Builder inferenceConfigBuilder = InferenceConfiguration.builder()
                .maxTokens(resolveWireMaxTokens(context));
        if (temperature != null) {
            inferenceConfigBuilder.temperature(temperature.floatValue());
        }
        InferenceConfiguration inferenceConfig = inferenceConfigBuilder.build();
        // Build request. The per-request override threads the LLMRequest through AWS's own
        // ExecutionAttributes so BedrockAuditInterceptor can write the marshalled wire bytes
        // back into LLMRequest.inputJson / .outputJson.
        ConverseRequest.Builder requestBuilder = ConverseRequest.builder()
                .modelId(bedrockModelId)
                .messages(messages)
                .inferenceConfig(inferenceConfig)
                .overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                        .putExecutionAttribute(BedrockAuditInterceptor.LLM_REQUEST, request)
                        .build());
        // gpt-oss always reasons; its verbosity is the reasoning_effort field, and left
        // unset the model defaults to medium - which on the row-decode fan-out meant ~600
        // reasoning tokens per call for a mechanical verbatim echo. Map the call's depth
        // onto the effort so IMMEDIATE/QUICK collapse the reasoning to its floor. The
        // reasoning tokens count inside maxTokens; resolveWireMaxTokens adds the entry's
        // thinking budget as headroom above the declared answer.
        if (context.getModel().getThinkingMode() == ThinkingMode.REASONING_EFFORT) {
            requestBuilder.additionalModelRequestFields(software.amazon.awssdk.core.document.Document.mapBuilder()
                    .putString("reasoning_effort", reasoningEffortFor(context.resolveDepth()))
                    .build());
        }
        if (!systemBlocks.isEmpty()) {
            requestBuilder.system(systemBlocks);
        }
        return requestBuilder.build();
    }

    @Override
    protected boolean is429Error(Exception e) {
        return BedrockClients.isThrottling(e);
    }

    @Override
    protected boolean isServerError(Exception e) {
        return BedrockClients.isServerError(e);
    }

    /** Bedrock Converse has no dedicated overload status; capacity pressure arrives as throttling (the 429 path). */
    @Override
    protected boolean isOverloadError(Exception e) {
        return false;
    }

    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        return new RateLimitInfo(
                model != null ? model.getId() : "unknown",
                null, null, null, null, null, null,
                true, RateLimitType.CAPACITY);
    }
}
