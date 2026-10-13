/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.tokens.*;

import java.time.*;
import java.util.*;

/**
 * Response object from LLM API calls containing token metrics and response data.
 *
 * <p>What the call cost in tokens is one {@link TokenLedger}, {@link #getUsage()}: input,
 * cache reads and writes, output, and the cost at the model's prices, built by the provider's
 * client from the provider's own usage record. It is the same type the reservation held
 * before the call, so what was reserved and what was spent compare facet by facet. A provider
 * that returns no usage record leaves it null; a facet it does not report is null on the
 * ledger.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public class LLMResponse<T> {
    /**
     * The catalog id of the spec this call was issued against - what WE called, never
     * the name the provider echoes back. The echo collapses variants (every Haiku
     * endpoint echoes the same wire name; Converse echoes nothing), while this id is
     * identical on successes and failures, so recorded calls attribute cleanly per spec.
     * Set centrally by the abstract client templates.
     */
    protected String model;

    /** The tokens this call spent, as the provider reported them; null when it reported nothing. */
    protected TokenLedger usage;

    /**
     * Provider key for billing and observability. Set by the abstract client
     * wrapper from {@link AbstractLLMClient#getDialect()}.
     */
    protected String provider;

    protected final ConversationContext context;
    protected IncomingMessage<T> responseMessage;
    protected Throwable lastError;
    protected int attempts = 1;
    protected boolean successful = false;
    protected String reasonForFailure;
    protected RateLimitInfo rateLimitInfo;
    protected Instant startTime;
    protected Instant endTime;
    protected final LLMRequest<T> request;
    protected LLMStopReason stopReason = LLMStopReason.UNKNOWN;
    /** The provider's category for a {@link LLMStopReason#CONTENT_FILTERED} stop, when it named one. */
    protected String refusalCategory;
    /** The provider's own explanation of a {@link LLMStopReason#CONTENT_FILTERED} stop, when it gave one. */
    protected String refusal;
    protected int requestedMaxTokens;

    /**
     * The model id the PROVIDER says served this call - the alias echo (an OpenAI alias
     * like gpt-5.6 answers with its snapshot, e.g. gpt-5.6-sol). Null when the provider
     * echoes nothing (Converse). Distinct from {@link #model}, which is always our
     * catalog id.
     */
    protected String servedModelId;

    /** The provider's own request id, for correlating with provider-side logs and support tickets. */
    protected String providerRequestId;

    /**
     * The raw response headers, verbatim with lowercased names - rate-limit envelopes,
     * request ids, whatever the provider sends. Null on transports that expose none
     * (SSE streaming).
     */
    protected Map<String, String> providerHeaders;

    public LLMResponse(LLMRequest<T> request) {
        this.request = request;
        this.context = request.getContext();

        // Get the response handler from the last outgoing message
        // If we're creating a response, we MUST have sent a request
        OutgoingMessage<T> outgoingMessage = context.getLastOutgoingMessage();
        if (outgoingMessage == null) {
            throw new IllegalStateException("Cannot create LLMResponse without an OutgoingMessage in the conversation");
        }
        this.responseMessage = outgoingMessage.createIncomingMessage();
        // Don't add to conversation yet - will be added after successful population
        this.startTime = Instant.now();
    }

    /**
     * A call that is no conversation exchange. An embeddings call has a model, input tokens,
     * a latency and a verdict, and no message in or out, so {@link #getContext()},
     * {@link #getRequest()} and {@link #getResponseMessage()} are null on it. What every
     * rollup reads (model, tokens, latency, success) is here, which is why such a call is a
     * response on the job like any other.
     */
    protected LLMResponse() {
        this.request = null;
        this.context = null;
        this.responseMessage = null;
        this.startTime = Instant.now();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getServedModelId() {
        return servedModelId;
    }

    public void setServedModelId(String servedModelId) {
        this.servedModelId = servedModelId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public Map<String, String> getProviderHeaders() {
        return providerHeaders;
    }

    public void setProviderHeaders(Map<String, String> providerHeaders) {
        this.providerHeaders = providerHeaders;
    }

    /**
     * The tokens this call spent, by kind, as the provider reported them: null when the
     * provider returned no usage record, so nothing downstream bills the call at zero.
     */
    public TokenLedger getUsage() {
        return usage;
    }

    /** Set by the provider's client from its usage record, once, when the response is complete. */
    public void setUsage(TokenLedger usage) {
        this.usage = usage;
    }

    public Throwable getLastError() {
        return lastError;
    }

    public void setLastError(Throwable lastError) {
        this.lastError = lastError;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public void setSuccessful(boolean successful) {
        this.successful = successful;
    }

    public String getReasonForFailure() {
        return reasonForFailure;
    }

    public void setReasonForFailure(String reasonForFailure) {
        this.reasonForFailure = reasonForFailure;
    }

    public RateLimitInfo getRateLimitInfo() {
        return rateLimitInfo;
    }

    public void setRateLimitInfo(RateLimitInfo rateLimitInfo) {
        this.rateLimitInfo = rateLimitInfo;
    }

    public Duration getLatency() {
        if (startTime != null && endTime != null) {
            return Duration.between(startTime, endTime);
        }
        return Duration.ZERO;
    }

    public long getLatencyMs() {
        return getLatency().toMillis();
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public IncomingMessage<T> getResponseMessage() {
        return responseMessage;
    }

    public ConversationContext getContext() {
        return context;
    }

    public LLMRequest<T> getRequest() {
        return request;
    }

    /**
     * Gets the stop reason returned by the LLM API.
     * Common values: "end_turn", "max_tokens", "tool_use", "stop_sequence"
     *
     * @return the normalized stop reason ({@link LLMStopReason#UNKNOWN} if not set)
     */
    public LLMStopReason getStopReason() {
        return stopReason;
    }

    public void setStopReason(LLMStopReason stopReason) {
        this.stopReason = stopReason;
    }

    /**
     * The provider's word for the policy area behind a {@link LLMStopReason#CONTENT_FILTERED}
     * stop (Anthropic's {@code stop_details.category}: {@code reasoning_extraction},
     * {@code cyber}, {@code bio}, ...); null when it named none. The one field a caller may
     * branch on when it decides whether to resubmit elsewhere.
     */
    public String getRefusalCategory() {
        return refusalCategory;
    }

    public void setRefusalCategory(String refusalCategory) {
        this.refusalCategory = refusalCategory;
    }

    /**
     * Why the provider refused, in its own words, when the stop reason is
     * {@link LLMStopReason#CONTENT_FILTERED} and the provider explained itself (Anthropic's
     * {@code stop_details.explanation}); null when it gave no account. Display text, never
     * parsed: the provider does not hold it stable.
     */
    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }

    /**
     * The max output tokens requested of the provider for this call. Used as a backstop for
     * truncation detection on backends that do not surface a typed stop reason.
     */
    public int getRequestedMaxTokens() {
        return requestedMaxTokens;
    }

    public void setRequestedMaxTokens(int requestedMaxTokens) {
        this.requestedMaxTokens = requestedMaxTokens;
    }

    /**
     * Checks if the response was truncated due to hitting the output ceiling. When truncated, the
     * content is incomplete and may not parse correctly.
     *
     * <p>Primary signal is the normalized {@link LLMStopReason#MAX_TOKENS}. Some backends (e.g.
     * Anthropic-over-Bedrock) do not surface a typed stop reason, so a secondary backstop treats
     * output reaching the requested ceiling as truncation. A call with no usage record, or no
     * output count on it, gives no backstop signal.
     *
     * @return true if the response was truncated
     */
    public boolean wasTruncated() {
        if (stopReason == LLMStopReason.MAX_TOKENS) {
            return true;
        }
        Integer output = usage != null ? usage.outputTokens() : null;
        return requestedMaxTokens > 0 && output != null && output >= requestedMaxTokens;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
