/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.secrets.*;
import com.openai.azure.credential.*;
import com.openai.client.*;
import com.openai.client.okhttp.*;

/**
 * The OpenAI surface of an Azure AI Foundry resource, {@code /openai/v1}, through the same
 * OpenAI Java client as {@link OpenAISDKClient}, on the Responses API, which Foundry serves for
 * every model on that surface and which is where the reasoning generation reasons and calls
 * tools in one turn.
 *
 * <p>Foundry is a multi-vendor host the way Bedrock is: one resource, several wire surfaces,
 * each the native API of a model family. This client is exactly one of them, the surface that
 * speaks OpenAI's v1 syntax. It serves the Azure OpenAI models and the Foundry Models sold by
 * Azure that Microsoft documents on that syntax (DeepSeek, Grok, Llama, Microsoft AI, gpt-oss).
 * Claude on Foundry is a different surface, {@code /anthropic/v1/messages}, the native Messages
 * API behind the Anthropic SDK, and belongs to an Anthropic provider, not here - the same split
 * as Bedrock's Mantle and Converse surfaces.
 *
 * <p>The surface is what makes this class thin. It is wire-compatible with OpenAI's own: the
 * model is named in the body, the response has the same shape, reasoning effort is spelled the
 * same, so the whole of the parent - request builder, typed parameters, envelope capture,
 * failure classification, streaming - applies unchanged. Two things differ, and the SDK carries
 * both once it is pointed at the resource:
 * <ul>
 *   <li><b>The endpoint is per resource.</b> Each Foundry resource has its own hostname, so the
 *       base URL is built from the credential's {@code host} rather than a constant. Under a
 *       {@code /openai/v1} root the SDK recognizes the unified surface and posts the dialect's
 *       paths beneath it as they are; the legacy {@code /openai/deployments/{name}} routing,
 *       which ignores the body's model, is never engaged.</li>
 *   <li><b>The credential is an {@code api-key} header</b>, which the SDK sends in place of
 *       {@code Authorization: Bearer} when the credential is an {@link AzureApiKeyCredential}.</li>
 * </ul>
 *
 * <p>The credential is {@link #SECRET_ID}: {@code secret} the resource key, {@code host} the
 * resource hostname ({@code my-resource.services.ai.azure.com}). Both are needed, because
 * Foundry is addressed per resource; a credential without a host is refused with the reason.
 * The id names the resource rather than this surface, since one resource key opens every
 * surface of the resource.
 *
 * <p>The rate-limit envelope is the parent's: Foundry answers with the same {@code x-ratelimit-*}
 * family OpenAI sends (limit and remaining for tokens and requests, per a 60-second renewal
 * period it also names), keyed by the served model, so the adaptive limiter learns the account's
 * live limits from every response here as it does on OpenAI.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 * @see AzureFoundryOpenAIProvider
 */
public class AzureFoundryOpenAIClient extends OpenAISDKClient {
    /** The resource credential: {@code secret} the resource key, {@code host} the resource hostname. */
    public static final String SECRET_ID = "azure-foundry-api-key";
    /** What the credential is made of, as the provider declares it. */
    public static final CredentialShape SHAPE = CredentialShape.secretAndHost(SECRET_ID, "AZURE_FOUNDRY_API_KEY", "the resource key",
            "AZURE_FOUNDRY_API_KEY_HOST", "the resource hostname, e.g. my-resource.services.ai.azure.com");
    static final String SERVICE_NAME = "Azure AI Foundry";
    private OpenAIClient client;
    private String apiKey;
    private String host;

    /** The host and key come from the store on first use, so the request builder is exercisable without either. */
    public AzureFoundryOpenAIClient() {
        super(WireApi.RESPONSES);
    }

    /** An explicit resource and key, for a harness that has them in hand. */
    public AzureFoundryOpenAIClient(String host, String apiKey) {
        super(WireApi.RESPONSES);
        this.host = host;
        this.apiKey = apiKey;
    }

    /** The OpenAI-surface root of a Foundry resource: the one home for that path. */
    static String apiRootOf(String host) {
        return "https://" + resourceHost(host) + "/openai/v1";
    }

    /**
     * The bare resource hostname from whatever a person provided. Azure's portal hands out
     * the endpoint with its scheme and often a surface's path
     * ({@code https://x.services.ai.azure.com/openai/v1}), the store convention is the
     * hostname alone, and both must work: prefixing a scheme onto a value that already has
     * one builds {@code https://https://...}, which DNS answers by failing to resolve the
     * hostname "https". The scheme and any path are dropped - every Azure surface here is
     * addressed by the resource's hostname, and each client owns its own path.
     */
    static String resourceHost(String host) {
        String bare = host.strip();
        int scheme = bare.indexOf("://");
        if (scheme >= 0) {
            bare = bare.substring(scheme + "://".length());
        }
        int path = bare.indexOf('/');
        if (path >= 0) {
            bare = bare.substring(0, path);
        }
        return bare;
    }

    @Override
    protected OpenAIClient client() {
        if (client == null) {
            if (apiKey == null || host == null) {
                Credential credential = Secrets.configured().require(SECRET_ID);
                if (credential.host() == null || credential.host().isBlank()) {
                    throw new UncorrectableRuntimeLLMException("The credential '" + SECRET_ID + "' carries no host;"
                            + " Azure AI Foundry is addressed per resource, so its host part must be the resource hostname"
                            + " (e.g. my-resource.services.ai.azure.com) alongside the resource key");
                }
                apiKey = apiKey != null ? apiKey : credential.secret();
                host = host != null ? host : credential.host();
            }
            client = OpenAIOkHttpClient.builder()
                    .baseUrl(apiRootOf(host))
                    .credential(AzureApiKeyCredential.create(apiKey))
                    .maxRetries(0)
                    .build();
        }
        return client;
    }

    /**
     * Holds the key here rather than in the parent: the parent's setter writes a field only the
     * parent's {@link #client()} reads, and this class replaces that method, so without this
     * override the inherited setter would silently do nothing on a Foundry client.
     */
    @Override
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    protected String accountIdentifier() {
        return "AZURE-FOUNDRY-OPENAI / secret " + SECRET_ID;
    }
}
