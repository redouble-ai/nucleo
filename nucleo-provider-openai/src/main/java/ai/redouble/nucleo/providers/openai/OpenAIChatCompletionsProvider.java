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
 * Serves OpenAI chat models through OpenAI's own client on the Chat Completions API: the same
 * account, key and models as {@link OpenAIProvider}, on the older surface, for an entry that
 * must be called there. On this surface the GPT-5.6 family refuses tools together with a
 * reasoning effort, which the entry declares as {@code tools_suspend_reasoning}; the shipped
 * fragment carries no entries under this key, since every OpenAI model is served on the
 * Responses surface, so a deployment that wants one here adds it to its own catalog.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class OpenAIChatCompletionsProvider extends AbstractClientProvider<OpenAISDKClient> implements ModelDiscovery {
    @Override
    public String key() {return "openai-chat-completions";}

    @Override
    public String platform() {return "openai";}
    @Override
    public String credentialId() {return OpenAIProvider.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(OpenAIProvider.SHAPE);}
    @Override
    protected OpenAISDKClient newClient() {return new OpenAISDKClient(WireApi.CHAT_COMPLETIONS);}
    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        return OpenAIModelListing.list(OpenAIModelListing.OPENAI_ROOT, Secrets.configured().require(credentialId()).secret());
    }
}
