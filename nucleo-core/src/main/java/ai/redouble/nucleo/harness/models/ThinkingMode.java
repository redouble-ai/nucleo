/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * Capability flag on {@link ModelSpec} describing how a model reasons and what that costs the
 * caller. The client dispatches its request shape on this mode (sending the wrong shape returns
 * HTTP 400), and the framework reads it through this enum's two predicates:
 * {@link #reasoningReserved} (does a call book reasoning headroom, both on the wire ceiling and
 * in the rate-limit reservation) and {@link #thinkingActive} (does a native thinking block come
 * back and supersede the envelope's prose reasoning field, so the schema strips it).
 *
 * <ul>
 *   <li>{@code NONE} - the model does not reason. No thinking config, no headroom, no strip.
 *   <li>{@code EXTENDED} - classic Anthropic extended thinking: {@code thinking: {type: "enabled",
 *       budget_tokens: N}}. The budget is sent, reserved and added to the wire ceiling; a native
 *       thinking block comes back. Nothing at IMMEDIATE depth.
 *   <li>{@code ADAPTIVE} - Anthropic adaptive thinking (Opus 4.7 onwards): {@code thinking: {type:
 *       "adaptive", display: "summarized"}} plus {@code output_config: {effort: ...}}. The provider
 *       picks the budget from the effort word; the entry's number is headroom and reservation
 *       only. A native thinking block comes back. Nothing at IMMEDIATE depth.
 *   <li>{@code REASONING_EFFORT} - the OpenAI reasoning generation, direct or as gpt-oss on Bedrock
 *       Converse: the model always reasons, its verbosity is a {@code reasoning_effort} word mapped
 *       from the call's {@link Depth} (IMMEDIATE and QUICK to the floor), and the reasoning tokens
 *       count inside the ordinary completion ceiling. The entry's number is therefore headroom
 *       added above the answer and reserved, at every depth. No native thinking block comes back,
 *       so the prose reasoning field stays in the schema.
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public enum ThinkingMode {
    NONE,
    EXTENDED,
    ADAPTIVE,
    REASONING_EFFORT;

    /**
     * Whether a native thinking block is guaranteed for a call on this model and depth: the
     * model has an Anthropic thinking mode and depth is not IMMEDIATE. Read by the
     * request-time gate ({@code AnthropicSDKClient.attachThinkingConfig}) and by the schema
     * strip (the envelope's prose {@code reasoning} field is omitted from the response schema
     * when this holds, because the thinking block supersedes it). The strip is computed from
     * the client's own model, so it keys on the same model the request gate uses.
     *
     * <p>Deliberately false for {@link #REASONING_EFFORT}: those models return no thinking
     * block, so the prose field must stay. Their headroom is {@link #reasoningReserved}'s
     * business, not this predicate's.
     */
    public static boolean thinkingActive(ModelSpec model, Depth depth) {
        if (model == null || depth == Depth.IMMEDIATE) {
            return false;
        }
        ThinkingMode mode = model.getThinkingMode();
        return mode == EXTENDED || mode == ADAPTIVE;
    }

    /**
     * Whether a call on this model and depth books reasoning headroom: the entry's thinking
     * budget is added to the wire output ceiling and to the rate-limit reservation. Read by
     * {@code ConversationContext.resolveThinkingBudget} and by the clients' wire-ceiling
     * resolution. True for the Anthropic modes exactly when {@link #thinkingActive} is, and
     * for {@link #REASONING_EFFORT} at every depth: that model reasons inside the ordinary
     * completion ceiling on every call, IMMEDIATE included.
     */
    public static boolean reasoningReserved(ModelSpec model, Depth depth) {
        if (model == null) {
            return false;
        }
        if (model.getThinkingMode() == REASONING_EFFORT) {
            return true;
        }
        return thinkingActive(model, depth);
    }
}
