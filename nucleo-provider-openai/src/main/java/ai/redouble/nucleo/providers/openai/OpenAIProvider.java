/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.secrets.*;

import java.io.*;
import java.util.*;

/**
 * Serves OpenAI chat models through OpenAI's own client on the Responses API, the surface where
 * the reasoning generation reasons and calls tools in one turn. The older surface is
 * {@link OpenAIChatCompletionsProvider}, the same models under another key.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class OpenAIProvider extends AbstractClientProvider<OpenAISDKClient> implements ModelDiscovery {
    /** The id of the OpenAI credential in the deployment's secret store: the key, and for Azure the host. */
    public static final String SECRET_ID = "openai-api-key";
    /** What the credential is made of: the key, and for Azure OpenAI the resource host; every provider riding this id declares this shape. */
    public static final CredentialShape SHAPE = CredentialShape.secretAndHost(SECRET_ID, "OPENAI_API_KEY", "the API key",
            "OPENAI_API_KEY_HOST", "the Azure OpenAI resource host, absent for OpenAI itself");
    /** How an OpenAI account names itself in quota and failure messages, the same from chat and from embeddings. */
    static final String ACCOUNT = "OPENAI / secret " + SECRET_ID;

    @Override
    public String key() {return "openai";}

    @Override
    public String platform() {return "openai";}
    @Override
    public String credentialId() {return SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(SHAPE);}
    @Override
    protected OpenAISDKClient newClient() {return new OpenAISDKClient(WireApi.RESPONSES);}
    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        return OpenAIModelListing.list(OpenAIModelListing.OPENAI_ROOT, Secrets.configured().require(SECRET_ID).secret());
    }
}
