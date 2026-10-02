/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;

/**
 * Everything about an OpenAI-family conversation that is the dialect's and not the wire's:
 * the content encoders, the request body as JSON, the response body read back onto the
 * framework's response, for both of OpenAI's APIs. Chat Completions ({@link #buildRequestJson},
 * {@link #finishResponse}) and Responses ({@link #buildResponsesJson}, {@link #finishResponses})
 * are two shapes of one contract, and which one a client speaks is its {@link WireApi}, the
 * surface its provider serves, the way Bedrock's surfaces are providers. Two transports carry either -
 * {@link OpenAISDKClient} through OpenAI's own client, {@link OpenAICompatibleClient} through
 * the framework's HTTP client for the endpoints that speak the shape without being OpenAI - and
 * the shapes are asserted once here, on the JSON, for both.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public abstract class AbstractOpenAIChatClient extends AbstractLLMClient<ObjectNode> {
    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAIChatClient.class);
    private final WireApi wireApi;
    private ModelSpec modelInfo;

    /** The dialect this client speaks, the provider's choice; a client without one has no shape to send. */
    protected AbstractOpenAIChatClient(WireApi wireApi) {
        if (wireApi == null) {
            throw new IllegalArgumentException("An OpenAI-family client speaks CHAT_COMPLETIONS or RESPONSES; null names neither");
        }
        this.wireApi = wireApi;
        this.temperature = 1.0;
        this.formatter = createFormatter();
    }

    @Override
    protected ContentFormatter createFormatter() {
        return new OpenAIContentFormatter();
    }

    /**
     * Records the provider envelope from the response headers, flattened by the transport to
     * lowercase names and the first value each: every header verbatim, the provider's request
     * id, and the account's live per-model rate limits from the {@code x-ratelimit-*} family
     * when the endpoint sends them, feeding the pre-emptive limiter updates.
     */
    protected void recordEnvelope(LLMResponse<?> response, Map<String, String> headers) {
        response.setProviderHeaders(headers);
        response.setProviderRequestId(headers.get("x-request-id"));
        Integer tokensLimit = RateLimitInfo.parseIntOrNull(headers.get("x-ratelimit-limit-tokens"));
        Integer tokensRemaining = RateLimitInfo.parseIntOrNull(headers.get("x-ratelimit-remaining-tokens"));
        Integer requestsLimit = RateLimitInfo.parseIntOrNull(headers.get("x-ratelimit-limit-requests"));
        Integer requestsRemaining = RateLimitInfo.parseIntOrNull(headers.get("x-ratelimit-remaining-requests"));
        if (tokensLimit != null || requestsLimit != null) {
            response.setRateLimitInfo(new RateLimitInfo(getModel().getId(),
                    tokensLimit, tokensRemaining, null, requestsLimit, requestsRemaining, null, false));
        }
    }

    @Override
    public APIDialect getDialect() {
        return APIDialect.OPENAI;
    }

    @Override
    protected TextWrapper<ObjectNode> textWrapper() {
        return new OpenAITextWrapper();
    }

    @Override
    protected Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<ObjectNode>>> buildEncoders() {
        Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<ObjectNode>>> m = super.buildEncoders();
        m.put(ImageBlock.class, OpenAIImageBlockEncoder.class);
        m.put(FileBlock.class, OpenAIFileBlockEncoder.class);
        // Suppressed inline because buildRequestJson sweeps the same blocks into the native
        // tools parameter - the suppression and the collection are this one client's pair
        m.put(ToolDefinitionBlock.class, OpenAIToolDefinitionBlockEncoder.class);
        // Encoded as message structure rather than content: buildRequestJson routes a call into
        // the assistant message's tool_calls and a result into a role:tool message of its own
        m.put(ToolUseBlock.class, OpenAIToolUseBlockEncoder.class);
        m.put(ToolResultBlock.class, OpenAIToolResultBlockEncoder.class);
        return m;
    }

    /**
     * Assembles the wire request from the prepared conversation: the Chat Completions body
     * as JSON. Package-private so the mapping can be asserted without a transport.
     */
    protected ObjectNode buildRequestJson(LLMRequest<?> request, PreparedConversation prepared) {
        ObjectNode requestJson = NucleoJsonSerializer.createObjectNode();
        requestJson.put("model", this.modelInfo.getWireModelId());
        // Output budget must match what JobResources reserved locally (resolveWireMaxTokens, the
        // single source also used for the response's truncation ceiling). The field is
        // max_completion_tokens: every model in the catalog is of the reasoning generation,
        // which rejects the legacy max_tokens name outright ("Unsupported parameter").
        requestJson.put("max_completion_tokens", resolveWireMaxTokens(request.getContext()));
        // The reasoning generation reasons inside max_completion_tokens; resolveWireMaxTokens adds
        // the entry's thinking budget as headroom above the declared answer, and the call's depth
        // sets how much of it the model spends. Left unset the provider defaults to medium.
        ConversationContext context = request.getContext();
        if (context.getModel().getThinkingMode() == ThinkingMode.REASONING_EFFORT) {
            requestJson.put("reasoning_effort", reasoningEffortFor(context.resolveDepth()));
        }
        if (temperature != null) {
            requestJson.put("temperature", this.temperature);
        }
        // The answer's declared shape, held natively: a handler that describes its answer as a
        // schema is asking for one JSON object, and the dialect can bind the model to that. The
        // instruction alone is not enough here - a model whose system prompt asks for terse
        // answers replies with the bare value, which is valid JSON and not the object, and no
        // correction moves it. A handler that reads plain text gets no format and text comes back.
        if (context.getLastOutgoingMessage().getResponseHandler().usesSchemaNotation()) {
            requestJson.putObject("response_format").put("type", "json_object");
        }
        // This provider has no separate system channel: system content is expressed as
        // role:system messages at the front of the array. Admitted skills lead, then the
        // conversation's system content - the main objective, already rendered by the
        // shared pipeline. OpenAI auto-caches stable prefixes, which is what its cache
        // hit rate keys on.
        ArrayNode messages = NucleoJsonSerializer.createArrayNode();
        for (Skill skill : context.getLoadedSkills()) {
            ObjectNode skillMsg = NucleoJsonSerializer.createObjectNode();
            skillMsg.put("role", "system");
            skillMsg.put("content", Skill.renderForSystemPrompt(skill));
            messages.add(skillMsg);
        }
        if (prepared.hasSystemText()) {
            ObjectNode systemMsg = NucleoJsonSerializer.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", prepared.systemText());
            messages.add(systemMsg);
        }
        // Every definition the request carries - palette and mid-conversation announcements -
        // goes through the native tools parameter; this client's own encoder suppresses the
        // inline form, so the two halves of that decision live in one class
        List<ToolDefinitionBlock> toolDefinitions = prepared.allToolDefinitions();
        if (!toolDefinitions.isEmpty()) {
            ArrayNode tools = requestJson.putArray("tools");
            for (ToolDefinitionBlock definition : toolDefinitions) {
                tools.add(toolParameter(definition));
            }
            // A model the catalog declares tools_suspend_reasoning refuses tools and a reasoning
            // effort together ("400 Function tools with reasoning_effort are not supported ... set
            // reasoning_effort to 'none'" - the GPT-5.6 family, on OpenAI and on Foundry alike),
            // so a turn carrying tools goes out at none and the depth applies on the other turns
            if (context.getModel().toolsSuspendReasoning() && requestJson.has("reasoning_effort")) {
                requestJson.put("reasoning_effort", "none");
            }
        }
        // Render each turn, in conversation order, after the system prefix. A tool call and a
        // tool result are message structure on this dialect: a call sits in the assistant
        // message's tool_calls, a result is a role:tool message that must directly follow the
        // assistant message it answers, ahead of whatever else the turn says
        for (ProcessedMessageData msg : prepared.turns()) {
            ArrayNode elements = NucleoJsonSerializer.createArrayNode();
            ArrayNode toolCalls = NucleoJsonSerializer.createArrayNode();
            List<ObjectNode> toolMessages = new ArrayList<>();
            boolean allText = true;
            for (ContentBlock block : msg.contentBlocks()) {
                ObjectNode encoded = encodeBlock(block);
                if (encoded == null) {
                    continue;
                }
                switch (block) {
                    case ToolUseBlock ignored -> toolCalls.add(encoded);
                    case ToolResultBlock ignored -> toolMessages.add(encoded);
                    default -> {
                        elements.add(encoded);
                        allText &= "text".equals(encoded.path("type").asText());
                    }
                }
            }
            // A turn of text elements alone collapses into the single content string; any
            // native element (an image) keeps the array form
            String text = allText ? joinText(elements) : null;
            switch (msg.role()) {
                case USER -> {
                    messages.addAll(toolMessages);
                    if (!elements.isEmpty()) {
                        ObjectNode msgJson = NucleoJsonSerializer.createObjectNode();
                        msgJson.put("role", "user");
                        if (allText) {
                            msgJson.put("content", text);
                        }
                        else {
                            msgJson.set("content", elements);
                        }
                        messages.add(msgJson);
                    }
                }
                case ASSISTANT -> {
                    ObjectNode msgJson = NucleoJsonSerializer.createObjectNode();
                    msgJson.put("role", "assistant");
                    // An assistant turn carries text; the dialect takes it as one string, and
                    // a turn that is only calls has no content at all
                    if (!allText) {
                        msgJson.set("content", elements);
                    }
                    else if (toolCalls.isEmpty() || !text.isEmpty()) {
                        msgJson.put("content", text);
                    }
                    if (!toolCalls.isEmpty()) {
                        msgJson.set("tool_calls", toolCalls);
                    }
                    messages.add(msgJson);
                }
            }
        }
        requestJson.set("messages", messages);
        return requestJson;
    }

    /**
     * One entry of the {@code tools} parameter: a function with the definition's name,
     * description and its whole JSON Schema as the parameters, so a {@code $defs} for a
     * recursive type reaches the model with something to point at.
     */
    private static ObjectNode toolParameter(ToolDefinitionBlock definition) {
        ObjectNode tool = NucleoJsonSerializer.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", definition.name());
        function.put("description", definition.description());
        if (definition.schemaJson() != null && !definition.schemaJson().isEmpty()) {
            try {
                function.set("parameters", NucleoJsonSerializer.readTree(definition.schemaJson()));
            }
            catch (IOException e) {
                // Our own tool definition, so a failure here is a defect in this process rather
                // than anything the model authored
                throw new UncorrectableRuntimeLLMException("Failed to convert tool definition: " + definition.name(), e);
            }
        }
        return tool;
    }

    private static String joinText(ArrayNode textElements) {
        StringBuilder text = new StringBuilder();
        for (JsonNode element : textElements) {
            text.append(element.get("text").asText());
        }
        return text.toString();
    }

    /**
     * Reads the provider's response body onto the response object. Package-private so the
     * mapping can be asserted without a transport.
     */
    <T> LLMResponse<T> finishResponse(LLMResponse<T> response, LLMRequest<T> request, String responseStr) {
        request.setOutputJson(responseStr);
        JsonNode responseJson;
        try {
            responseJson = NucleoJsonSerializer.readTree(responseStr);
        }
        catch (IOException e) {
            // A body this process cannot parse is the provider's fault and nothing the model can
            // fix, so it leaves as uncorrectable. The body itself stays out of the message and
            // remains on the request's outputJson for the audit trail.
            throw new UncorrectableRuntimeLLMException("OpenAI response is not valid JSON", e);
        }
        // An empty body parses to nothing at all rather than failing to parse; it is no more an answer
        if (responseJson.isMissingNode()) {
            throw new UncorrectableRuntimeLLMException("OpenAI response is not valid JSON: the body is empty");
        }
        IncomingMessage<T> incomingMessage = response.getResponseMessage();
        incomingMessage.setMessageId(responseJson.path("id").asText(""));
        // The served-model echo: an alias like gpt-5.6 answers with its snapshot
        response.setServedModelId(responseJson.path("model").asText(null));
        // OpenAI reports prompt_tokens as the grand total; prompt_tokens_details.cached_tokens
        // is a subset of prompt_tokens (unlike Anthropic's additive shape). There is no
        // cache-creation concept on this provider; caching is automatic and first-call has
        // no overhead.
        JsonNode usage = responseJson.path("usage");
        if (usage.isObject()) {
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            JsonNode promptDetails = usage.path("prompt_tokens_details");
            Integer cachedTokens = promptDetails.has("cached_tokens")
                    ? promptDetails.path("cached_tokens").asInt() : null;
            response.setUsage(promptTokens, null, cachedTokens, completionTokens);
            incomingMessage.setActualOutputTokens(completionTokens);
        }
        // Stop reason is provider-derived; the abstract client's template records the requested
        // ceiling (resolveWireMaxTokens) and centralizes the truncation decision.
        // finish_reason "length" normalizes to MAX_TOKENS.
        // Strict about the missing: a 200 whose body has no choice carrying a message is not an
        // answer - an error object some gateways send with a 200, an empty choices array, a
        // scalar - and is refused rather than read as a successful empty turn the thinker would
        // then try to correct
        JsonNode message = responseJson.path("choices").path(0).path("message");
        if (!message.isObject()) {
            throw new UncorrectableRuntimeLLMException("OpenAI's response carries no choice with a message");
        }
        response.setStopReason(LLMStopReason.from(responseJson.path("choices").path(0).path("finish_reason").asText(null)));
        String text = contentText(message.path("content"));
        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) {
            blocks.add(new TextBlock(text));
        }
        for (JsonNode call : message.path("tool_calls")) {
            blocks.add(toolUse(call.path("id").asText(null), call.path("function")));
        }
        incomingMessage.setContentBlocks(blocks);
        incomingMessage.overwriteRawContent(text);
        // The dialect-specific response detail; the template logs the call's one-line summary
        if (!text.isEmpty()) {
            log.debug("Text:\n{}", text);
        }
        return response;
    }

    /**
     * The message's content as text: a string, as OpenAI answers, or an array of typed parts
     * whose text parts are joined in order, as several endpoints that speak the dialect
     * without being OpenAI answer. Anything else - null for a turn that is only calls - is empty.
     */
    static String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText(null))) {
                    text.append(part.path("text").asText(""));
                }
            }
        }
        return text.toString();
    }

    /**
     * A native tool call as the framework's block: the provider's call id, which the result
     * is recorded against and which the assistant turn echoes back on the next request, the
     * function's name, and its arguments as the JSON text the model wrote. A call without an
     * id cannot be answered and one without a name cannot be run - a call of another type than
     * a function has neither, since the runtime declares only functions - so either is refused
     * here rather than handed on with a blank.
     */
    static ToolUseBlock toolUse(String callId, JsonNode function) {
        if (callId == null || callId.isBlank()) {
            throw new UncorrectableRuntimeLLMException("OpenAI's response carries a tool call without an id");
        }
        String name = function.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new UncorrectableRuntimeLLMException("OpenAI's response carries a tool call without a function name");
        }
        ToolUseBlock block = new ToolUseBlock(callId, name, function.path("arguments").asText());
        log.debug("Tool call: {} [{}] {}", block.toolName(), block.toolUseId(), block.inputJson());
        return block;
    }

    /** Which of the two dialects this client speaks: the surface its provider serves. */
    public WireApi wireApi() {
        return wireApi;
    }

    // ---- the Responses dialect ----

    /**
     * Assembles the Responses request from the prepared conversation, as JSON. Package-private
     * so the mapping can be asserted without a transport. What differs from the Chat Completions
     * body is the shape, not the contract:
     * <ul>
     *   <li>The system channel is {@code instructions}: the admitted skills, then the
     *       conversation's system content, as the shared pipeline rendered it.</li>
     *   <li>The turns are {@code input} items in conversation order. A user turn's text and
     *       images are one {@code message} of {@code input_text} and {@code input_image} parts,
     *       its tool results {@code function_call_output} items ahead of it; an assistant turn's
     *       reasoning is replayed as the {@code reasoning} items the model produced, its text a
     *       {@code message} of {@code output_text}, its calls {@code function_call} items, in the
     *       order the model produced them.</li>
     *   <li>Tools are flat functions with the definition's whole schema as the parameters and
     *       {@code strict} off, since the runtime's schemas are not written for strict mode.</li>
     *   <li>Reasoning rides {@code reasoning.effort} from the seat's depth, beside the tools, and
     *       the response is not stored server-side ({@code store: false}): the conversation is the
     *       runtime's, persisted as JSON, so the model's reasoning comes back encrypted
     *       ({@code include: reasoning.encrypted_content}) as a {@link RedactedThinkingBlock}
     *       holding the whole item, and goes back verbatim on the next turn, which is what lets
     *       the model continue a tool loop with its reasoning intact.</li>
     *   <li>The answer's declared shape is {@code text.format: json_object} for a schema handler.</li>
     * </ul>
     */
    ObjectNode buildResponsesJson(LLMRequest<?> request, PreparedConversation prepared) {
        ConversationContext context = request.getContext();
        ObjectNode requestJson = NucleoJsonSerializer.createObjectNode();
        requestJson.put("model", this.modelInfo.getWireModelId());
        // The same ceiling JobResources reserved: the declared answer plus the entry's reasoning
        // headroom, since the reasoning generation reasons inside the output budget
        requestJson.put("max_output_tokens", resolveWireMaxTokens(context));
        if (temperature != null) {
            requestJson.put("temperature", this.temperature);
        }
        // The conversation is the runtime's: nothing is stored server-side, and the model's
        // reasoning comes back encrypted so it can go back verbatim on the next turn
        requestJson.put("store", false);
        if (context.getModel().getThinkingMode() == ThinkingMode.REASONING_EFFORT) {
            requestJson.putObject("reasoning").put("effort", reasoningEffortFor(context.resolveDepth()));
            requestJson.putArray("include").add("reasoning.encrypted_content");
        }
        if (context.getLastOutgoingMessage().getResponseHandler().usesSchemaNotation()) {
            requestJson.putObject("text").putObject("format").put("type", "json_object");
        }
        StringBuilder instructions = new StringBuilder();
        for (Skill skill : context.getLoadedSkills()) {
            instructions.append(Skill.renderForSystemPrompt(skill)).append("\n\n");
        }
        if (prepared.hasSystemText()) {
            instructions.append(prepared.systemText());
        }
        if (!instructions.isEmpty()) {
            requestJson.put("instructions", instructions.toString().strip());
        }
        List<ToolDefinitionBlock> toolDefinitions = prepared.allToolDefinitions();
        if (!toolDefinitions.isEmpty()) {
            ArrayNode tools = requestJson.putArray("tools");
            for (ToolDefinitionBlock definition : toolDefinitions) {
                tools.add(functionTool(definition));
            }
        }
        ArrayNode input = requestJson.putArray("input");
        for (ProcessedMessageData msg : prepared.turns()) {
            String role = msg.role() == TurnRole.USER ? "user" : "assistant";
            String textType = msg.role() == TurnRole.USER ? "input_text" : "output_text";
            ArrayNode parts = NucleoJsonSerializer.createArrayNode();
            for (ContentBlock block : msg.contentBlocks()) {
                switch (block) {
                    case ToolUseBlock use -> {
                        flushMessage(input, role, parts);
                        ObjectNode call = input.addObject();
                        call.put("type", "function_call");
                        call.put("call_id", use.toolUseId());
                        call.put("name", use.toolName());
                        call.put("arguments", use.inputJson());
                    }
                    case ToolResultBlock result -> {
                        // A result answers a call by its id; it precedes the turn's own content,
                        // as the tool message does on the other dialect
                        ObjectNode output = input.addObject();
                        output.put("type", "function_call_output");
                        output.put("call_id", result.toolUseId());
                        output.put("output", result.resultJson());
                    }
                    case RedactedThinkingBlock reasoning -> {
                        flushMessage(input, role, parts);
                        input.add(reasoningItem(reasoning));
                    }
                    default -> {
                        ObjectNode encoded = encodeBlock(block);
                        if (encoded != null) {
                            parts.add(responsesPart(encoded, textType));
                        }
                    }
                }
            }
            flushMessage(input, role, parts);
        }
        return requestJson;
    }

    /** The parts gathered so far as one message item of the turn's role; nothing when there are none. */
    private static void flushMessage(ArrayNode input, String role, ArrayNode parts) {
        if (parts.isEmpty()) {
            return;
        }
        ObjectNode message = input.addObject();
        message.put("type", "message");
        message.put("role", role);
        message.set("content", parts.deepCopy());
        parts.removeAll();
    }

    /**
     * A content element of the Chat Completions dialect as a Responses part: a {@code text}
     * element becomes {@code input_text} or {@code output_text} by the turn's role, an
     * {@code image_url} element an {@code input_image} carrying the same data URL.
     */
    private static ObjectNode responsesPart(ObjectNode encoded, String textType) {
        ObjectNode part = NucleoJsonSerializer.createObjectNode();
        if ("image_url".equals(encoded.path("type").asText(""))) {
            part.put("type", "input_image");
            part.put("image_url", encoded.path("image_url").path("url").asText());
            part.put("detail", encoded.path("image_url").path("detail").asText("auto"));
            return part;
        }
        part.put("type", textType);
        part.put("text", encoded.path("text").asText(""));
        return part;
    }

    /** A reasoning item the model produced, replayed verbatim from the block that kept it. */
    private static JsonNode reasoningItem(RedactedThinkingBlock block) {
        try {
            return NucleoJsonSerializer.readTree(block.data());
        }
        catch (IOException e) {
            // The block was written by this dialect from the provider's own item, so a payload
            // that does not parse is a defect of this process
            throw new UncorrectableRuntimeLLMException("A persisted reasoning item does not parse", e);
        }
    }

    /**
     * One entry of the Responses {@code tools} parameter: a flat function with the definition's
     * name, description and its whole JSON Schema as the parameters, strict mode off because the
     * runtime's schemas carry optional fields and nested types strict mode refuses.
     */
    private static ObjectNode functionTool(ToolDefinitionBlock definition) {
        ObjectNode tool = NucleoJsonSerializer.createObjectNode();
        tool.put("type", "function");
        tool.put("name", definition.name());
        tool.put("description", definition.description());
        tool.put("strict", false);
        if (definition.schemaJson() != null && !definition.schemaJson().isEmpty()) {
            try {
                tool.set("parameters", NucleoJsonSerializer.readTree(definition.schemaJson()));
            }
            catch (IOException e) {
                throw new UncorrectableRuntimeLLMException("Failed to convert tool definition: " + definition.name(), e);
            }
        }
        return tool;
    }

    /**
     * Reads a Responses body onto the response object. Package-private so the mapping can be
     * asserted without a transport. The {@code output} items become the framework's blocks in
     * order: a {@code message}'s {@code output_text} parts joined as the text, a
     * {@code function_call} a {@link ToolUseBlock} under the provider's {@code call_id}, a
     * {@code reasoning} item a {@link RedactedThinkingBlock} holding the whole item.
     * {@code status: incomplete} with {@code max_output_tokens} is the truncation the template
     * escalates; a {@code failed} response is refused with its error; a body without an
     * {@code output} array is not an answer. Usage is {@code input_tokens} as the grand total,
     * {@code input_tokens_details.cached_tokens} the cached subset, {@code output_tokens} the
     * output, reasoning included.
     */
    <T> LLMResponse<T> finishResponses(LLMResponse<T> response, LLMRequest<T> request, String responseStr) {
        request.setOutputJson(responseStr);
        JsonNode responseJson;
        try {
            responseJson = NucleoJsonSerializer.readTree(responseStr);
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException("OpenAI response is not valid JSON", e);
        }
        if (responseJson.isMissingNode()) {
            throw new UncorrectableRuntimeLLMException("OpenAI response is not valid JSON: the body is empty");
        }
        JsonNode output = responseJson.path("output");
        if (!output.isArray()) {
            throw new UncorrectableRuntimeLLMException("OpenAI's response carries no output");
        }
        String status = responseJson.path("status").asText("completed");
        if ("failed".equals(status)) {
            throw new UncorrectableRuntimeLLMException("OpenAI failed the response for " + getModel().getId() + ": "
                    + responseJson.path("error").path("message").asText("no error message"));
        }
        IncomingMessage<T> incoming = response.getResponseMessage();
        incoming.setMessageId(responseJson.path("id").asText(""));
        response.setServedModelId(responseJson.path("model").asText(null));
        JsonNode usage = responseJson.path("usage");
        if (usage.isObject()) {
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            JsonNode inputDetails = usage.path("input_tokens_details");
            Integer cachedTokens = inputDetails.has("cached_tokens") ? inputDetails.path("cached_tokens").asInt() : null;
            response.setUsage(inputTokens, null, cachedTokens, outputTokens);
            incoming.setActualOutputTokens(outputTokens);
        }
        StringBuilder text = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();
        boolean refused = false;
        for (JsonNode item : output) {
            switch (item.path("type").asText("")) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        String type = part.path("type").asText("");
                        if ("output_text".equals(type)) {
                            text.append(part.path("text").asText(""));
                        }
                        else if ("refusal".equals(type)) {
                            refused = true;
                            text.append(part.path("refusal").asText(""));
                        }
                    }
                }
                case "function_call" -> {
                    ObjectNode function = NucleoJsonSerializer.createObjectNode();
                    function.put("name", item.path("name").asText(null));
                    function.put("arguments", item.path("arguments").asText(""));
                    blocks.add(toolUse(item.path("call_id").asText(null), function));
                }
                case "reasoning" -> {
                    // The whole item, encrypted content included, so the next turn replays it as
                    // the model produced it; the summary is the readable part, where one was asked
                    blocks.add(new RedactedThinkingBlock(item.toString()));
                    for (JsonNode summary : item.path("summary")) {
                        String summaryText = summary.path("text").asText("");
                        if (!summaryText.isEmpty()) {
                            log.debug("Reasoning:\n{}", summaryText);
                        }
                    }
                }
                default -> log.info("Output item of type {} has no seat in the runtime and is left out", item.path("type").asText(""));
            }
        }
        if (!text.isEmpty()) {
            blocks.add(0, new TextBlock(text.toString()));
        }
        incoming.setContentBlocks(blocks);
        incoming.overwriteRawContent(text.toString());
        response.setStopReason(responsesStopReason(responseJson, status, refused, blocks));
        if (!text.isEmpty()) {
            log.debug("Text:\n{}", text);
        }
        return response;
    }

    /**
     * The normalized stop reason of a Responses body: an incomplete response stopped by the
     * output budget is the truncation the template escalates, a refusal or a filtered stop is
     * content filtering, a completed turn that carries calls is a tool stop, anything else the
     * end of the turn.
     */
    private static LLMStopReason responsesStopReason(JsonNode responseJson, String status, boolean refused, List<ContentBlock> blocks) {
        if ("incomplete".equals(status)) {
            String reason = responseJson.path("incomplete_details").path("reason").asText("");
            return "max_output_tokens".equals(reason) ? LLMStopReason.MAX_TOKENS : LLMStopReason.from(reason);
        }
        if (refused) {
            return LLMStopReason.CONTENT_FILTERED;
        }
        return blocks.stream().anyMatch(block -> block instanceof ToolUseBlock) ? LLMStopReason.TOOL_USE : LLMStopReason.END_TURN;
    }

    /** OpenAI has no dedicated overload status; capacity pressure arrives as 429/503, which ride their own paths. */
    @Override
    protected boolean isOverloadError(Exception e) {
        return false;
    }

    @Override
    public void setModel(ModelSpec model) {
        this.modelInfo = model;
        this.model = model;
    }

    @Override
    public ModelSpec getModel() {
        return this.modelInfo;
    }
}
