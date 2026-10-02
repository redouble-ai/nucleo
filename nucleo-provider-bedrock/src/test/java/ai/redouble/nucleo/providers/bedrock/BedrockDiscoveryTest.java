/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrock.model.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two pure halves of the Bedrock discovery on recorded shapes: a quota is matched to a
 * wire id by the family its prefix names and the model's own names, and nothing is matched
 * when AWS spells the model differently; the Mantle listing's {@code allowed_modes} become
 * {@code requiresLax} only when data sharing is the sole mode.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
class BedrockDiscoveryTest {

    private static final Map<String, Double> QUOTAS = Map.of(
            "Global cross-region model inference tokens per minute for Anthropic Claude Sonnet 5", 6_000_000.0,
            "Cross-region model inference tokens per minute for Anthropic Claude Sonnet 5", 6_000_000.0,
            "Cross-region model inference requests per minute for Anthropic Claude Haiku 4.5", 10_000.0,
            "Cross-region model inference tokens per minute for Anthropic Claude Sonnet 4.5 V1", 2_000_000.0,
            "On-demand model inference tokens per minute for Amazon Nova Micro", 4_000_000.0,
            "On-demand model inference tokens per minute for Meta Llama 3 8B Instruct", 600_000.0,
            "On-demand model inference tokens per minute for Ministral 14B 3.0", 100_000_000.0);

    private static FoundationModelSummary summary(String provider, String name) {
        return FoundationModelSummary.builder().providerName(provider).modelName(name).build();
    }

    @Test
    void thePrefixPicksTheFamilyAndTheNamesCompleteIt() {
        assertEquals(6_000_000, BedrockDiscovery.quota(QUOTAS, "global.anthropic.claude-sonnet-5", "tokens", summary("Anthropic", "Claude Sonnet 5")));
        assertEquals(6_000_000, BedrockDiscovery.quota(QUOTAS, "us.anthropic.claude-sonnet-5", "tokens", summary("Anthropic", "Claude Sonnet 5")));
        assertEquals(10_000, BedrockDiscovery.quota(QUOTAS, "us.anthropic.claude-haiku-4-5-20251001-v1:0", "requests", summary("Anthropic", "Claude Haiku 4.5")));
        assertEquals(4_000_000, BedrockDiscovery.quota(QUOTAS, "amazon.nova-micro-v1:0", "tokens", summary("Amazon", "Nova Micro")));
        // a vendor prefix as short as a geography's (meta., luma.) is still an on-demand id
        assertEquals(600_000, BedrockDiscovery.quota(QUOTAS, "meta.llama3-8b-instruct-v1:0", "tokens", summary("Meta", "Llama 3 8B Instruct")));
    }

    @Test
    void theV1SuffixAwsAppendsIsTried() {
        assertEquals(2_000_000, BedrockDiscovery.quota(QUOTAS, "us.anthropic.claude-sonnet-4-5-20250929-v1:0", "tokens", summary("Anthropic", "Claude Sonnet 4.5")));
    }

    @Test
    void aNameAwsSpellsDifferentlyMatchesNothing() {
        // The model calls itself "Ministral 3 14B"; the quota says "Ministral 14B 3.0". Reported, not guessed.
        assertNull(BedrockDiscovery.quota(QUOTAS, "mistral.ministral-3-14b-instruct", "tokens", summary("Mistral AI", "Ministral 3 14B")));
        assertNull(BedrockDiscovery.quota(QUOTAS, "us.anthropic.claude-sonnet-5", "requests", summary("Anthropic", "Claude Sonnet 5")));
    }

    /**
     * The control plane states each model's inference types and modalities; the listing carries
     * them as the provider spells them, and only an id the runtime can invoke as listed is a
     * model the account can reach.
     */
    @Test
    void onlyOnDemandIdsAreInvokableAndModalitiesAreCarriedAsTheProviderSpellsThem() {
        FoundationModelSummary provisionedOnly = FoundationModelSummary.builder().modelId("amazon.titan-embed-image-v1:0")
                .providerName("Amazon").modelName("Titan Multimodal Embeddings G1")
                .inferenceTypesSupportedWithStrings("PROVISIONED").build();
        FoundationModelSummary onDemand = FoundationModelSummary.builder().modelId("amazon.titan-embed-image-v1")
                .providerName("Amazon").modelName("Titan Multimodal Embeddings G1")
                .inputModalitiesWithStrings("TEXT", "IMAGE").outputModalitiesWithStrings("EMBEDDING")
                .inferenceTypesSupportedWithStrings("ON_DEMAND").build();
        FoundationModelSummary speech = FoundationModelSummary.builder().modelId("amazon.nova-2-sonic-v1:0")
                .providerName("Amazon").modelName("Nova 2 Sonic")
                .inputModalitiesWithStrings("SPEECH").outputModalitiesWithStrings("SPEECH", "TEXT")
                .inferenceTypesSupportedWithStrings("ON_DEMAND").build();
        assertFalse(BedrockDiscovery.onDemand(provisionedOnly), "provisioned throughput only: a plain invocation is refused");
        assertTrue(BedrockDiscovery.onDemand(onDemand));
        BedrockDiscovery.Quotas none = new BedrockDiscovery.Quotas(Map.of(), null);
        DiscoveredModel embeddings = BedrockDiscovery.discovered(onDemand.modelId(), onDemand, none, true);
        assertEquals(List.of("TEXT", "IMAGE"), embeddings.inputModalities());
        assertEquals(List.of("EMBEDDING"), embeddings.outputModalities());
        assertEquals(Boolean.TRUE, embeddings.supportsVision(), "image input is vision");
        assertEquals(Boolean.TRUE, embeddings.embeddingsOutput());
        assertEquals(Boolean.FALSE, embeddings.textSeat());
        DiscoveredModel sonic = BedrockDiscovery.discovered(speech.modelId(), speech, none, true);
        assertEquals(List.of("SPEECH"), sonic.inputModalities(), "a modality the SDK's enum does not know survives as the provider's word");
        assertTrue(sonic.otherModality(), "speech in: no text seat, no embeddings - a seatless listing");
        DiscoveredModel bareProfile = BedrockDiscovery.discovered("us.vendor.model", null, none, true);
        assertNull(bareProfile.inputModalities(), "a profile with nothing behind it states no modality");
        assertEquals(Boolean.TRUE, bareProfile.onDemand());
    }

    @Test
    void mantleAllowedModesBecomeRequiresLaxWhenZeroRetentionIsNotAmongThem() throws Exception {
        // Fable 5 as the account lists it: review and data-share allowed, zero retention not
        String body = """
                { "data": [
                  { "id": "anthropic.claude-fable-5", "data_retention": { "allowed_modes": ["aws_review", "provider_data_share"] } },
                  { "id": "anthropic.claude-opus-5", "data_retention": { "allowed_modes": ["none", "default", "provider_data_share"] } },
                  { "id": "openai.gpt-oss-120b" }
                ] }
                """;
        List<DiscoveredModel> models = BedrockDiscovery.parseMantle(body);
        assertEquals(3, models.size());
        assertEquals(Boolean.TRUE, models.get(0).requiresLax());
        assertEquals(Boolean.FALSE, models.get(1).requiresLax());
        assertNull(models.get(2).requiresLax(), "no retention block, no claim");
        assertTrue(models.get(0).note().contains("provider_data_share"));
    }
}
