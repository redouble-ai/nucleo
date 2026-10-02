/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

/**
 * Serves chat models on an endpoint that speaks OpenAI's Responses API without being OpenAI,
 * through the framework's own HTTP client: {@link OpenAICompatibleProvider} on the other surface,
 * same credential, same listing, same rule that its entries are the deployment's to write.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class OpenAICompatibleResponsesProvider extends OpenAICompatibleProvider {
    @Override
    public String key() {return "openai-compatible-responses";}

    @Override
    protected OpenAICompatibleClient newClient() {return new OpenAICompatibleClient(WireApi.RESPONSES);}
}
