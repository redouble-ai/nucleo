/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deployment listing read on the shape a Foundry resource answered live on 2026-09-15:
 * deployments by the id a person gave them, the base model kept as the note, anything not yet
 * succeeded left out, and deployments of another surface's base models left to that surface.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
class AzureFoundryDeploymentsTest {
    private static final String BODY = """
            {"data": [
              {"scale_settings": {"scale_type": "standard"}, "model": "claude-opus-5", "owner": "organization-owner",
               "id": "claude-opus-5", "status": "succeeded", "created_at": 1788658984, "updated_at": 1788658984, "object": "deployment"},
              {"scale_settings": {"scale_type": "standard"}, "model": "gpt-5.6-sol", "owner": "organization-owner",
               "id": "my-sol", "status": "succeeded", "created_at": 1788879004, "updated_at": 1788908748, "object": "deployment"},
              {"model": "gpt-5.6-terra", "id": "terra-soon", "status": "creating", "object": "deployment"}
            ], "object": "list"}
            """;

    @Test
    void deploymentsAreListedByTheirOwnIdWithTheBaseModelAsNote() throws IOException {
        List<DiscoveredModel> models = AzureFoundryDeployments.parse(BODY, model -> true);
        assertEquals(2, models.size(), "a deployment still being created is not reachable");
        assertEquals("claude-opus-5", models.get(0).wireModelId());
        assertEquals("deployment of claude-opus-5", models.get(0).note());
        assertEquals("my-sol", models.get(1).wireModelId(), "the wire id is the deployment's own name");
        assertEquals("deployment of gpt-5.6-sol", models.get(1).note());
    }

    @Test
    void theChatCompletionsSurfaceLeavesClaudeDeploymentsToTheAnthropicSurface() throws IOException {
        List<DiscoveredModel> models = AzureFoundryDeployments.parse(BODY, AzureFoundryOpenAIProvider::onChatCompletions);
        assertEquals(List.of("my-sol"), models.stream().map(DiscoveredModel::wireModelId).toList());
    }

    @Test
    void aBodyWithoutTheListIsRefused() {
        assertThrows(IOException.class, () -> AzureFoundryDeployments.parse("{\"error\": {\"code\": \"401\"}}", model -> true));
    }

    /**
     * Every spelling a person provides addresses the same resource: the bare hostname, or
     * the portal's endpoint with scheme and surface path. Without the normalization the
     * scheme gets prefixed twice ({@code https://https://...}) and DNS fails resolving the
     * hostname "https" - a live paste, 2026-09-18.
     */
    @Test
    void aPastedEndpointAddressesTheSameResourceAsTheBareHostname() {
        String root = "https://my-resource.services.ai.azure.com/openai/v1";
        assertEquals(root, AzureFoundryOpenAIClient.apiRootOf("my-resource.services.ai.azure.com"));
        assertEquals(root, AzureFoundryOpenAIClient.apiRootOf("https://my-resource.services.ai.azure.com"));
        assertEquals(root, AzureFoundryOpenAIClient.apiRootOf("https://my-resource.services.ai.azure.com/openai/v1"));
        assertEquals(root, AzureFoundryOpenAIClient.apiRootOf(" https://my-resource.services.ai.azure.com/ "));
    }
}
