/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.secrets.*;
import ai.redouble.nucleo.util.*;
import com.anthropic.client.*;
import com.anthropic.client.okhttp.*;
import com.anthropic.core.*;
import com.anthropic.core.http.*;
import com.anthropic.models.messages.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * Anthropic Claude API client using the official Java SDK.
 * Supports both streaming and non-streaming responses with prompt caching.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-06-13)
 */
public class AnthropicSDKClient extends AbstractLLMClient<ContentBlockParam> {
    private static final Logger log = LoggerFactory.getLogger(AnthropicSDKClient.class);
    protected AnthropicClient client;
    protected Model anthropicModel;

    /** The id of the Anthropic API key in the deployment's secret store. */
    public static final String SECRET_ID = "anthropic-api-key";
    /** What the credential is made of: the key alone. */
    public static final CredentialShape SHAPE = CredentialShape.secret(SECRET_ID, "ANTHROPIC_API_KEY", "the API key");

    public AnthropicSDKClient() {
        try {
            String apiKey = Secrets.configured().require(SECRET_ID).secret();
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey)
                    .maxRetries(0)
                    .build();
            this.formatter = createFormatter();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Protected constructor for subclasses that configure the client differently (e.g., Bedrock).
     */
    protected AnthropicSDKClient(boolean subclassInit) {
        // Subclass will initialize client
        this.formatter = createFormatter();
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected ContentFormatter createFormatter() {
        return new AnthropicContentFormatter();
    }

    @Override
    public APIDialect getDialect() {
        return APIDialect.ANTHROPIC_V1;
    }

    @Override
    protected TextWrapper<ContentBlockParam> textWrapper() {
        return new AnthropicTextWrapper();
    }

    /**
     * Anthropic renders images, files, tool-use, tool-result, and thinking blocks as native
     * structures; everything else (text, JSON, skills) rides the shared text encoders. The wire
     * max_tokens folds in the thinking budget (resolveWireMaxTokens) so the truncation ceiling matches.
     */
    @Override
    protected Map<Class<? extends ContentBlocks.ContentBlock>, Class<? extends BlockEncoder<ContentBlockParam>>> buildEncoders() {
        Map<Class<? extends ContentBlocks.ContentBlock>, Class<? extends BlockEncoder<ContentBlockParam>>> m = super.buildEncoders();
        m.put(ContentBlocks.ImageBlock.class, AnthropicImageBlockEncoder.class);
        m.put(ContentBlocks.FileBlock.class, AnthropicFileBlockEncoder.class);
        m.put(ContentBlocks.ToolUseBlock.class, AnthropicToolUseBlockEncoder.class);
        m.put(ContentBlocks.ToolResultBlock.class, AnthropicToolResultBlockEncoder.class);
        // Suppressed inline because buildMessageCreateParams sweeps the same blocks into the
        // native tools parameter - the suppression and the collection are this one client's pair
        m.put(ContentBlocks.ToolDefinitionBlock.class, AnthropicToolDefinitionBlockEncoder.class);
        m.put(ContentBlocks.ThinkingBlock.class, AnthropicThinkingBlockEncoder.class);
        m.put(ContentBlocks.RedactedThinkingBlock.class, AnthropicRedactedThinkingBlockEncoder.class);
        return m;
    }

    /** Text a cache-anchored block carries: the same string its default encoder wrapped. */
    private String cacheAnchorText(ContentBlocks.ContentBlock block) {
        if (block instanceof ContentBlocks.TextBlock tb) {
            return tb.text();
        }
        return Skill.renderBody(((ContentBlocks.SkillBlock) block).skill());
    }


    /** The schema keys the SDK's InputSchema builder models as typed slots; every other key travels as an additional property. */
    private static final Set<String> SDK_TYPED_SCHEMA_KEYS = Set.of("type", "properties", "required");

    @SuppressWarnings("unchecked")
    private List<ToolUnion> convertToAnthropicTools(List<ToolDefinitionBlock> toolDefs) {
        return toolDefs.stream().map(def -> {
            try {
                Map<String, Object> schema = NucleoJsonSerializer.parse(def.schemaJson(), Map.class);
                Tool.InputSchema.Builder inputSchemaBuilder = Tool.InputSchema.builder()
                                                                              .type(JsonValue.from(schema.get("type")))
                                                                              .properties(JsonValue.from(schema.get("properties")))
                                                                              .required((List<String>)schema.get("required"));
                // The API takes the whole JSON Schema document. The three named keys are the
                // builder's typed slots; everything else ($defs for a recursive type,
                // additionalProperties, title) rides as-is, or a $ref inside properties
                // would reach the model with nothing to point at.
                for (Map.Entry<String, Object> entry : schema.entrySet()) {
                    if (!SDK_TYPED_SCHEMA_KEYS.contains(entry.getKey())) {
                        inputSchemaBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
                    }
                }
                Tool.InputSchema inputSchema = inputSchemaBuilder.build();
                Tool tool = Tool.builder().name(def.name()).description(def.description()).inputSchema(inputSchema)
                        .build();
                return ToolUnion.ofTool(tool);
            }
            catch (Exception e) {
                // Our own tool definition, so a failure here is a defect in this process rather
                // than anything the model authored: uncorrectable, with the cause carrying the
                // detail. Printing the trace as well would report one defect twice.
                throw new UncorrectableRuntimeLLMException("Failed to convert tool definition: " + def.name(), e);
            }
        }).toList();
    }

    /**
     * Whether this client's backend accepts cache_control blocks.
     * Subclasses override to return false if their backend does not.
     */
    protected boolean isCachingSupported() {
        return true;
    }

    /**
     * Attaches the right thinking config for this model:
     * <ul>
     *   <li>EXTENDED: {@code thinking.type = "enabled"} with the caller-supplied budget.
     *   <li>ADAPTIVE: {@code thinking.type = "adaptive"} with {@code display = "summarized"}
     *       plus {@code output_config.effort = ...}. Anthropic 4.7 requires {@code display}
     *       explicit or the ThinkingBlock comes back empty.
     *   <li>NONE or kill switch off: attaches nothing.
     * </ul>
     *
     * @return true if a thinking config was attached. Callers send no temperature in that case,
     *         because Anthropic rejects any value other than its default when thinking is on.
     */
    protected boolean attachThinkingConfig(MessageCreateParams.Builder paramsBuilder, ConversationContext context, int thinkingBudget) {
        Depth depth = context.resolveDepth();
        if (!ThinkingMode.thinkingActive(model, depth)) {
            return false;
        }
        switch (model.getThinkingMode()) {
            case EXTENDED -> paramsBuilder.thinking(ThinkingConfigEnabled.builder().budgetTokens(thinkingBudget).build());
            case ADAPTIVE -> {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build());
                paramsBuilder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(depthToAnthropicEffort(depth))).build());
            }
            case NONE -> { return false; }
            // a reasoning-effort model (OpenAI direct, gpt-oss on Converse) never reaches an
            // Anthropic client, but the switch must be exhaustive: attach no Anthropic thinking
            case REASONING_EFFORT -> { return false; }
        }
        return true;
    }

    private static String depthToAnthropicEffort(Depth depth) {
        return switch (depth) {
            case QUICK -> "low";
            case STANDARD -> "medium";
            case THOROUGH, ULTRA_THOROUGH -> "high";
            case IMMEDIATE -> throw new IllegalArgumentException("IMMEDIATE depth should not reach adaptive config");
        };
    }

    /**
     * Temperature travels only when a caller set one; unset sends nothing and the provider's
     * default applies (1.0 - the only value accepted with thinking on, and the only value
     * models after Opus 4.6 accept at all). The provider's verdict on a set one is the
     * caller's to receive.
     */
    @SuppressWarnings("deprecation") // the API retired the field after Opus 4.6; a caller who sets one asked for it
    private void attachTemperature(MessageCreateParams.Builder paramsBuilder, boolean thinkingAttached) {
        if (!thinkingAttached && temperature != null) {
            paramsBuilder.temperature(temperature);
        }
    }

    /**
     * Builds SDK request parameters from the prepared conversation.
     * Applies cache_control to turns marked for caching, up to Anthropic's limit of 4 breakpoints per request.
     */
    protected MessageCreateParams buildMessageCreateParams(ConversationContext context, PreparedConversation prepared) {
        // Track cache breakpoints (Anthropic allows max 4 per request)
        int cacheBreakpointsUsed = 0;
        final int MAX_CACHE_BREAKPOINTS = 4;

        // Output budget must match what JobResources reserved locally - Anthropic/Bedrock pre-debit
        // this from the upstream TPM bucket. resolveWireMaxTokens folds the thinking budget into the
        // ceiling (Anthropic counts thinking as output and requires max_tokens > thinking budget) and
        // is the single source the response's truncation ceiling also reads.
        int thinkingBudget = context.resolveThinkingBudget();
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder().maxTokens(resolveWireMaxTokens(context)).model(this.anthropicModel);

        boolean thinkingAttached = attachThinkingConfig(paramsBuilder, context, thinkingBudget);
        attachTemperature(paramsBuilder, thinkingAttached);

        // Every definition the request carries - palette and mid-conversation announcements -
        // goes through the native tools parameter; this client's own encoder suppresses the
        // inline form, so the two halves of that decision live in one class
        List<ToolDefinitionBlock> toolDefs = prepared.allToolDefinitions();
        if (!toolDefs.isEmpty()) {
            List<ToolUnion> tools = convertToAnthropicTools(toolDefs);
            paramsBuilder.tools(tools);
        }

        // The system parameter: admitted skills first (top of the system context for cache
        // hit and recency), then the conversation's system content - the main objective,
        // already rendered by the shared pipeline. That is the only system content there is.
        // Cache strategy: ONE cache_control on the LAST system block marks the end of the
        // stable prefix (skills + tool defs + objective), taken when the conversation asks
        // for it (cacheSystem). Counted before the turn loop so turn-level anchors cannot
        // take the prefix's breakpoint - both the count and the apply key on the same
        // rendered blocks, so they cannot disagree.
        List<TextBlockParam> systemBlocks = new ArrayList<>();
        for (Skill skill : context.getLoadedSkills()) {
            systemBlocks.add(TextBlockParam.builder().text(Skill.renderForSystemPrompt(skill)).build());
        }
        if (prepared.hasSystemText()) {
            systemBlocks.add(TextBlockParam.builder().text(prepared.systemText()).build());
        }
        boolean cacheSystemPrefix = !systemBlocks.isEmpty() && isCachingSupported() && prepared.cacheSystem();
        if (cacheSystemPrefix) {
            cacheBreakpointsUsed++;
        }

        // Render each turn
        for (ProcessedMessageData msg : prepared.turns()) {
            boolean shouldCache = isCachingSupported() && msg.cacheEnabled() && cacheBreakpointsUsed < MAX_CACHE_BREAKPOINTS;
            List<ContentBlockParam> blocks = new ArrayList<>();
            boolean appliedCache = false;
            for (ContentBlocks.ContentBlock block : msg.contentBlocks()) {
                ContentBlockParam param = encodeBlock(block);
                if (param == null) {
                    continue;
                }
                // Cache anchor: the first text- or skill-derived block in a cacheable message carries the
                // breakpoint. Those are the stable, worth-caching parts; image/tool/thinking params never
                // anchor. Rebuild that one block's text param with cache_control.
                if (shouldCache && !appliedCache && (block instanceof ContentBlocks.TextBlock || block instanceof ContentBlocks.SkillBlock)) {
                    param = ContentBlockParam.ofText(TextBlockParam.builder()
                            .text(cacheAnchorText(block))
                            .cacheControl(CacheControlEphemeral.builder().type(JsonValue.from("ephemeral")).build())
                            .build());
                    appliedCache = true;
                    cacheBreakpointsUsed++;
                }
                blocks.add(param);
            }
            if (blocks.isEmpty()) {
                continue;
            }
            switch (msg.role()) {
            case USER -> paramsBuilder.addUserMessageOfBlockParams(blocks);
            case ASSISTANT -> paramsBuilder.addAssistantMessageOfBlockParams(blocks);
            }
        }

        if (!systemBlocks.isEmpty()) {
            // The single breakpoint on the LAST system block covers the whole stable prefix.
            if (cacheSystemPrefix) {
                int lastIdx = systemBlocks.size() - 1;
                TextBlockParam last = systemBlocks.get(lastIdx);
                TextBlockParam cached = TextBlockParam.builder()
                    .text(last.text())
                    .cacheControl(CacheControlEphemeral.builder()
                        .type(JsonValue.from("ephemeral")).build())
                    .build();
                systemBlocks.set(lastIdx, cached);
            }
            paramsBuilder.systemOfTextBlockParams(systemBlocks);
        }

        return paramsBuilder.build();
    }

    @Override
    protected int countTokensExact(LLMRequest<?> request) {
        try {
            PreparedConversation prepared = prepareConversation(request.getContext());
            MessageCreateParams createParams = buildMessageCreateParams(request.getContext(), prepared);
            MessageCountTokensParams.Builder countBuilder = MessageCountTokensParams.builder()
                .model(this.anthropicModel)
                .messages(createParams.messages());
            // Both system forms must reach the counter. The request builder emits system as
            // a block list, and system carries the skills and the main objective - the
            // largest stable chunk of the request - so a count that misses it cannot be
            // trusted, and this path exists specifically to be trusted near the ceiling.
            createParams.system().ifPresent(sys -> {
                if (sys.isString()) {
                    countBuilder.system(sys.asString());
                }
                else if (sys.isTextBlockParams()) {
                    countBuilder.systemOfTextBlockParams(sys.asTextBlockParams());
                }
            });

            // Convert tools from ToolUnion to MessageCountTokensTool
            createParams.tools().ifPresent(toolUnions -> {
                List<MessageCountTokensTool> countTools = toolUnions.stream()
                    .filter(ToolUnion::isTool)
                    .map(tu -> MessageCountTokensTool.ofTool(tu.asTool()))
                    .toList();
                if (!countTools.isEmpty()) {
                    countBuilder.tools(countTools);
                }
            });
            MessageTokensCount result = client.messages().countTokens(countBuilder.build());
            return (int) result.inputTokens();
        }
        catch (Exception e) {
            log.warn("count_tokens API failed, falling back to estimate: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * The provider's account of a refusal onto the response: the category word to
     * {@link LLMResponse#setRefusalCategory}, the explanation to {@link LLMResponse#setRefusal},
     * each null when the provider left it out.
     */
    private static void recordRefusal(LLMResponse<?> response, com.anthropic.models.messages.RefusalStopDetails details) {
        response.setRefusalCategory(details.category().map(com.anthropic.models.messages.RefusalStopDetails.Category::asString).orElse(null));
        response.setRefusal(details.explanation().orElse(null));
    }

    @Override
    protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
        LLMResponse<T> response = new LLMResponse<T>(request);
        ConversationContext context = request.getContext();

        MessageCreateParams params = buildMessageCreateParams(context, prepared);
        // Serialize the request BODY, not the params wrapper: MessageCreateParams holds body +
        // headers + query, and the SDK mapper renders the wrapper as "{}" - the actual wire payload
        // (system + messages + model + max_tokens) lives in _body(). Capturing the wrapper silently
        // lost every prompt on this path.
        request.setInputJson(serializeSdkObject(params._body()));

        // The raw response carries the headers; it is closed once the body is parsed and the
        // envelope read, releasing the connection
        com.anthropic.models.messages.Message message;
        try (HttpResponseFor<com.anthropic.models.messages.Message> rawResponse = client.messages().withRawResponse().create(params)) {
            message = rawResponse.parse();
            response.setRateLimitInfo(extractRateLimitFromHeaders(rawResponse.headers()));
            // The full provider envelope: every header verbatim and the provider's own request id
            response.setProviderHeaders(headersToMap(rawResponse.headers()));
            response.setProviderRequestId(headerValue(rawResponse.headers(), "request-id"));
        }
        request.setOutputJson(serializeSdkObject(message));
        // The served model echo (alias -> snapshot)
        response.setServedModelId(message.model().toString());

        // Record the normalized stop reason; the abstract client's template records the requested
        // ceiling (resolveWireMaxTokens) and centralizes the truncation decision.
        response.setStopReason(LLMStopReason.from(message.stopReason().map(Object::toString).orElse(null)));
        message.stopDetails().ifPresent(details -> recordRefusal(response, details));

        // Anthropic's Messages API reports usage as three additive counters (regular input, cache
        // creation, cache read); setAdditiveUsage sums them into the grand-total contract.
        com.anthropic.models.messages.Usage usage = message.usage();
        Integer cacheCreation = usage.cacheCreationInputTokens().map(Long::intValue).orElse(null);
        Integer cacheRead = usage.cacheReadInputTokens().map(Long::intValue).orElse(null);
        int regularInput = (int)usage.inputTokens();
        response.setAdditiveUsage(regularInput, cacheCreation, cacheRead, (int)usage.outputTokens());

        // Set output tokens on the incoming message (separate field on IncomingMessage)
        IncomingMessage<T> incomingMessage = response.getResponseMessage();
        incomingMessage.setMessageId(message.id());
        incomingMessage.setActualOutputTokens((int)message.usage().outputTokens());

        // Get the content blocks
        List<com.anthropic.models.messages.ContentBlock> anthropicBlocks = message.content();

        // Convert Anthropic blocks to our ContentBlock types
        List<ai.redouble.nucleo.harness.conversation.ContentBlocks.ContentBlock> ourBlocks = new ArrayList<>();
        StringBuilder textContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();
        for (com.anthropic.models.messages.ContentBlock block : anthropicBlocks) {
            if (block.isText()) {
                String text = block.asText().text();
                ourBlocks.add(new ai.redouble.nucleo.harness.conversation.ContentBlocks.TextBlock(text));
                textContent.append(text);
            }
            else if (block.isToolUse()) {
                com.anthropic.models.messages.ToolUseBlock toolUse = block.asToolUse();
                try {
                    String inputJson = NucleoJsonSerializer.write(toolUse._input());
                    ourBlocks.add(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolUseBlock(toolUse.id(), toolUse.name(), inputJson));
                }
                catch (Exception e) {
                    log.error(e.getMessage(), e);
                    throw new RuntimeException("Failed to serialize tool use input", e);
                }
            }
            else if (block.isThinking()) {
                // Anthropic extended / adaptive thinking. Signature must round-trip verbatim on
                // later turns or the request will 400. Thinking text is empty when the request
                // used "display: omitted" (4.7 default without our explicit override).
                com.anthropic.models.messages.ThinkingBlock tb = block.asThinking();
                ourBlocks.add(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ThinkingBlock(tb.thinking(), tb.signature()));
                thinkingContent.append(tb.thinking());
            }
            else if (block.isRedactedThinking()) {
                // Policy-redacted thinking: no readable text, only the opaque echo-back payload.
                com.anthropic.models.messages.RedactedThinkingBlock rt = block.asRedactedThinking();
                ourBlocks.add(new ai.redouble.nucleo.harness.conversation.ContentBlocks.RedactedThinkingBlock(rt.data()));
            }
            else {
                // a block kind this client does not carry: said aloud, never dropped in silence
                log.warn("Response from {} carried a content block of a kind this client does not carry, dropped: {}", getModelIdentifier(), block);
            }
        }
        if (ourBlocks.isEmpty()) {
            // an answer with nothing in it is a fact about the exchange - a refusal, a filter, a
            // model that stopped before saying anything - and the raw message is the only
            // evidence; a caller that sees only "empty response" cannot diagnose it
            log.warn("Empty response from {}: stop_reason={}, {} output tokens, raw message {}\nfor the request {}", getModelIdentifier(),
                    message.stopReason().map(Object::toString).orElse("none"), message.usage().outputTokens(), request.getOutputJson(), request.getInputJson());
        }

        incomingMessage.setContentBlocks(ourBlocks);
        incomingMessage.overwriteRawContent(textContent.toString());

        // Anthropic-specific response detail; the template logs the call's one-line summary,
        // tokens and cache reads included
        if (!thinkingContent.isEmpty()) {
            log.debug("Thinking:\n{}", thinkingContent);
        }
        if (!textContent.isEmpty()) {
            log.debug("Text:\n{}", textContent);
        }
        for (ai.redouble.nucleo.harness.conversation.ContentBlocks.ContentBlock b : ourBlocks) {
            if (b instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolUseBlock tu) {
                log.debug("Tool call: {} [{}] {}", tu.toolName(), tu.toolUseId(), tu.inputJson());
            }
        }
        return response;
    }

    /**
     * Checks if an exception represents a rate limit error.
     */
    @Override
    protected boolean is429Error(Exception e) {
        return messageChainContains(e, "rate limit", "429", "too many requests");
    }

    @Override
    protected boolean isQuotaError(Exception e) {
        // Anthropic signals an out-of-money account with an HTTP 400 whose
        // message is "Your credit balance is too low to access the Anthropic
        // API ..." - not a 429, so is429Error never catches it and it would
        // otherwise degrade to a generic SystemException.
        return messageChainContains(e, "credit balance is too low", "credit balance");
    }

    @Override
    protected String accountIdentifier() {
        return "ANTHROPIC / secret " + SECRET_ID;
    }

    /**
     * Anthropic signals overload with a dedicated 529 {@code overloaded_error}. Walks
     * the same typed cause chain as {@link #isServerError(Exception)}; the status code
     * alone identifies it.
     */
    @Override
    protected boolean isOverloadError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof com.anthropic.errors.AnthropicServiceException svc && svc.statusCode() == 529) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Checks if an exception represents a server error (5xx) or connection failure.
     * Uses the Anthropic SDK's typed exception hierarchy:
     * - {@code InternalServerException} (500)
     * - {@code UnexpectedStatusCodeException} (502, 503, 529, etc.)
     * - {@code AnthropicIoException} (connection failures)
     */
    @Override
    protected boolean isServerError(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof com.anthropic.errors.InternalServerException) {
                return true;
            }
            if (current instanceof com.anthropic.errors.AnthropicIoException) {
                return true;
            }
            if (current instanceof com.anthropic.errors.AnthropicServiceException svc && svc.statusCode() >= 500) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Extracts rate limit information from an exception. Walks the cause chain
     * looking for an {@code AnthropicServiceException} and pulls the
     * {@code anthropic-ratelimit-*} and {@code retry-after} headers from the
     * server's response. Logs the server's view at WARN level so an operator
     * reading the log can see the actual upstream state alongside our local
     * bucket state, which is the fastest way to diagnose a mismatch between
     * our local TPM config and what the upstream provider is enforcing.
     *
     * <p>Message keywords ("acceleration", "usage increase rate") still tag
     * the logged {@link RateLimitType} for diagnostic value, but behavior no
     * longer branches on the tag - every 429 feeds the same adaptive throttle
     * loop ({@link TokenBucketRateLimiter#recordBackpressure(int)} at weight 1).
     *
     * <p>If no known rate-limit headers are present (the common case on
     * Bedrock, which does not forward Anthropic's rate-limit headers), dumps
     * the full response header set plus the exception cause chain at WARN
     * level so we can spot any new hints the provider starts sending.
     */
    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        String message = e.getMessage();
        String lowerMsg = message != null ? message.toLowerCase() : "";
        boolean isAcceleration = lowerMsg.contains("acceleration") || lowerMsg.contains("usage increase rate") || (lowerMsg.contains("scale up")
                                                                                                                   && lowerMsg.contains("gradually"));
        RateLimitType type = isAcceleration ? RateLimitType.ACCELERATION : RateLimitType.CAPACITY;

        // Walk the cause chain to find an AnthropicServiceException so we can
        // read the response headers directly. RateLimitException extends
        // AnthropicServiceException, so either shows up here.
        Headers serverHeaders = null;
        Throwable current = e;
        while (current != null) {
            if (current instanceof com.anthropic.errors.AnthropicServiceException svc) {
                serverHeaders = svc.headers();
                break;
            }
            current = current.getCause();
        }

        if (serverHeaders == null) {
            log.warn("[RATE-LIMIT] 429 from {} (no server headers on exception, type={}): {}", (model != null ? model.getId() : "unknown"), type, message);
            return new RateLimitInfo(model != null ? model.getId() : "unknown", null, null, null, null, null, null, true, type);
        }

        // Anthropic direct API rate-limit headers (may be absent on Bedrock).
        String tokensLimit = headerValue(serverHeaders, "anthropic-ratelimit-tokens-limit");
        String tokensRemaining = headerValue(serverHeaders, "anthropic-ratelimit-tokens-remaining");
        String tokensReset = headerValue(serverHeaders, "anthropic-ratelimit-tokens-reset");
        String requestsLimit = headerValue(serverHeaders, "anthropic-ratelimit-requests-limit");
        String requestsRemaining = headerValue(serverHeaders, "anthropic-ratelimit-requests-remaining");
        String requestsReset = headerValue(serverHeaders, "anthropic-ratelimit-requests-reset");
        String inputTokensLimit = headerValue(serverHeaders, "anthropic-ratelimit-input-tokens-limit");
        String inputTokensRemaining = headerValue(serverHeaders, "anthropic-ratelimit-input-tokens-remaining");
        String outputTokensLimit = headerValue(serverHeaders, "anthropic-ratelimit-output-tokens-limit");
        String outputTokensRemaining = headerValue(serverHeaders, "anthropic-ratelimit-output-tokens-remaining");
        // Shared: Retry-After is the one header that shows up on both paths.
        String retryAfter = headerValue(serverHeaders, "retry-after");
        // Bedrock / AWS-specific: the only useful things AWS returns on a
        // ThrottlingException. AWS does NOT put your actual quota in the
        // response - that lives only in the Service Quotas console and
        // CloudWatch metrics.
        String awsErrorType = headerValue(serverHeaders, "x-amzn-errortype");
        String awsRequestId = headerValue(serverHeaders, "x-amzn-requestid");
        if (awsRequestId == null) {
            awsRequestId = headerValue(serverHeaders, "x-amzn-RequestId");
        }

        // Surface whatever the server told us so mismatches between our local
        // bucket and the real upstream state are immediately visible.
        StringBuilder diag = new StringBuilder("[RATE-LIMIT] 429 from ")
                .append(model != null ? model.getId() : "unknown")
                .append(" type=").append(type);
        if (retryAfter != null) diag.append(" retry-after=").append(retryAfter).append("s");
        if (tokensLimit != null || tokensRemaining != null) {
            diag.append(" tokens=").append(tokensRemaining).append("/").append(tokensLimit);
            if (tokensReset != null) diag.append(" (reset ").append(tokensReset).append(")");
        }
        if (inputTokensLimit != null || inputTokensRemaining != null) {
            diag.append(" input-tokens=").append(inputTokensRemaining).append("/").append(inputTokensLimit);
        }
        if (outputTokensLimit != null || outputTokensRemaining != null) {
            diag.append(" output-tokens=").append(outputTokensRemaining).append("/").append(outputTokensLimit);
        }
        if (requestsLimit != null || requestsRemaining != null) {
            diag.append(" requests=").append(requestsRemaining).append("/").append(requestsLimit);
            if (requestsReset != null) diag.append(" (reset ").append(requestsReset).append(")");
        }
        if (awsErrorType != null) diag.append(" aws-error=").append(awsErrorType);
        if (awsRequestId != null) diag.append(" aws-request-id=").append(awsRequestId);
        if (message != null) diag.append(" msg=").append(message);
        log.warn(diag.toString());
        // If none of the known Anthropic/AWS rate-limit headers were present,
        // dump the full header set so we can see what Bedrock actually returned
        // and wire up anything we're missing. Condition keeps the dump rare -
        // on direct Anthropic this path is silent.
        if (tokensLimit == null && tokensRemaining == null
                && requestsLimit == null && requestsRemaining == null
                && inputTokensLimit == null && outputTokensLimit == null) {
            StringBuilder hdump = new StringBuilder("[RATE-LIMIT] raw response headers:");
            for (String name : serverHeaders.names()) {
                hdump.append("\n  ").append(name).append(" = ").append(serverHeaders.values(name));
            }
            hdump.append("\n  exception chain:");
            Throwable walk = e;
            while (walk != null) {
                hdump.append("\n    ").append(walk.getClass().getName());
                if (walk.getMessage() != null) {
                    String m = walk.getMessage();
                    hdump.append(" :: ").append(m, 0, Math.min(m.length(), 200));
                }
                walk = walk.getCause();
            }
            log.warn(hdump.toString());
        }

        Integer tl = RateLimitInfo.parseIntOrNull(tokensLimit);
        Integer tr = RateLimitInfo.parseIntOrNull(tokensRemaining);
        Integer rl = RateLimitInfo.parseIntOrNull(requestsLimit);
        Integer rr = RateLimitInfo.parseIntOrNull(requestsRemaining);
        Duration ra = parseRetryAfter(retryAfter);
        return new RateLimitInfo(model != null ? model.getId() : "unknown", tl, tr, null, rl, rr, ra, true, type);
    }

    private static String headerValue(Headers headers, String name) {
        List<String> values = headers.values(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    /** Flattens SDK headers into a plain lowercased-name map, first value per name. */
    private static Map<String, String> headersToMap(Headers headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            String value = headerValue(headers, name);
            if (value != null) {
                map.put(name.toLowerCase(Locale.ROOT), value);
            }
        }
        return map;
    }

    private static Duration parseRetryAfter(String s) {
        if (s == null) return null;
        try { return Duration.ofSeconds(Long.parseLong(s.trim())); } catch (NumberFormatException nfe) { return null; }
    }

    /**
     * Extracts rate limit information from HTTP headers.
     * Anthropic provides rate limit info in these headers:
     * - anthropic-ratelimit-requests-limit
     * - anthropic-ratelimit-requests-remaining
     * - anthropic-ratelimit-tokens-limit
     * - anthropic-ratelimit-tokens-remaining
     */
    private RateLimitInfo extractRateLimitFromHeaders(Headers headers) {
        if (headers == null) {
            return null;
        }
        try {
            String requestsLimit = headerValue(headers, "anthropic-ratelimit-requests-limit");
            String requestsRemaining = headerValue(headers, "anthropic-ratelimit-requests-remaining");
            String tokensLimit = headerValue(headers, "anthropic-ratelimit-tokens-limit");
            String tokensRemaining = headerValue(headers, "anthropic-ratelimit-tokens-remaining");
            // Log the extracted headers
            if (requestsLimit != null || tokensLimit != null) {
                log.info("Rate limit headers - Requests: {} (remaining: {}), Tokens: {} (remaining: {})", requestsLimit, requestsRemaining, tokensLimit, tokensRemaining);
            }
            // Create RateLimitInfo if we have any rate limit data
            if (requestsLimit != null || tokensLimit != null) {
                // Use the constructor with appropriate values
                Integer requestsLimitInt = requestsLimit != null ? Integer.valueOf(requestsLimit) : null;
                Integer requestsRemainingInt = requestsRemaining != null ? Integer.valueOf(requestsRemaining) : null;
                Integer tokensLimitInt = tokensLimit != null ? Integer.valueOf(tokensLimit) : null;
                Integer tokensRemainingInt = tokensRemaining != null ? Integer.valueOf(tokensRemaining) : null;
                // Create RateLimitInfo using constructor
                // RateLimitInfo(String model, Integer tokensLimit, Integer tokensRemaining, Instant tokensReset,
                //               Integer requestsLimit, Integer requestsRemaining, Duration retryAfter, boolean wasRateLimited)
                return new RateLimitInfo(
                        model != null ? model.getId() : "unknown", tokensLimitInt, tokensRemainingInt, null,  // tokensReset not provided in headers
                        requestsLimitInt, requestsRemainingInt, null,  // retryAfter not provided in successful responses
                        false  // wasRateLimited = false for successful responses
                );
            }
        }
        catch (Exception ex) {
            log.error("Failed to parse rate limit headers: {}", ex.getMessage());
        }
        return null;
    }

    public void setAnthropicModel(Model anthropicModel) {
        this.anthropicModel = anthropicModel;
        ModelSpec spec = Models.findSpec(anthropicModel.toString());
        if (spec == null) {
            throw new UncorrectableRuntimeLLMException("No model spec found for Anthropic model: " + anthropicModel);
        }
        this.model = spec;  // Set parent's model field
    }

    public Model getAnthropicModel() {
        return this.anthropicModel;
    }

    @Override
    public void setModel(ModelSpec modelInfo) {
        super.setModel(modelInfo);  // Set parent's model field
        this.anthropicModel = AnthropicModelResolver.resolve(modelInfo);
    }

    /**
     * Streaming implementation. Parses {@code message_start} for initial input +
     * cache counters and the message ID; {@code message_delta} for the final
     * {@code output_tokens} and {@code stop_reason}; content-block deltas for
     * text. Populates the response via {@link LLMResponse#setAdditiveUsage} once
     * the stream completes. {@code rateLimitInfo} is not populated on streaming
     * because the SSE transport doesn't expose HTTP response headers.
     */
    @Override
    protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response, PreparedConversation prepared, Consumer<StreamChunk> chunkHandler) {
        ConversationContext context = request.getContext();
        MessageCreateParams params = buildMessageCreateParams(context, prepared);
        // Serialize the request BODY, not the params wrapper: MessageCreateParams holds body +
        // headers + query, and the SDK mapper renders the wrapper as "{}" - the actual wire payload
        // (system + messages + model + max_tokens) lives in _body(). Capturing the wrapper silently
        // lost every prompt on this path.
        request.setInputJson(serializeSdkObject(params._body()));
        int[] regularInput = {0};
        Integer[] cacheCreation = {null};
        Integer[] cacheRead = {null};
        int[] outputTokens = {0};
        String[] stopReason = {null};
        IncomingMessage<T> incomingMessage = response.getResponseMessage();
        // Stream events form the wire output. Accumulate verbatim via the SDK's mapper so the
        // audit trail mirrors the SSE sequence the upstream actually sent. Persist in a finally
        // so partial events survive on stream failure - half a response is more informative
        // than nothing when debugging a broken call.
        com.fasterxml.jackson.databind.node.ArrayNode eventLog =
                com.anthropic.core.ObjectMappers.jsonMapper().createArrayNode();
        try (StreamResponse<RawMessageStreamEvent> streamResponse = client.messages().createStreaming(params)) {
            streamResponse.stream().forEach(event -> {
                eventLog.add(com.anthropic.core.ObjectMappers.jsonMapper().valueToTree(event));
                if (event.isMessageStart()) {
                    com.anthropic.models.messages.Message msg = event.asMessageStart().message();
                    com.anthropic.models.messages.Usage usage = msg.usage();
                    regularInput[0] = (int)usage.inputTokens();
                    cacheCreation[0] = usage.cacheCreationInputTokens().map(Long::intValue).orElse(null);
                    cacheRead[0] = usage.cacheReadInputTokens().map(Long::intValue).orElse(null);
                    incomingMessage.setMessageId(msg.id());
                }
                else if (event.isContentBlockDelta()) {
                    RawContentBlockDelta delta = event.asContentBlockDelta().delta();
                    if (delta.isText()) {
                        chunkHandler.accept(StreamChunk.of(delta.asText().text()));
                    }
                    else if (delta.isThinking()) {
                        chunkHandler.accept(StreamChunk.of(delta.asThinking().thinking()));
                    }
                }
                else if (event.isMessageDelta()) {
                    com.anthropic.models.messages.RawMessageDeltaEvent md = event.asMessageDelta();
                    outputTokens[0] = (int)md.usage().outputTokens();
                    md.delta().stopReason().ifPresent(sr -> stopReason[0] = sr.toString());
                    md.delta().stopDetails().ifPresent(details -> recordRefusal(response, details));
                }
                else if (event.isMessageStop()) {
                    chunkHandler.accept(StreamChunk.done());
                }
            });
        }
        finally {
            request.setOutputJson(eventLog.toString());
        }
        response.setAdditiveUsage(regularInput[0], cacheCreation[0], cacheRead[0], outputTokens[0]);
        response.setStopReason(LLMStopReason.from(stopReason[0]));
        incomingMessage.setActualOutputTokens(outputTokens[0]);
    }

    /**
     * Serializes an Anthropic SDK request/response object to its wire JSON. The SDK's
     * params and message types use {@code JsonField}/{@code JsonValue} wrappers that
     * Jackson alone can't introspect correctly; the SDK ships a preconfigured
     * {@link com.anthropic.core.ObjectMappers#jsonMapper()} that is the only safe
     * serializer for these types. The result is the byte-for-byte payload sent on the wire.
     * Failure here means the SDK can no longer render its own model - unrecoverable, not
     * something the LLM can correct, so it surfaces as an uncorrectable runtime exception.
     */
    private static String serializeSdkObject(Object sdkObject) {
        try {
            return com.anthropic.core.ObjectMappers.jsonMapper().writeValueAsString(sdkObject);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncorrectableRuntimeLLMException(
                    "Audit serialization failed for Anthropic SDK object " + sdkObject.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

}