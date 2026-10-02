/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.secrets.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import java.io.*;
import java.util.*;

/**
 * Serves the OpenAI surface ({@code /openai/v1}) of an Azure AI Foundry resource, on the
 * Responses API: the Azure OpenAI models
 * and the Foundry Models sold by Azure on OpenAI's v1 syntax. Foundry's other surfaces are
 * other providers' - Claude on Foundry rides the Anthropic SDK - which is why the key names the
 * surface and not just the vendor, as Bedrock's keys do.
 *
 * <p>The key deliberately does not end in {@code -embeddings}: that suffix is the catalog's
 * convention for the embeddings client family ({@code ModelSpec.isEmbeddings()}), and a chat
 * provider carrying it would make the resolution gate refuse every LLM seat it serves. The
 * platform is {@code azure}, shared with {@link AzureOpenAIEmbeddingsProvider}: a model a
 * deployment disables on Azure is disabled on Azure.
 *
 * <p>It lists what the resource can reach on this surface: its deployments, through
 * {@link AzureFoundryDeployments}, under the same resource key, minus the Claude ones, which
 * the same resource serves on its Anthropic surface and which this one answers with
 * {@code 404 Requested API is currently not supported}. The shipped fragment carries no
 * entries for it, since the deployments behind a resource are named by the people who create
 * them; the discovery reports each one the deployment's own {@code models.json} does not name.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class AzureFoundryOpenAIProvider extends AbstractClientProvider<AzureFoundryOpenAIClient> implements ModelDiscovery {
    /** The base models of another surface: Claude on Foundry is the Anthropic Messages surface. */
    static boolean onChatCompletions(String baseModel) {
        return !baseModel.startsWith("claude");
    }

    @Override
    public String key() {return "azure-foundry-openai";}

    @Override
    public String platform() {return "azure";}
    @Override
    public String credentialId() {return AzureFoundryOpenAIClient.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(AzureFoundryOpenAIClient.SHAPE);}
    @Override
    protected AzureFoundryOpenAIClient newClient() {return new AzureFoundryOpenAIClient();}
    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        Credential credential = Secrets.configured().require(credentialId());
        return new AzureFoundryDeployments(credential.host(), credential.secret()).list(AzureFoundryOpenAIProvider::onChatCompletions);
    }
}
