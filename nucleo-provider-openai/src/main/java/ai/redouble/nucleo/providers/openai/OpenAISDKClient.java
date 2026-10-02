/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.secrets.*;
import com.fasterxml.jackson.core.type.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.openai.client.*;
import com.openai.client.okhttp.*;
import com.openai.core.*;
import com.openai.core.http.*;
import com.openai.errors.*;
import com.openai.models.*;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.*;
import com.openai.models.responses.*;

import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * OpenAI itself, through OpenAI's own Java client, on whichever of OpenAI's two APIs the
 * provider that built it serves. The request is still the dialect's JSON from
 * {@link AbstractOpenAIChatClient#buildRequestJson} or {@link AbstractOpenAIChatClient#buildResponsesJson},
 * converted into the SDK's typed parameters through the SDK's own mapper, so the one request
 * shape is asserted once and carried by both transports; the response comes back typed, is read
 * for the envelope (the account's live rate limits, the request id), and is handed to the shared
 * body reader as JSON. Retries are the framework's ({@code maxRetries(0)}), as with every SDK
 * client here.
 *
 * <p>Failures arrive typed: a {@link RateLimitException} is a 429 (an
 * {@code insufficient_quota} error code among them is out of money, not busy), an
 * {@link InternalServerException} or any 5xx status a server error, an
 * {@link OpenAIIoException} an unreachable provider.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAISDKClient extends AbstractOpenAIChatClient {
    private static final TypeReference<List<ChatCompletionMessageParam>> MESSAGES = new TypeReference<>() {};
    private static final TypeReference<List<ChatCompletionTool>> TOOLS = new TypeReference<>() {};
    private static final TypeReference<List<ResponseInputItem>> INPUT_ITEMS = new TypeReference<>() {};
    private static final TypeReference<List<Tool>> RESPONSES_TOOLS = new TypeReference<>() {};
    private OpenAIClient client;
    private String apiKey;

    /** The key comes from the store on first use, so the request builder is exercisable without one. */
    public OpenAISDKClient(WireApi wireApi) {
        super(wireApi);
    }

    /** An explicit key, for a harness that has it in hand. */
    public OpenAISDKClient(WireApi wireApi, String apiKey) {
        super(wireApi);
        this.apiKey = apiKey;
    }

    /**
     * The SDK client, built on first use; overridable to supply a recording one, or to point
     * the same SDK at another host that serves the dialect ({@link AzureFoundryOpenAIClient}).
     */
    protected OpenAIClient client() {
        if (client == null) {
            if (apiKey == null) {
                apiKey = Secrets.configured().require(OpenAIProvider.SECRET_ID).secret();
            }
            client = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0).build();
        }
        return client;
    }

    /** Who answered, for a failure's message: OpenAI here, the hosting service in a subclass. */
    protected String serviceName() {
        return "OpenAI";
    }

    /** This client's dialect, on this transport. */
    @Override
    protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
        return switch (wireApi()) {
            case CHAT_COMPLETIONS -> chatSingle(request, prepared);
            case RESPONSES -> responsesSingle(request, prepared);
        };
    }

    private <T> LLMResponse<T> chatSingle(LLMRequest<T> request, PreparedConversation prepared) {
        LLMResponse<T> response = new LLMResponse<T>(request);
        ObjectNode requestJson = buildRequestJson(request, prepared);
        request.setInputJson(requestJson.toString());
        ChatCompletion completion;
        try (HttpResponseFor<ChatCompletion> raw = client().chat().completions().withRawResponse().create(toParams(requestJson))) {
            completion = raw.parse();
            captureEnvelope(response, raw.headers());
        }
        catch (OpenAIException e) {
            throw failed(e);
        }
        return finishResponse(response, request, ObjectMappers.jsonMapper().valueToTree(completion).toString());
    }

    private <T> LLMResponse<T> responsesSingle(LLMRequest<T> request, PreparedConversation prepared) {
        LLMResponse<T> response = new LLMResponse<T>(request);
        ObjectNode requestJson = buildResponsesJson(request, prepared);
        request.setInputJson(requestJson.toString());
        Response answer;
        try (HttpResponseFor<Response> raw = client().responses().withRawResponse().create(toResponsesParams(requestJson))) {
            answer = raw.parse();
            captureEnvelope(response, raw.headers());
        }
        catch (OpenAIException e) {
            throw failed(e);
        }
        return finishResponses(response, request, ObjectMappers.jsonMapper().valueToTree(answer).toString());
    }

    /** This client's dialect, streamed. */
    @Override
    protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response, PreparedConversation prepared, Consumer<StreamChunk> chunkHandler) {
        switch (wireApi()) {
            case CHAT_COMPLETIONS -> streamChat(request, response, prepared, chunkHandler);
            case RESPONSES -> streamResponses(request, response, prepared, chunkHandler);
        }
    }

    /**
     * The Responses request as a stream: every {@code output_text.delta} reaches the chunk
     * handler as it arrives, and the completed response the final event carries is read exactly
     * as a single response is, so the turn ends with the same blocks, usage and stop reason.
     * Every event is kept verbatim as the wire output, persisted in a finally so half a response
     * survives a broken stream. A stream that ends without a completed or incomplete response,
     * or with a failed one, is refused.
     */
    private <T> void streamResponses(LLMRequest<T> request, LLMResponse<T> response, PreparedConversation prepared, Consumer<StreamChunk> chunkHandler) {
        ObjectNode requestJson = buildResponsesJson(request, prepared);
        request.setInputJson(requestJson.toString());
        ArrayNode eventLog = ObjectMappers.jsonMapper().createArrayNode();
        Response[] finished = {null};
        String[] failure = {null};
        try (StreamResponse<ResponseStreamEvent> stream = client().responses().createStreaming(toResponsesParams(requestJson))) {
            stream.stream().forEach(event -> {
                eventLog.add(ObjectMappers.jsonMapper().valueToTree(event));
                event.outputTextDelta().ifPresent(delta -> chunkHandler.accept(StreamChunk.of(delta.delta())));
                event.completed().ifPresent(done -> finished[0] = done.response());
                event.incomplete().ifPresent(done -> finished[0] = done.response());
                event.failed().ifPresent(done -> failure[0] = done.response().error().map(ResponseError::message).orElse("the response failed"));
                event.error().ifPresent(error -> failure[0] = error.message());
            });
        }
        catch (OpenAIException e) {
            throw failed(e);
        }
        finally {
            request.setOutputJson(eventLog.toString());
        }
        chunkHandler.accept(StreamChunk.done());
        if (failure[0] != null) {
            throw new UncorrectableRuntimeLLMException(serviceName() + " failed the streamed response for " + getModel().getId() + ": " + failure[0]);
        }
        if (finished[0] == null) {
            throw new UncorrectableRuntimeLLMException(serviceName() + "'s stream for " + getModel().getId() + " ended without a completed response");
        }
        finishResponses(response, request, ObjectMappers.jsonMapper().valueToTree(finished[0]).toString());
    }

    /**
     * The Responses JSON as the SDK's typed parameters: the scalars through the builder, the
     * input items and the tools converted whole through the SDK's mapper, which validates the
     * shapes. Package-private so the conversion can be asserted without a transport.
     */
    static ResponseCreateParams toResponsesParams(ObjectNode requestJson) {
        ResponseCreateParams.Builder params = ResponseCreateParams.builder()
                .model(requestJson.get("model").asText())
                .maxOutputTokens(requestJson.get("max_output_tokens").asLong())
                .store(requestJson.path("store").asBoolean(false))
                .inputOfResponse(JsonValue.from(requestJson.get("input")).convert(INPUT_ITEMS));
        if (requestJson.has("temperature")) {
            params.temperature(requestJson.get("temperature").asDouble());
        }
        if (requestJson.has("instructions")) {
            params.instructions(requestJson.get("instructions").asText());
        }
        if (requestJson.has("reasoning")) {
            params.reasoning(Reasoning.builder().effort(ReasoningEffort.of(requestJson.get("reasoning").get("effort").asText())).build());
        }
        for (JsonNode include : requestJson.path("include")) {
            params.addInclude(ResponseIncludable.of(include.asText()));
        }
        if (requestJson.has("tools")) {
            params.tools(JsonValue.from(requestJson.get("tools")).convert(RESPONSES_TOOLS));
        }
        if (requestJson.has("text")) {
            params.text(JsonValue.from(requestJson.get("text")).convert(ResponseTextConfig.class));
        }
        return params.build();
    }

    /**
     * The SDK's failure as the framework's: the template classifies 429, quota and 5xx by
     * walking the cause chain, so the typed failure stays the cause, and what is left after
     * that classification - a 4xx, a body the SDK could not read - reaches a thinker as an
     * uncorrectable LLM-readable failure rather than as the transport library's own type.
     * The message is composed from what this process knows, the status and the model; the
     * provider's own words ride in the cause.
     */
    protected UncorrectableRuntimeLLMException failed(OpenAIException e) {
        String what = e instanceof OpenAIServiceException svc
                ? serviceName() + " answered HTTP " + svc.statusCode() + " for " + getModel().getId()
                : serviceName() + "'s response for " + getModel().getId() + " could not be read";
        return new UncorrectableRuntimeLLMException(what, e);
    }

    /**
     * The Chat Completions request as a stream of chunks: text deltas go to the chunk handler
     * as they arrive, tool-call deltas are gathered by index into whole calls (the id and name
     * arrive on the first delta of a call, the arguments in pieces), the usage block the final
     * chunk carries ({@code stream_options.include_usage}) becomes the response's usage, and the
     * finish reason of the last choice its stop reason. Every chunk is kept verbatim as the
     * wire output so the audit trail mirrors the SSE sequence, persisted in a finally so half
     * a response survives a broken stream. Rate-limit headers are not read on this path, as
     * on no streaming path.
     */
    private <T> void streamChat(LLMRequest<T> request, LLMResponse<T> response, PreparedConversation prepared, Consumer<StreamChunk> chunkHandler) {
        ObjectNode requestJson = buildRequestJson(request, prepared);
        request.setInputJson(requestJson.toString());
        ChatCompletionCreateParams params = toParams(requestJson).toBuilder()
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                .build();
        IncomingMessage<T> incoming = response.getResponseMessage();
        StringBuilder text = new StringBuilder();
        String[] finishReason = {null};
        String[] servedModel = {null};
        CompletionUsage[] usage = {null};
        SortedMap<Long, ObjectNode> calls = new TreeMap<>();
        ArrayNode eventLog = ObjectMappers.jsonMapper().createArrayNode();
        try (StreamResponse<ChatCompletionChunk> stream = client().chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> {
                eventLog.add(ObjectMappers.jsonMapper().valueToTree(chunk));
                if (incoming.getMessageId() == null) {
                    incoming.setMessageId(chunk.id());
                }
                servedModel[0] = chunk.model();
                chunk.usage().ifPresent(u -> usage[0] = u);
                for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                    choice.delta().content().ifPresent(content -> {
                        text.append(content);
                        chunkHandler.accept(StreamChunk.of(content));
                    });
                    for (ChatCompletionChunk.Choice.Delta.ToolCall delta : choice.delta().toolCalls().orElse(List.of())) {
                        gather(calls, delta);
                    }
                    choice.finishReason().ifPresent(reason -> finishReason[0] = reason.asString());
                }
            });
        }
        catch (OpenAIException e) {
            throw failed(e);
        }
        finally {
            request.setOutputJson(eventLog.toString());
        }
        chunkHandler.accept(StreamChunk.done());
        List<ContentBlocks.ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) {
            blocks.add(new ContentBlocks.TextBlock(text.toString()));
        }
        blocks.addAll(gathered(calls));
        incoming.setContentBlocks(blocks);
        incoming.overwriteRawContent(text.toString());
        response.setServedModelId(servedModel[0]);
        if (usage[0] != null) {
            int promptTokens = (int) usage[0].promptTokens();
            int completionTokens = (int) usage[0].completionTokens();
            Integer cachedTokens = usage[0].promptTokensDetails().flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                    .map(Long::intValue).orElse(null);
            response.setUsage(promptTokens, null, cachedTokens, completionTokens);
            incoming.setActualOutputTokens(completionTokens);
        }
        response.setStopReason(LLMStopReason.from(finishReason[0]));
    }

    /**
     * One tool-call delta folded into the call it belongs to, keyed by the call's index in the
     * turn: the id and the name are set by the delta that carries them, the arguments text is
     * appended piece by piece, so the gathered call has the same shape as a whole one.
     */
    static void gather(SortedMap<Long, ObjectNode> calls, ChatCompletionChunk.Choice.Delta.ToolCall delta) {
        ObjectNode call = calls.computeIfAbsent(delta.index(), index -> {
            ObjectNode started = ObjectMappers.jsonMapper().createObjectNode();
            started.putObject("function").put("arguments", "");
            return started;
        });
        delta.id().ifPresent(id -> call.put("id", id));
        delta.function().ifPresent(function -> {
            ObjectNode gathered = (ObjectNode) call.get("function");
            function.name().ifPresent(name -> gathered.put("name", name));
            function.arguments().ifPresent(arguments -> gathered.put("arguments", gathered.get("arguments").asText() + arguments));
        });
    }

    /** The gathered calls as the framework's blocks, in index order, once the stream has ended. */
    static List<ContentBlocks.ToolUseBlock> gathered(SortedMap<Long, ObjectNode> calls) {
        List<ContentBlocks.ToolUseBlock> blocks = new ArrayList<>();
        for (ObjectNode call : calls.values()) {
            blocks.add(toolUse(call.path("id").asText(null), call.path("function")));
        }
        return blocks;
    }

    /**
     * The dialect's JSON as the SDK's typed parameters: the scalar fields set through the
     * builder, the messages array converted whole through the SDK's mapper, which validates the
     * shape the encoders produced. Package-private so the conversion can be asserted without a
     * transport.
     */
    static ChatCompletionCreateParams toParams(ObjectNode requestJson) {
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(requestJson.get("model").asText())
                .maxCompletionTokens(requestJson.get("max_completion_tokens").asLong())
                .messages(JsonValue.from(requestJson.get("messages")).convert(MESSAGES));
        if (requestJson.has("temperature")) {
            params.temperature(requestJson.get("temperature").asDouble());
        }
        if (requestJson.has("reasoning_effort")) {
            params.reasoningEffort(ReasoningEffort.of(requestJson.get("reasoning_effort").asText()));
        }
        if (requestJson.has("tools")) {
            params.tools(JsonValue.from(requestJson.get("tools")).convert(TOOLS));
        }
        if (requestJson.has("response_format")) {
            params.responseFormat(JsonValue.from(requestJson.get("response_format")).convert(ChatCompletionCreateParams.ResponseFormat.class));
        }
        return params.build();
    }

    /**
     * Records the provider envelope: all response headers verbatim, the provider's request
     * id, and the account's live per-model rate limits from the x-ratelimit-* family, feeding
     * the pre-emptive limiter updates.
     */
    void captureEnvelope(LLMResponse<?> response, Headers headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (String name : headers.names()) {
            List<String> values = headers.values(name);
            if (!values.isEmpty()) {
                flat.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        }
        recordEnvelope(response, flat);
    }

    @Override
    protected boolean is429Error(Exception e) {
        return OpenAISDKFailures.is429(e);
    }

    @Override
    protected boolean isQuotaError(Exception e) {
        return OpenAISDKFailures.isQuota(e);
    }

    @Override
    protected String accountIdentifier() {
        return OpenAIProvider.ACCOUNT;
    }

    @Override
    protected boolean isServerError(Exception e) {
        return OpenAISDKFailures.isServerError(e);
    }

    /** The error-path counterpart of {@link #captureEnvelope}: the same headers, off the typed failure. */
    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        OpenAIServiceException svc = OpenAISDKFailures.serviceException(e);
        if (svc == null) {
            return null;
        }
        Headers headers = svc.headers();
        Integer tokensLimit = RateLimitInfo.parseIntOrNull(first(headers, "x-ratelimit-limit-tokens"));
        Integer tokensRemaining = RateLimitInfo.parseIntOrNull(first(headers, "x-ratelimit-remaining-tokens"));
        Integer requestsLimit = RateLimitInfo.parseIntOrNull(first(headers, "x-ratelimit-limit-requests"));
        Integer requestsRemaining = RateLimitInfo.parseIntOrNull(first(headers, "x-ratelimit-remaining-requests"));
        Integer retryAfterSeconds = RateLimitInfo.parseIntOrNull(first(headers, "retry-after"));
        return new RateLimitInfo(getModel() != null ? getModel().getId() : null, tokensLimit, tokensRemaining, null,
                requestsLimit, requestsRemaining, retryAfterSeconds != null ? Duration.ofSeconds(retryAfterSeconds) : null,
                svc instanceof RateLimitException);
    }

    private static String first(Headers headers, String name) {
        List<String> values = headers.values(name);
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
