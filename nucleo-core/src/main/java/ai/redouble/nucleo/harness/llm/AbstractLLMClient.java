/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.util.*;
import org.slf4j.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Base class for LLM client implementations.
 *
 * <p>Provides model-agnostic interface for various providers (OpenAI, Anthropic, etc.).
 * Rate limiting is handled by {@link AbstractRateLimitedClient}.
 *
 * <p>{@code B} is the provider's native content-block type (Anthropic {@code ContentBlockParam},
 * OpenAI {@code JSONObject}, Bedrock {@code ContentBlock}). Per-block encoding lives in the
 * {@link ai.redouble.nucleo.harness.llm.encode} classes: {@link #buildEncoders()} maps each
 * {@link ContentBlocks.ContentBlock} type to a {@link BlockEncoder} class, and a subclass overwrites
 * only the blocks it renders natively. {@link #textWrapper()} supplies the provider's string-to-native
 * text wrap, injected into each encoder. A provider's native encoder class is typed to its {@code B},
 * so registering one for the wrong provider is a compile error.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-06-13)
 */
public abstract class AbstractLLMClient<B> extends AbstractRateLimitedClient implements LLMClient {
    private static final Logger log = LoggerFactory.getLogger(AbstractLLMClient.class);
    /**
     * Sampling temperature, sent only when a caller set one. Null - the default - sends
     * nothing, and the provider's own default applies; volunteering a value would also
     * fail outright on the models that accept no sampling parameter at all. A caller who
     * sets one on such a model gets the provider's refusal back, properly typed - asking
     * for what the model cannot do is the caller's to hear about, never to be silently
     * repaired.
     */
    protected Double temperature;
    protected ContentFormatter formatter;
    private final Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<B>>> encoderClasses = buildEncoders();
    private final Map<Class<? extends ContentBlock>, BlockEncoder<B>> encoderCache = new ConcurrentHashMap<>();

    @Override
    public Double getTemperature() {
        return temperature;
    }

    @Override
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    @Override
    public String getModelIdentifier() {
        return model != null ? model.getId() : null;
    }


    @Override
    public final <T> LLMResponse<T> singleResponse(LLMRequest<T> request) throws TokenEstimateExceedsLimitException {
        int estimatedTokens = estimateTokens(request);
        validateEstimatedTokens(estimatedTokens);

        // If estimate is above 75% of context window, try exact count from provider
        if (model != null) {
            int maxContext = model.getMaxContextTokens();
            if (estimatedTokens > (int)(maxContext * 0.75)) {
                int exactTokens = countTokensExact(request);
                if (exactTokens > 0) {
                    log.info("Exact token count: {} (estimate was {}, limit {})",
                            Formats.compactNumber(exactTokens),
                            Formats.compactNumber(estimatedTokens),
                            Formats.compactNumber(maxContext));
                    validateEstimatedTokens(exactTokens);
                }
            }
        }

        PreparedConversation prepared = prepareConversation(request.getContext());
        try {
            LLMResponse<T> response = executeWithRateLimit(new RateLimitedOperation<>() {
                @Override
                public LLMResponse<T> execute() {
                    try {
                        ConversationContext ctx = request.getContext();
                        String callerInfo = getCallerInfo(ctx);
                        // Log the dialect id, not the providerKey: several dialects deliberately
                        // share a providerKey so billing multipliers and the recorded provider stay
                        // stable (bedrock-anthropic and bedrock-mantle are both BEDROCK, azure and
                        // direct OpenAI are both OPENAI), which would make the log unable to say
                        // which endpoint actually served the call.
                        log.debug("===== SENDING TO {} {} | {} tokens{} =====", getDialect().id(), model, Formats.compactNumber(ctx.getTotalTokens(model)), callerInfo);
                        long sentAt = System.nanoTime();
                        LLMResponse<T> resp = doSingleResponse(request, prepared);
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAt);
                        // Stamped here, in the template, so every client's successful rows carry a
                        // real latency - the failure paths and the streaming template already stamp
                        // theirs, and a client-by-client convention left every single-path success
                        // with a null end time fleet-wide.
                        resp.setEndTime(java.time.Instant.now());
                        // The response identifies the model by OUR catalog id, never the name the
                        // provider echoes back: the echo collapses variants (mantle, direct, and
                        // bedrock Haiku all echo the same string; Converse echoes nothing), and
                        // the call record must show which spec we called - on successes and failures alike -
                        // or a per-variant query silently misattributes traffic.
                        resp.setModel(getModelIdentifier());
                        resp.setProvider(getDialect().providerKey());
                        resp.setRequestedMaxTokens(resolveWireMaxTokens(ctx));
                        resp.setSuccessful(true);
                        // One line per call, naming the job that asked (the id its lifecycle lines
                        // carry), the model and the endpoint that answered, and what the call cost;
                        // the provider's payload detail below it is DEBUG
                        log.info("{} <- {} via {}: {} in, {} cached, {} out, {} ms, {}", callerJobId(ctx), getModelIdentifier(), getDialect().id(),
                                tokens(resp.getActualInputTokens()), tokens(resp.getCacheReadInputTokens()), tokens(resp.getActualOutputTokens()),
                                elapsedMs, resp.getStopReason());
                        if (resp.getRateLimitInfo() != null) {
                            updateRateLimitsFromHeaders(resp.getRateLimitInfo());
                        }
                        return resp;
                    }
                    catch (RuntimeException e) {
                        throw classifyUpstreamFailure(e);
                    }
                }
            });
            throwIfRefused(response, request);
            throwIfTruncated(response, request);
            // Commit the response to the conversation only after it clears the refusal and
            // truncation gates, so a refused/truncated/abandoned response never lingers in the
            // context and ObservableLLMClient's failure path can still rebuild from a clean
            // [.., outgoing] tail.
            request.getContext().getMessages().add(response.getResponseMessage());
            return response;
        }
        catch (RuntimeException e) {
            if (model != null && isPromptTooLongError(e)) {
                log.warn("API rejected prompt as too long (estimate was {} for {})", Formats.compactNumber(estimatedTokens), model.getId());
                throw new TokenEstimateExceedsLimitException(
                    estimatedTokens, model.getMaxContextTokens(), model.getId());
            }
            throw e;
        }
    }

    /**
     * Turns a provider failure into the framework's typed signal, in precedence order: quota (a
     * terminal account condition) before 429 (a pacing signal the dispatcher retries transparently),
     * overload before the generic server error (a 529 matches both predicates and must ride the
     * capacity signal, not the fault path). An unrecognized failure comes back unchanged for the
     * caller to rethrow. Both call templates classify through here, so a failure kind means the
     * same retry-and-throttle behavior on the single and streaming paths.
     */
    private RuntimeException classifyUpstreamFailure(RuntimeException e) {
        // An already-classified signal stays what its thrower said: a retry signal keeps its
        // pacing semantics and a quota verdict stays terminal. Any other framework-typed
        // failure still faces the predicates below, because the OpenAI clients wrap every
        // transport failure in a descriptive uncorrectable whose cause chain still carries
        // the provider's raw 429 or 5xx: the wrap names the failure, this template decides
        // whether a retry can help, and a wrapper the predicates do not recognize comes
        // back unchanged, uncorrectable as its thrower said.
        if (e instanceof UpstreamRetryException || e instanceof QuotaExhaustedException) {
            return e;
        }
        // A hostname that does not resolve is deployment configuration, not weather: no retry
        // changes DNS. Checked first, because every SDK wraps it in an IO failure that the
        // generic server-error predicate would send into minutes of backoff. A client that
        // already refined the failure keeps its own words (Bedrock names the region that
        // built the hostname).
        UnknownHostException unknownHost = findUnknownHost(e);
        if (unknownHost != null) {
            return e instanceof LLMReadable ? e
                    : new UncorrectableRuntimeLLMException("The endpoint does not exist: " + unknownHost.getMessage()
                    + ". " + (model != null ? model.getId() : "This model")
                    + " is not served where this deployment points. Not retried: no retry changes DNS.", e);
        }
        if (isQuotaError(e)) {
            return quotaExhausted(e);
        }
        if (is429Error(e)) {
            RateLimitInfo info = extractRateLimitInfo(e);
            return new RateLimitRetryException(
                    "Rate limit exceeded: " + e.getMessage(), info, 0, e.getMessage(), info != null ? info.getRetryAfter() : null, e);
        }
        if (isOverloadError(e)) {
            String provider = model != null ? model.getId() : "unknown";
            String details = UpstreamRetryException.details(e);
            return new OverloadRetryException(
                    "Upstream overloaded: " + details, provider, details, e);
        }
        if (isServerError(e)) {
            String provider = model != null ? model.getId() : "unknown";
            String details = UpstreamRetryException.details(e);
            return new TransientErrorRetryException(
                    "Server error: " + details, provider, details, 0, 0, e);
        }
        return e;
    }

    /**
     * A failure after the stream already delivered chunks. The typed retry signals must not
     * escape here - the dispatcher would transparently re-run the call and re-deliver text the
     * consumer already rendered - but a capacity signal is still real fleet information, so its
     * backpressure weight feeds the model's throttle before the call fails. A failure that
     * classifies as an upstream signal is therefore rethrown as an uncorrectable naming the
     * mid-stream position; a quota classification is terminal anyway and surfaces typed; an
     * unrecognized failure propagates as the provider threw it.
     */
    private RuntimeException midStreamFailure(RuntimeException e) {
        RuntimeException classified = classifyUpstreamFailure(e);
        if (classified instanceof UpstreamRetryException upstream) {
            int increments = upstream.backpressureIncrements();
            TokenBucketRateLimiter limiter = model != null
                    ? RateLimiterRegistry.getInstance().getRateLimiter(model)
                    : null;
            if (limiter != null && increments > 0) {
                limiter.recordBackpressure(increments);
            }
            return new UncorrectableRuntimeLLMException("Upstream failed mid-stream on "
                    + (model != null ? model.getId() : "an unresolved model")
                    + " after chunks were delivered; not retried because the consumer already rendered them", e);
        }
        return classified;
    }

    /**
     * Updates the rate limits based on API response headers.
     */
    protected void updateRateLimitsFromHeaders(RateLimitInfo info) {
        if (model != null && info != null) {
            // Extract RPM and TPM from headers if available
            if (info.requestsPerMinute > 0 && info.tokensPerMinute > 0) {
                RateLimiterRegistry.getInstance().updateLimits(model, info.requestsPerMinute, info.tokensPerMinute);
            }
        }
    }

    /**
     * Calculates tokens for rate limiter reservation.
     * Uses processed content to match what will be sent to the LLM.
     *
     * @param request the request
     * @return total tokens to reserve (input + expected output)
     */
    protected int estimateTokens(LLMRequest<?> request) {
        int promptTokens = 0;
        ConversationContext context = request.getContext();
        TokenCounter counter = TokenizerFactory.get().forModel(model);
        PreparedConversation prepared = prepareConversation(context);
        // System content is part of the wire payload like any turn; not counting it is
        // how the pre-flight gate ends up trusting a number smaller than the request
        promptTokens += counter.countTokens(prepared.systemText());
        // The palette is counted in its text form regardless of how this client will encode
        // it: native tool parameters are billed as input tokens at essentially the text rate
        for (ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolDefinitionBlock td : prepared.toolDefinitions()) {
            promptTokens += counter.countTokens(ai.redouble.nucleo.harness.llm.encode.ToolDefinitionBlockEncoder.renderText(td));
        }
        for (ProcessedMessageData msg : prepared.turns()) {
            StringBuilder contentBuilder = new StringBuilder();
            for (ai.redouble.nucleo.harness.conversation.ContentBlocks.ContentBlock block : msg.contentBlocks()) {
                if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.TextBlock tb) {
                    contentBuilder.append(tb.text());
                } else if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.JsonBlock jb) {
                    contentBuilder.append(jb.json());
                } else if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolDefinitionBlock td) {
                    // counted in the text form regardless of how this client will encode it:
                    // native tool parameters are billed as input tokens at essentially the
                    // text rate, so one number serves both encodings
                    contentBuilder.append(ai.redouble.nucleo.harness.llm.encode.ToolDefinitionBlockEncoder.renderText(td));
                } else if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.ImageBlock ib) {
                    promptTokens += counter.countImageTokens(ib);
                } else if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.FileBlock fb) {
                    promptTokens += counter.countFileTokens(fb);
                }
            }
            promptTokens += counter.countTokens(contentBuilder.toString());
        }

        int responseTokens = context.resolveOutputBudget();
        int totalTokens = promptTokens + responseTokens;
        int warningThreshold = model.getTpm() / 10;  // Warn at 10% of TPM
        if (totalTokens > warningThreshold) {
            log.warn("Large token estimate for {}: {} tokens (prompt: {}, response: {})",
                    model.getId(), Formats.compactNumber(totalTokens),
                    Formats.compactNumber(promptTokens), Formats.compactNumber(responseTokens));
        }
        return totalTokens;
    }

    /**
     * Validates that estimated tokens don't exceed model limits.
     * Throws a checked exception if they do, allowing callers to handle gracefully
     * (e.g., trigger segmentation, compact context).
     *
     * @param estimatedTokens the estimated token count
     * @throws TokenEstimateExceedsLimitException if estimate exceeds model's max input tokens
     */
    protected void validateEstimatedTokens(int estimatedTokens) throws TokenEstimateExceedsLimitException {
        if (model == null) {
            return; // Can't validate without model info
        }
        int maxContextTokens = model.getMaxContextTokens();
        if (estimatedTokens > maxContextTokens) {
            log.warn("Pre-flight token estimate {} exceeds {} limit of {}",
                    Formats.compactNumber(estimatedTokens),
                    model.getId(),
                    Formats.compactNumber(maxContextTokens));
            throw new TokenEstimateExceedsLimitException(estimatedTokens, maxContextTokens, model.getId());
        }
    }

    /**
     * Returns the exact token count for a request from the provider's API, or -1 if not supported.
     * Only called when fast estimate exceeds 75% of context window.
     * Subclasses that support server-side counting should override this.
     */
    protected int countTokensExact(LLMRequest<?> request) {
        return -1;
    }

    /**
     * Implementation-specific method to perform the actual LLM request.
     * Subclasses must implement this to make their API calls.
     * The conversation has already been prepared via prepareConversation - do not call it again.
     * The prepared conversation carries the system content and the turns separately;
     * the client renders each into its dialect and decides nothing about routing.
     *
     * @param request the LLM request
     * @param prepared the prepared conversation, ready for provider-specific encoding
     * @return the LLM response
     */
    protected abstract <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared);

    /**
     * Checks if the given exception represents a rate limit error (HTTP 429).
     * Subclasses must implement based on their HTTP client/SDK.
     *
     * @param e the exception to check
     * @return true if this is a rate limit error
     */
    protected abstract boolean is429Error(Exception e);

    /**
     * Checks if the given exception represents a server error (HTTP 5xx).
     * Subclasses must implement based on their HTTP client/SDK.
     *
     * @param e the exception to check
     * @return true if this is a server/transient error
     */
    protected abstract boolean isServerError(Exception e);

    /**
     * Whether the exception is the provider's overload signal - a capacity statement
     * ("my fleet is saturated, slow down"), distinct from both a 429 (the caller's own
     * budget) and a plain 5xx fault. Checked before {@link #isServerError(Exception)},
     * which an overload status also matches. No default: every client answers - a
     * provider without a dedicated overload status answers false, and its overload
     * conditions ride whichever signal the provider actually uses.
     */
    protected abstract boolean isOverloadError(Exception e);

    /**
     * Checks if an exception represents an API "prompt too long" rejection.
     * This is the last-resort catch when the pre-flight estimate was wrong.
     * Subclasses can override for provider-specific exception types.
     */
    /**
     * The {@link UnknownHostException} anywhere in a failure's cause chain, or null: the one
     * failure no retry changes, since the endpoint is not served where this deployment points.
     * Shared with the embeddings client and with the catalog discovery, which reads a listing
     * that fails this way as the provider being unreachable from here.
     */
    public static UnknownHostException findUnknownHost(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof UnknownHostException unknownHost) {
                return unknownHost;
            }
        }
        return null;
    }

    protected boolean isPromptTooLongError(Exception e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("prompt is too long")
                    || lower.contains("too many input tokens")
                    || lower.contains("context length exceeded")
                    || lower.contains("maximum context length")
                    || lower.contains("token count exceeds")
                    || lower.contains("input is too long")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Gets the API dialect for this client.
     * Used to determine encoding strategies and compatibility.
     *
     * @return the API dialect
     */
    public abstract APIDialect getDialect();

    /**
     * Creates the content formatter for this client.
     * Subclasses should override to provide their specific formatter.
     *
     * @return the content formatter
     */
    protected ContentFormatter createFormatter() {
        return new GenericContentFormatter();
    }

    /**
     * Gets the content formatter for this client.
     * Lazily initializes if needed.
     *
     * @return the content formatter
     */
    public ContentFormatter getFormatter() {
        if (formatter == null) {
            formatter = createFormatter();
        }
        return formatter;
    }

    /**
     * Prepares the conversation for LLM consumption with artifact processing.
     *
     * <p>This is the MANDATORY method that all LLM clients must use to get
     * conversation messages. It ensures that:
     * <ul>
     *   <li>Artifacts are replaced with @ref placeholders</li>
     *   <li>Content is properly formatted for the target dialect</li>
     *   <li>Response instructions are appended</li>
     *   <li>The artifact registry is included at the end</li>
     * </ul>
     *
     * @param context the conversation context to prepare
     * @return the prepared conversation, ready for provider-specific encoding
     */
    protected PreparedConversation prepareConversation(ConversationContext context) {
        // The strip flag is computed from this client's own model, so the schema strip keys on the
        // same model the request-time gate (attachThinkingConfig) uses, with no cross-object model assumption.
        return context.prepareMessagesForLLM(getFormatter(), ThinkingMode.thinkingActive(getModel(), context.resolveDepth()));
    }

    /**
     * The provider's text wrap, injected into every encoder this client instantiates so the shared
     * text-rendering encoders ({@link TextBlockEncoder} and friends) produce this provider's native
     * text block. One stateless instance per provider.
     */
    protected abstract TextWrapper<B> textWrapper();

    /**
     * Block-type to encoder-class map. The defaults here render every block as text (or nothing);
     * subclasses call {@code super.buildEncoders()} and overwrite only the block types they render
     * natively. Class-to-class (not instances): the declaration is immutable and the per-client
     * instances live in {@link #encoderCache}, so a client instance is the only thing that touches a
     * given encoder instance. Every permitted {@link ContentBlocks.ContentBlock} type has an entry.
     */
    protected Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<B>>> buildEncoders() {
        Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<B>>> m = new HashMap<>();
        m.put(TextBlock.class, base(TextBlockEncoder.class));
        m.put(JsonBlock.class, base(JsonBlockEncoder.class));
        m.put(ToolUseBlock.class, base(ToolUseBlockEncoder.class));
        m.put(ToolResultBlock.class, base(ToolResultBlockEncoder.class));
        m.put(SkillBlock.class, base(SkillBlockEncoder.class));
        m.put(ImageBlock.class, base(ImageBlockEncoder.class));
        m.put(FileBlock.class, base(FileBlockEncoder.class));
        m.put(ThinkingBlock.class, base(ThinkingBlockEncoder.class));
        m.put(RedactedThinkingBlock.class, base(RedactedThinkingBlockEncoder.class));
        m.put(ToolDefinitionBlock.class, base(ToolDefinitionBlockEncoder.class));
        m.put(PojoBlock.class, base(PojoBlockEncoder.class));
        return m;
    }

    /**
     * Adapts a generic base encoder's raw {@code Class} to the map's {@code Class<? extends
     * BlockEncoder<B>>}. Safe because the base encoders are themselves {@code <B>}: they adapt to any
     * provider's native type via the injected wrapper. Provider-native encoder classes are concrete
     * (e.g. {@code AnthropicImageBlockEncoder extends ImageBlockEncoder<ContentBlockParam>}) and are
     * registered directly without this adapter, so a wrong-provider native encoder stays a compile
     * error. The parameter is the raw {@code BlockEncoder} because a class literal of a generic
     * encoder ({@code TextBlockEncoder.class}) is itself raw and matches no parameterized bound.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected static <B> Class<? extends BlockEncoder<B>> base(Class<? extends BlockEncoder> encoderClass) {
        return (Class<? extends BlockEncoder<B>>) (Class<?>) encoderClass;
    }

    /**
     * Encodes one content block into this provider's native block by resolving its encoder class,
     * instantiating it once (cached per client, wrapper injected), and delegating. Returns
     * {@code null} when the block produces no wire content (empty text, thinking on a provider with no
     * thinking channel, a tool definition that travels out-of-band); callers skip a null result. A
     * block type with no registered encoder is a programming error (a new permitted type added without
     * a default), surfaced loudly rather than silently dropped.
     */
    protected final B encodeBlock(ContentBlock block) {
        return encoderCache.computeIfAbsent(block.getClass(), this::instantiateEncoder).encode(block);
    }

    private BlockEncoder<B> instantiateEncoder(Class<? extends ContentBlock> blockClass) {
        Class<? extends BlockEncoder<B>> encoderClass = encoderClasses.get(blockClass);
        if (encoderClass == null) {
            throw new UncorrectableRuntimeLLMException("No BlockEncoder registered for " + blockClass.getName());
        }
        try {
            BlockEncoder<B> encoder = encoderClass.getDeclaredConstructor().newInstance();
            encoder.setTextWrapper(textWrapper());
            return encoder;
        }
        catch (ReflectiveOperationException e) {
            throw new UncorrectableRuntimeLLMException("Cannot instantiate BlockEncoder " + encoderClass.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The max output tokens this client puts on the wire for a call, and therefore the truncation
     * ceiling recorded on the response. Base is the answer budget, already capped at the model's
     * {@code maxOutputTokens} by {@link ConversationContext#outputReserve(ModelSpec)}: the declared
     * output plus the reasoning headroom the call books (Anthropic counts thinking as output
     * tokens and requires max_tokens to exceed the thinking budget; a reasoning-effort model
     * reasons inside the same ceiling), clamped at the model's cap. One formula for every
     * provider, the same one the reservation is priced with. Single source for both the wire
     * {@code max_tokens} field and the response's {@code requestedMaxTokens}, so the two cannot drift.
     */
    protected int resolveWireMaxTokens(ConversationContext context) {
        return context.outputReserve(context.getModel());
    }

    /**
     * The {@code reasoning_effort} word for a call's depth on a {@link ThinkingMode#REASONING_EFFORT}
     * model. The model always reasons - there is no "none" - so IMMEDIATE and QUICK map to the floor
     * (low), STANDARD to medium, and the thorough depths to high. Shared by every client that speaks
     * that vocabulary (OpenAI direct, gpt-oss on Bedrock Converse) so depth means one thing.
     */
    protected static String reasoningEffortFor(Depth depth) {
        return switch (depth) {
            case IMMEDIATE, QUICK -> "low";
            case STANDARD -> "medium";
            case THOROUGH, ULTRA_THOROUGH -> "high";
        };
    }

    /**
     * Caller attribution for the request/response log envelope: {@code " | from <DisplayName>"} when
     * the conversation carries a JobContext with a display name, empty otherwise.
     */
    /**
     * The id of the job the call runs in - the id its lifecycle lines carry - or
     * {@code no job} for a call made outside the job system.
     */
    private static String callerJobId(ConversationContext context) {
        JobContext<?> jobCtx = context.getJobContext();
        return jobCtx != null ? jobCtx.getSnapshot().getJobId() : "no job";
    }

    /** A token count as the log prints it; a provider that reports none prints {@code unreported}. */
    private static String tokens(Integer count) {
        return count != null ? Formats.compactNumber(count) : "unreported";
    }

    protected String getCallerInfo(ConversationContext context) {
        JobContext<?> jobCtx = context.getJobContext();
        if (jobCtx != null) {
            String displayName = jobCtx.getSnapshot().getDisplayName();
            if (displayName != null && !displayName.isEmpty()) {
                return " | from " + displayName;
            }
        }
        return "";
    }

    /**
     * Creates a new conversation message.
     * Always returns GenericOutgoingMessage for provider independence.
     *
     * @return a new generic conversation message
     */
    @Override
    public <T> OutgoingMessage<T> createOutgoingMessage(ResponseHandler<T> responseHandler) {
        return new OutgoingMessage<>(responseHandler);
    }

    /**
     * Extracts rate limit information from an exception if possible.
     * May return null if the information is not available.
     *
     * @param e the exception containing rate limit info
     * @return rate limit info or null
     */
    protected abstract RateLimitInfo extractRateLimitInfo(Exception e);

    /**
     * Streams response chunks from the LLM. Handles rate limiting and constructs
     * the response record that {@link #doStreamResponse} populates in place.
     * Provider clients are responsible for capturing the wire request/response JSON
     * onto {@code request} via {@link LLMRequest#setInputJson}/{@link LLMRequest#setOutputJson}.
     *
     * <p>A failure before the first chunk classifies exactly like the single path's (quota,
     * 429, overload, 5xx, and the prompt-too-long rejection), because the caller has seen
     * nothing a retry would duplicate. A failure after a chunk was delivered never retries -
     * a transparent re-run would re-deliver text the consumer already rendered - but one that
     * classifies as a capacity signal still feeds the model's throttle before the call fails
     * as an uncorrectable naming the mid-stream position; an unrecognized failure propagates
     * as the provider threw it.
     *
     * @return the populated {@link LLMResponse} (check {@code isSuccessful()})
     */
    @Override
    public final <T> LLMResponse<T> streamResponse(LLMRequest<T> request, Consumer<StreamChunk> chunkHandler)
            throws TokenEstimateExceedsLimitException {
        int estimatedTokens = estimateTokens(request);
        validateEstimatedTokens(estimatedTokens);

        PreparedConversation prepared = prepareConversation(request.getContext());
        LLMResponse<T> response = new LLMResponse<>(request);
        response.setModel(getModelIdentifier());
        // Tracks whether the provider delivered anything yet. Until the first chunk, a failure is
        // indistinguishable from the single path's - the caller has seen nothing - so it gets the
        // same typed signals (transparent retry, fleet throttle). After a chunk, a typed retry
        // would transparently re-run the call and re-deliver text the consumer already rendered,
        // so no retry signal may escape; midStreamFailure keeps the throttle informed instead.
        AtomicBoolean chunkDelivered = new AtomicBoolean();
        Consumer<StreamChunk> trackedHandler = chunk -> {
            chunkDelivered.set(true);
            chunkHandler.accept(chunk);
        };
        try {
            executeWithRateLimit(new RateLimitedOperation<Void>() {
                @Override
                public Void execute() {
                    try {
                        doStreamResponse(request, response, prepared, trackedHandler);
                    }
                    catch (RuntimeException e) {
                        if (!chunkDelivered.get()) {
                            throw classifyUpstreamFailure(e);
                        }
                        throw midStreamFailure(e);
                    }
                    return null;
                }
            });
        }
        catch (RuntimeException e) {
            if (model != null && isPromptTooLongError(e)) {
                log.warn("API rejected streamed prompt as too long (estimate was {} for {})",
                        Formats.compactNumber(estimatedTokens), model.getId());
                throw new TokenEstimateExceedsLimitException(
                        estimatedTokens, model.getMaxContextTokens(), model.getId());
            }
            throw e;
        }
        response.setProvider(getDialect().providerKey());
        response.setRequestedMaxTokens(resolveWireMaxTokens(request.getContext()));
        response.setEndTime(java.time.Instant.now());
        response.setSuccessful(true);
        throwIfRefused(response, request);
        throwIfTruncated(response, request);
        return response;
    }

    /**
     * A provider that refused answers with nothing in the body and a stop reason that says so
     * ({@link LLMStopReason#CONTENT_FILTERED}: Anthropic's {@code refusal}, a guardrail, a
     * content filter). Left alone, the empty body reaches the parser as "empty response" and a
     * thinker spends its whole correction budget re-asking a question the provider has already
     * declined, a dozen calls for one refusal. The refusal is a fact about the request as this
     * model reads it, so it surfaces as {@link ProviderRefusalException}, uncorrectable on this
     * model and typed for the caller that wants to resubmit on another, carrying the provider's
     * category ({@link LLMResponse#getRefusalCategory()}) and explanation
     * ({@link LLMResponse#getRefusal()}), on both templates, before anything downstream reads
     * the body.
     */
    private void throwIfRefused(LLMResponse<?> response, LLMRequest<?> request) {
        if (response.getStopReason() != LLMStopReason.CONTENT_FILTERED) {
            return;
        }
        ProviderRefusalException refusal = new ProviderRefusalException(request.getContext().getModel().getId(),
                response.getRefusalCategory(), response.getRefusal());
        response.setSuccessful(false);
        response.setReasonForFailure(refusal.getMessage());
        log.warn(refusal.getMessage());
        throw refusal;
    }

    /**
     * Centralized output-truncation handling. If the response hit the model's output ceiling
     * ({@link LLMResponse#wasTruncated()}), the content is incomplete and would fail to parse, so
     * signal the framework's deterministic retry instead of letting the half-written body flow
     * downstream. Runs for every client on both the single and streaming templates, so a single
     * client (or a future one) cannot silently skip it - which is exactly how truncated Bedrock
     * responses were reaching the JSON parser as "No JSON object or array found".
     *
     * <p>The escalation happens here too: the outgoing message that was just sent gets its
     * per-call budget bumped to the model ceiling before the retry signal is raised. A caller
     * that retains its conversation across attempts (a thinker's {@code LLMCall}, a one-call
     * job that builds its conversation once) therefore re-runs at the escalated budget with
     * nothing to opt into; a caller that rebuilds its conversation per attempt re-runs at its
     * declared budget, and the dispatcher reports that shape by name on the second truncation.
     *
     * <p>The retry signal is raised only when a larger budget exists. Since
     * {@link ConversationContext#resolveOutputBudget()} caps at the model's ceiling, a call
     * already issued at that ceiling has nowhere to grow: proposing the same budget again would
     * spend a second identical upstream call to reach the same truncation. That case is the
     * model's own limit refusing the answer, so it surfaces as an uncorrectable failure - a
     * different model or a smaller request is the only way through.
     */
    private void throwIfTruncated(LLMResponse<?> response, LLMRequest<?> request) {
        if (!response.wasTruncated()) {
            return;
        }
        // the request's own model, the same one the budget and the wire ceiling resolve
        // against, so all three cannot drift apart
        ConversationContext context = request.getContext();
        ModelSpec requestModel = context.getModel();
        int previousBudget = context.resolveOutputBudget();
        int modelMax = requestModel.getMaxOutputTokens();
        String modelName = requestModel.getId();
        response.setSuccessful(false);
        response.setReasonForFailure("Response truncated: hit max_tokens limit at " + previousBudget + " tokens");
        if (previousBudget >= modelMax) {
            log.warn("LLM response truncated at {} tokens on {}, the model's own output ceiling - no larger budget to retry with", previousBudget, modelName);
            throw new UncorrectableRuntimeLLMException("The answer did not fit in " + modelName
                    + "'s maximum output of " + modelMax + " tokens. Use a model with a larger"
                    + " output ceiling, or ask for a smaller result.");
        }
        log.warn("LLM response truncated at {} tokens on {} - signalling framework retry with budget {}", previousBudget, modelName, modelMax);
        context.getLastOutgoingMessage().setRequestedOutputTokens(modelMax);
        throw new OutputTruncationRetryException(modelName, previousBudget, modelMax);
    }

    /**
     * Implementation-specific streaming body. Subclasses parse the provider's
     * stream events, forward text deltas through {@code chunkHandler}, and
     * populate usage fields on {@code response} (via
     * {@link LLMResponse#setUsage} and any other applicable setters) before
     * returning. Subclasses must also capture wire input/output JSON onto
     * {@code request} via {@link LLMRequest#setInputJson}/{@link LLMRequest#setOutputJson}.
     */
    protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response, PreparedConversation prepared, Consumer<StreamChunk> chunkHandler) {
        // Reachable from job execution (a chat's streamed turn), so the refusal must speak
        // the framework's exception language, not a raw Java one
        throw new UncorrectableRuntimeLLMException("Streaming is not supported by " + this.getClass().getSimpleName()
                + ". Use the non-streaming call, or a model served by a streaming-capable client.");
    }
}