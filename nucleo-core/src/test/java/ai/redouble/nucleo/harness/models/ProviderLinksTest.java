/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The link between a catalog entry and its provider. Resolution looks only at providers of the
 * entry's addressing and kind, takes the first in key order that claims the model, else the first
 * that serves it, else none. A written catalog records every provider it was written for with
 * its platform and addressing, including a provider its entries name that the writer does not
 * carry when the platform is known from the entry or the record the catalog already had (its
 * addressing then the carried record's, else the platform's), and stamps each entry of a present
 * provider with that provider's platform.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class ProviderLinksTest {
    /** A provider that builds nothing: a key, a platform, which wire ids it claims and serves, and the addressing its ids are spelled in. */
    private record Stub(String key, String platform, String claimsPrefix, String servesPrefix, String addressing) implements ClientProvider<Client> {
        /** Addressed the way its platform is, as every provider is by default. */
        Stub(String key, String platform, String claimsPrefix, String servesPrefix) {
            this(key, platform, claimsPrefix, servesPrefix, platform);
        }

        @Override
        public Client createClient(ModelSpec spec) {
            throw new UnsupportedOperationException("a stub builds no client");
        }

        @Override
        public String credentialId() {
            return key + "-key";
        }

        @Override
        public boolean claims(String wireModelId) {
            return claimsPrefix != null && wireModelId.startsWith(claimsPrefix);
        }

        @Override
        public boolean serves(String wireModelId) {
            return servesPrefix == null || wireModelId.startsWith(servesPrefix);
        }
    }

    private static Map<String, ClientProvider<?>> providers(Stub... stubs) {
        Map<String, ClientProvider<?>> map = new HashMap<>();
        for (Stub stub : stubs) {
            map.put(stub.key(), stub);
        }
        return map;
    }

    @Test
    void aProviderThatClaimsTheModelWinsOverOneThatOnlyServesIt() {
        Map<String, ClientProvider<?>> providers = providers(new Stub("a-generic", "cloud", null, null),
                new Stub("b-vendor", "cloud", "vendor.", "vendor."));
        assertEquals("b-vendor", ProviderLinks.resolve(providers, "cloud", ModelKind.LLM, "vendor.model-1").key(),
                "the claimer, although the generic provider comes first in key order");
        assertEquals("a-generic", ProviderLinks.resolve(providers, "cloud", ModelKind.LLM, "other.model-1").key(),
                "nobody claims it: the first in key order that serves it");
    }

    @Test
    void theFirstClaimerInKeyOrderWins() {
        Map<String, ClientProvider<?>> providers = providers(new Stub("z-vendor", "cloud", "vendor.", null),
                new Stub("m-vendor", "cloud", "vendor.", null));
        assertEquals("m-vendor", ProviderLinks.resolve(providers, "cloud", ModelKind.LLM, "vendor.model-1").key(),
                "two claimers: key order settles it, so every run links the same way");
    }

    @Test
    void onlyProvidersOfThePlatformAndKindThatServeTheModelAreCandidates() {
        Map<String, ClientProvider<?>> providers = providers(new Stub("elsewhere", "other-cloud", "vendor.", null),
                new Stub("cloud-embeddings", "cloud", null, null), new Stub("narrow", "cloud", null, "narrow."));
        assertNull(ProviderLinks.resolve(providers, "cloud", ModelKind.LLM, "vendor.model-1"),
                "another platform's claimer, an embeddings provider and an LLM provider that does not serve the id: none fits");
        assertEquals("cloud-embeddings", ProviderLinks.resolve(providers, "cloud", ModelKind.EMBEDDINGS, "vendor.embed-1").key(),
                "an embeddings entry links to an embeddings provider");
        assertNull(ProviderLinks.resolve(providers, "nowhere", ModelKind.LLM, "vendor.model-1"), "a platform with no provider: none");
    }

    @Test
    void onlyAProviderOfTheEntrysAddressingIsACandidate() {
        Map<String, ClientProvider<?>> providers = providers(new Stub("a-own-endpoint", "cloud", "vendor.", null, "cloud-own"),
                new Stub("b-generic", "cloud", null, null));
        assertEquals("b-generic", ProviderLinks.resolve(providers, "cloud", ModelKind.LLM, "vendor.model-1").key(),
                "the claimer spells its ids for another endpoint of the platform, so it cannot take an id spelled for this one");
        assertEquals("a-own-endpoint", ProviderLinks.resolve(providers, "cloud-own", ModelKind.LLM, "vendor.model-1").key());
        assertNull(ProviderLinks.resolve(providers(new Stub("b-generic", "cloud", null, null)), "cloud-own", ModelKind.LLM, "vendor.model-1"),
                "an id spelled for an endpoint no provider here reaches: nothing here can serve it");
    }

    @Test
    void stampRecordsTheProvidersAndEachEntrysPlatform() throws Exception {
        ObjectNode catalog = (ObjectNode) NucleoJsonSerializer.readTree("""
                { "providers": { "carried": { "platform": "old-cloud", "addressing": "old-cloud-own" } },
                  "models": [
                    { "id": "here", "provider_key": "present", "wire_model_id": "w1" },
                    { "id": "own-word", "provider_key": "absent-a", "platform": "cloud", "wire_model_id": "w2" },
                    { "id": "recorded", "provider_key": "carried", "wire_model_id": "w3" },
                    { "id": "unknown", "provider_key": "absent-b", "wire_model_id": "w4" } ] }
                """);
        ProviderLinks.stamp(catalog, providers(new Stub("present", "cloud", null, null), new Stub("idle", "cloud", null, null, "cloud-own")));
        ObjectNode record = (ObjectNode) catalog.get(ProviderLinks.PROVIDERS);
        assertEquals(List.of("absent-a", "carried", "idle", "present"), List.copyOf(ProviderLinks.recordedKeys(record)),
                "every provider given, idle ones included, and every absent one whose platform is known; absent-b's is not");
        assertEquals("cloud", record.get("present").get(ProviderLinks.PLATFORM).asText());
        assertEquals("cloud", record.get("absent-a").get(ProviderLinks.PLATFORM).asText(), "from the entry's own word");
        assertEquals("old-cloud", record.get("carried").get(ProviderLinks.PLATFORM).asText(), "from the record the catalog already carried");
        assertEquals("cloud", record.get("present").get(ProviderLinks.ADDRESSING).asText(), "a provider addressed the way its platform is");
        assertEquals("cloud-own", record.get("idle").get(ProviderLinks.ADDRESSING).asText(), "a provider's own addressing");
        assertEquals("old-cloud-own", record.get("carried").get(ProviderLinks.ADDRESSING).asText(), "an absent one's, from the carried record");
        assertEquals("cloud", record.get("absent-a").get(ProviderLinks.ADDRESSING).asText(), "an absent one nothing records: its platform's");
        assertEquals("cloud", catalog.get("models").get(0).get(ProviderLinks.PLATFORM).asText(), "a present provider's entry is stamped");
        assertFalse(catalog.get("models").get(3).has(ProviderLinks.PLATFORM), "an absent provider's entry keeps what it said, here nothing");
    }
}
