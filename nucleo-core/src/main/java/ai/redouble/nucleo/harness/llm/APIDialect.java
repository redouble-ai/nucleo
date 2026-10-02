/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;


/**
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public enum APIDialect {

    // --- OpenAI ecosystem ---
    OPENAI("openai", "OpenAI REST schema with /v1/... endpoints; JSON errors with {error:{type,message,param,code}}", "Native OpenAI endpoints (api.openai.com)", "OPENAI"),
    AZURE_OPENAI("azure-openai", "Azure wrapper for OpenAI REST schema; same payloads, different URL structure and Azure error codes", "Azure OpenAI Service (openai.azure.com / *.openai.azure.com)", "OPENAI"),

    // --- Anthropic ecosystem ---
    ANTHROPIC_V1("anthropic-v1", "Anthropic Claude v1 REST API; uses application/jsonl for streaming; {error:{type,message}} format", "Anthropic direct API (api.anthropic.com)", "ANTHROPIC"),
    BEDROCK_ANTHROPIC("bedrock-anthropic", "AWS Bedrock integration of Anthropic models; AWS-style JSON envelopes and SigV4 auth", "AWS Bedrock (bedrock-runtime.{region}.amazonaws.com)", "BEDROCK"),
    // Same models, same SigV4 auth and same pricing as BEDROCK_ANTHROPIC, but the native Anthropic
    // Messages API instead of InvokeModel. providerKey stays BEDROCK so billing multipliers and the
    // recorded provider value are unaffected; the two surfaces are told apart by the recorded spec
    // id (the '-mantle' family).
    BEDROCK_MANTLE("bedrock-mantle", "AWS Bedrock Mantle endpoint serving the native Anthropic Messages API; bare anthropic.* model ids and SigV4 auth", "AWS Bedrock Mantle (bedrock-mantle.{region}.api.aws)", "BEDROCK"),

    // --- AWS Bedrock (other models) ---
    BEDROCK_TITAN("bedrock-titan", "AWS Titan models served via Bedrock; AWS envelope and Bedrock throttling semantics", "AWS Bedrock", "BEDROCK_CONVERSE"),
    BEDROCK_MISTRAL("bedrock-mistral", "Mistral models served via AWS Bedrock; same envelope, different model_origin", "AWS Bedrock", "BEDROCK_CONVERSE"),
    BEDROCK_META("bedrock-meta", "Meta Llama models via AWS Bedrock; identical Bedrock request/response contract", "AWS Bedrock", "BEDROCK_CONVERSE"),
    BEDROCK_CONVERSE("bedrock-converse", "AWS Bedrock Converse API; unified interface for all Bedrock models", "AWS Bedrock", "BEDROCK_CONVERSE"),

    // --- Google ecosystem ---
    VERTEX_GEMINI("vertex-gemini", "Google Vertex AI Gemini API; Protobuf/JSON hybrid schema; Google-style error {error:{code,message,details[]}}", "Google Cloud Vertex AI (us-central1-aiplatform.googleapis.com)", "GOOGLE"),
    GOOGLE_AI_STUDIO("google-ai-studio", "Legacy Gemini endpoint for AI Studio; similar payloads but different auth and quota headers", "generativelanguage.googleapis.com", "GOOGLE"),

    // --- Cohere ---
    COHERE_V1("cohere-v1", "Cohere REST schema; application/json, simple error fields {message,code}", "api.cohere.com", "COHERE"),

    // --- Mistral direct ---
    MISTRAL_V1("mistral-v1", "Mistral direct API; OpenAI-like request structure but unique error schema and endpoints", "api.mistral.ai", "MISTRAL"),

    // --- Meta direct ---
    META_LLAMASTACK("meta-llamastack", "Meta Llama Stack / Ollama-like interface; standard JSON, local deployment focus", "llama.meta.com or local inference stack", "META"),

    // --- Ollama / local inference ---
    OLLAMA("ollama", "Local HTTP interface (localhost:11434); OpenAI-like schema; minimal error typing", "Local runtime", "OLLAMA"),

    // --- Generic Fallback ---
    CUSTOM("custom", "User-defined or proprietary API dialect; may emulate OpenAI schema", "Custom / internal", "CUSTOM");

    private final String id;
    private final String description;
    private final String hostExample;
    private final String providerKey;

    APIDialect(String id, String description, String hostExample, String providerKey) {
        this.id = id;
        this.description = description;
        this.hostExample = hostExample;
        this.providerKey = providerKey;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public String hostExample() {
        return hostExample;
    }

    /**
     * Canonical provider tag recorded on every call and used by
     * billing-multiplier resolution. Groups dialects that share a pricing
     * model: Azure-hosted and direct OpenAI both resolve to {@code OPENAI};
     * Anthropic-on-Bedrock stays {@code BEDROCK} to preserve the column's
     * historical value while still sharing Anthropic pricing.
     */
    public String providerKey() {
        return providerKey;
    }

    @Override
    public String toString() {
        return id;
    }
}
