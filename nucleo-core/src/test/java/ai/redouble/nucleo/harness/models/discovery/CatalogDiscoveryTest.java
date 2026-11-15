/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The discovery's rules over explicit sources, providers and pings, with no network: a
 * configured provider that lists has its seed entries matched against the listing and the
 * reachable ones written with the observed limits; one that cannot list has them pinged
 * blind; an unconfigured one has them left out and the credential named; the previous file's
 * entries and pins survive whatever happened to them; and a listed model no entry names is
 * reported as unknown, never invented.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
class CatalogDiscoveryTest {

    private static String entry(String id, String provider, String wire, int tpm) {
        return "{ \"id\": \"" + id + "\", \"identity\": \"" + id + "\", \"grade\": \"SMALL\", \"provider_key\": \"" + provider + "\","
                + " \"wire_model_id\": \"" + wire + "\", \"max_context_tokens\": 1000, \"max_output_tokens\": 100,"
                + " \"supports_vision\": false, \"thinking_mode\": \"NONE\", \"tpm\": " + tpm + ", \"rpm\": 100 }";
    }

    private static JsonModelsBackend catalog(boolean fragment, String pins, String... entries) {
        return new JsonModelsBackend(List.of(new JsonModelsBackend.Layer(fragment ? "seed" : "previous",
                "{ \"models\": [" + String.join(",", entries) + "]" + (pins != null ? ", \"pins\": " + pins : "") + " }", fragment)));
    }

    /** A catalog whose provider "alpha" bills in USD, for entries that carry a price. */
    private static JsonModelsBackend pricedCatalog(boolean fragment, String... entries) {
        return new JsonModelsBackend(List.of(new JsonModelsBackend.Layer(fragment ? "seed" : "previous",
                "{ \"provider_defaults\": { \"alpha\": { \"currency\": \"USD\" } }, \"models\": [" + String.join(",", entries) + "] }", fragment)));
    }

    /**
     * A provider scripted by the test: its key, whether it is configured, and what it lists.
     * Public with a no-arg constructor because the classpath scan instantiates every concrete
     * provider it finds, this one included, under its default key; the scripted instances the
     * tests build are handed to the discovery directly and never registered.
     */
    public static class Scripted extends AbstractClientProvider<LLMClient> implements ModelDiscovery {
        private String key = "scripted";
        private boolean configured;
        private List<String> wireIds;
        private boolean unreachable;
        private final Set<String> retired = new HashSet<>();
        private final Map<String, List<List<String>>> modalities = new HashMap<>();
        private final Map<String, DiscoveredModel.Prices> prices = new HashMap<>();
        private final Map<String, DiscoveredModel.ListedRates> listedRates = new HashMap<>();
        private final Map<String, List<DiscoveredModel.PriceOption>> priceOptions = new HashMap<>();
        private final Map<String, ThinkingMode> statedThinking = new HashMap<>();

        /** The listing states how the id's model thinks. */
        void stateThinking(String wireId, ThinkingMode mode) {statedThinking.put(wireId, mode);}

        /** The provider's price list states this standard price for the id's model under the name, one of several the listing cannot choose between. */
        void priceOption(String wireId, String name, double input, double output) {
            priceOptions.computeIfAbsent(wireId, k -> new ArrayList<>()).add(new DiscoveredModel.PriceOption(name, new DiscoveredModel.Prices(input, output, null, null, "USD")));
        }
        private java.util.function.Predicate<String> serves = wireId -> true;

        /** The listing marks these ids as retired by the vendor. */
        void markRetired(String... wireIds) {retired.addAll(List.of(wireIds));}

        /** The provider's price list prices the id at these rates per million, in USD. */
        void price(String wireId, double input, Double output) {prices.put(wireId, new DiscoveredModel.Prices(input, output, null, null, "USD"));}

        /** The provider's price list states these rows for the id's model, the row's name to its rate per million, in the currency. */
        void listRates(String wireId, String currency, Map<String, Double> perMillion) {listedRates.put(wireId, new DiscoveredModel.ListedRates(perMillion, currency));}

        /** The listing states what the id takes and produces, in the provider's words. */
        void markModalities(String wireId, List<String> inputs, List<String> outputs) {modalities.put(wireId, List.of(inputs, outputs));}

        /** This provider's client speaks only to the wire ids the predicate accepts. */
        void servesOnly(java.util.function.Predicate<String> serves) {this.serves = serves;}

        @Override
        public boolean serves(String wireModelId) {return serves.test(wireModelId);}

        /** Catalog ids are suffixed with the key, the way the Anthropic providers suffix their channel. */
        @Override
        public String catalogIdOf(String identity, String wireModelId) {return identity + "@" + key;}

        static Scripted listing(String key, String... wireIds) {
            Scripted provider = new Scripted();
            provider.key = key;
            provider.configured = true;
            provider.wireIds = List.of(wireIds);
            return provider;
        }

        /** Configured or not, and never listing: the discovery pings its entries blind. */
        static Scripted plain(String key, boolean configured) {
            Scripted provider = new Scripted();
            provider.key = key;
            provider.configured = configured;
            return provider;
        }

        /** Configured, and its endpoint not served where this process points: the listing fails on an unknown host, wrapped the way an SDK wraps it. */
        static Scripted unreachable(String key) {
            Scripted provider = new Scripted();
            provider.key = key;
            provider.configured = true;
            provider.unreachable = true;
            return provider;
        }

        @Override
        public String key() {return key;}

        /** One platform per key stem: "alpha" and "alpha-embeddings" are one platform, "beta" another. */
        @Override
        public String platform() {return key.split("-")[0];}

        @Override
        public String credentialId() {return key + "-key";}

        @Override
        public boolean configured() {return configured;}

        @Override
        protected LLMClient newClient() {throw new UnsupportedOperationException("the test provider builds no client");}

        @Override
        public List<DiscoveredModel> listModels() {
            if (unreachable) {
                throw new IllegalStateException("listing failed", new UnknownHostException(key + ".nowhere.example: nodename nor servname provided, or not known"));
            }
            if (wireIds == null) {
                throw new UnsupportedOperationException("this scripted provider does not list");
            }
            List<DiscoveredModel> models = new ArrayList<>();
            for (String wireId : wireIds) {
                List<List<String>> stated = modalities.get(wireId);
                models.add(new DiscoveredModel(wireId, stated != null ? stated.get(0) : null, stated != null ? stated.get(1) : null,
                        null, null, retired.contains(wireId) ? Boolean.TRUE : null, null, null, null, null, prices.get(wireId), listedRates.get(wireId), priceOptions.get(wireId), statedThinking.get(wireId)));
            }
            return models;
        }
    }

    private static ProbeOutcome ok(ModelSpec spec, long tokensLimit) {
        ProbeOutcome outcome = new ProbeOutcome();
        outcome.setSpecId(spec.getId());
        outcome.setProvider(spec.getProviderKey());
        outcome.setStatus(ProbeOutcome.Status.OK);
        outcome.setProbedAt(Instant.now());
        outcome.setLatencyMs(12L);
        outcome.setObservedTokensLimit(tokensLimit);
        return outcome;
    }

    private static ProbeOutcome down(ModelSpec spec) {
        ProbeOutcome outcome = new ProbeOutcome();
        outcome.setSpecId(spec.getId());
        outcome.setProvider(spec.getProviderKey());
        outcome.setStatus(ProbeOutcome.Status.FAILED);
        outcome.setClassification(ProbeOutcome.Classification.AUTH);
        outcome.setErrorClass("UnauthorizedException");
        outcome.setErrorMessage("key rejected");
        outcome.setProbedAt(Instant.now());
        outcome.setLatencyMs(3L);
        return outcome;
    }

    private static Set<String> ids(JsonNode catalog) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode model : catalog.get("models")) {
            ids.add(model.get("id").asText());
        }
        return ids;
    }

    @Test
    void listedAndReachableEntriesAreWrittenWithObservedLimits() {
        JsonModelsBackend seed = catalog(true, null,
                entry("a-listed", "alpha", "wire-a", 1000),
                entry("a-gone", "alpha", "wire-gone", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-a", "wire-new"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(Set.of("a-listed"), ids(result.catalog()));
        assertEquals(55000, result.catalog().get("models").get(0).get("tpm").intValue(), "the header's limit replaces the seed's");
        assertEquals(CatalogDiscovery.Verdict.NOT_LISTED, verdict(result, "a-gone"));
        assertEquals(List.of("wire-new"), result.unknown().get("alpha").stream().map(DiscoveredModel::wireModelId).toList());
        assertTrue(result.report().contains("wire-new"), result.report());
    }

    @Test
    void aModelTwoProvidersListIsReportedUnknownOnce() {
        JsonModelsBackend seed = catalog(true, null, entry("a-listed", "alpha", "wire-a", 1000));
        // listed on demand and on a geography profile by both providers: one model, one line
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "wire-a", "wire-shared", "eu.wire-shared"),
                "alpha-embeddings", Scripted.listing("alpha-embeddings", "wire-a", "wire-shared", "eu.wire-shared"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(1, result.unknown().values().stream().mapToInt(List::size).sum());
        assertEquals("eu.wire-shared", result.unknown().get("alpha").get(0).wireModelId(), "named by the region's own profile");
        assertTrue(result.report().contains("tpm 1,000 -> 55,000 (observed)"), result.report());
    }

    @Test
    void aProviderWhoseListingFailsHasItsEntriesPingedBlind() {
        JsonModelsBackend seed = catalog(true, null, entry("b-up", "beta", "wire-up", 1000), entry("b-down", "beta", "wire-down", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("beta", Scripted.plain("beta", true));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers,
                (p, s) -> s.getId().equals("b-up") ? ok(s, 2000) : down(s)).discover();
        assertEquals(Set.of("b-up"), ids(result.catalog()));
        assertEquals(CatalogDiscovery.ProviderStatus.LISTING_FAILED, result.providers().get(0).status());
        assertEquals(CatalogDiscovery.Verdict.UNREACHABLE, verdict(result, "b-down"));
        assertTrue(result.report().contains("AUTH UnauthorizedException: key rejected"), result.report());
    }

    @Test
    void anUnconfiguredProviderLeavesItsSeedEntriesOutAndNamesTheCredential() {
        JsonModelsBackend seed = catalog(true, null, entry("g-one", "gamma", "wire-g", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("gamma", Scripted.plain("gamma", false));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            throw new AssertionError("nothing is pinged on an unconfigured provider");
        }).discover();
        assertTrue(ids(result.catalog()).isEmpty());
        assertFalse(result.anythingConfigured());
        assertTrue(result.report().contains("NOT CONFIGURED - provide"), result.report());
        assertTrue(result.report().contains("GAMMA_KEY"), result.report());
    }

    @Test
    void aProviderThisClasspathLacksHasItsEntriesCarriedThroughAndTheRecordNamesEveryProvider() {
        JsonModelsBackend seed = catalog(true, null, entry("a-listed", "alpha", "wire-a", 1000));
        // written by a classpath that also carried "delta", of the alpha platform; this one carries alpha alone
        JsonModelsBackend previous = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("previous",
                "{ \"providers\": { \"alpha\": { \"platform\": \"alpha\" }, \"delta\": { \"platform\": \"alpha\" } }, \"models\": ["
                        + entry("a-listed", "alpha", "wire-a", 1000) + "," + entry("d-only", "delta", "wire-d", 1000)
                        + "], \"pins\": { \"SMALL\": [\"d-only\"] } }", false)));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-a"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(Set.of("a-listed", "d-only"), ids(result.catalog()), "the previous file is never shrunk, whatever this classpath carries");
        JsonNode dOnly = result.catalog().get("models").get(1);
        assertEquals("delta", dOnly.get("provider_key").asText(), "carried through exactly as written");
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "d-only"));
        assertEquals(CatalogDiscovery.ProviderStatus.NOT_ON_CLASSPATH,
                result.providers().stream().filter(p -> p.key().equals("delta")).findFirst().orElseThrow().status());
        assertTrue(result.report().contains("not on this classpath"), result.report());
        assertEquals("d-only", result.catalog().get("pins").get("SMALL").get(0).asText(), "a place in an order for a carried entry stands");
        JsonNode record = result.catalog().get(ProviderLinks.PROVIDERS);
        assertEquals("alpha", record.get("alpha").get(ProviderLinks.PLATFORM).asText(), "the provider this run carries");
        assertEquals("alpha", record.get("delta").get(ProviderLinks.PLATFORM).asText(), "the absent one, from the previous record");
        assertEquals("alpha", result.catalog().get("models").get(0).get(ProviderLinks.PLATFORM).asText(), "each entry states its platform");
    }

    @Test
    void thePreviousFileIsNeverShrunkAndKeepsItsPins() {
        JsonModelsBackend seed = catalog(true, null, entry("a-listed", "alpha", "wire-a", 1000));
        JsonModelsBackend previous = catalog(false, "{ \"SMALL\": [\"a-old\", \"a-listed\"] }",
                entry("a-old", "alpha", "wire-old", 7000).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"DEPRECATED\""),
                entry("a-listed", "alpha", "wire-a", 9000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-a"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(Set.of("a-old", "a-listed"), ids(result.catalog()));
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "a-old"), "a closed entry is never pinged");
        assertTrue(result.entries().stream().filter(e -> e.id().equals("a-old")).findFirst().orElseThrow().kept());
        assertEquals("[\"a-old\",\"a-listed\"]", result.catalog().get("pins").get("SMALL").toString(), "the order is carried as written");
        JsonNode old = result.catalog().get("models").get(0);
        assertEquals("DEPRECATED", old.get("status").asText(), "a hand-set field survives");
        assertEquals(7000, old.get("tpm").intValue(), "a closed previous entry keeps its numbers");
        assertTrue(result.report().contains("DEPRECATED, not pinged"), result.report());
    }

    @Test
    void theResultLoadsAsACatalog() {
        JsonModelsBackend seed = catalog(true, null, entry("a-listed", "alpha", "wire-a", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-a"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000)).discover();
        // read by the providers it was written for; a classpath without "alpha" would leave its entries out
        JsonModelsBackend written = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("written", result.catalog().toString(), false)), providers);
        assertEquals(55000, written.spec("a-listed").getTpm());
    }

    private static ProbeOutcome overloaded(ModelSpec spec) {
        ProbeOutcome outcome = new ProbeOutcome();
        outcome.setSpecId(spec.getId());
        outcome.setProvider(spec.getProviderKey());
        outcome.setStatus(ProbeOutcome.Status.FAILED);
        outcome.setClassification(ProbeOutcome.Classification.AVAILABILITY);
        outcome.setErrorClass("OverloadRetryException");
        outcome.setErrorMessage("529: overloaded");
        outcome.setProbedAt(Instant.now());
        outcome.setLatencyMs(3L);
        return outcome;
    }

    @Test
    void aTransientFailureIsRetriedAndAReachableEntryIsWrittenNormally() {
        JsonModelsBackend seed = catalog(true, null, entry("a-busy", "alpha", "wire-a", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-a"));
        int[] pings = {0};
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers,
                (p, s) -> ++pings[0] < CatalogDiscovery.PING_ATTEMPTS ? overloaded(s) : ok(s, 55000), Duration.ZERO).discover();
        assertEquals(CatalogDiscovery.PING_ATTEMPTS, pings[0], "every attempt but the last answered transient");
        assertEquals(CatalogDiscovery.Verdict.REACHABLE, verdict(result, "a-busy"));
        assertEquals(55000, result.catalog().get("models").get(0).get("tpm").intValue());
    }

    @Test
    void anEntryStillTransientAfterEveryAttemptIsKeptUnverifiedWithItsOwnLimits() {
        JsonModelsBackend seed = catalog(true, null, entry("a-busy", "alpha", "wire-a", 1000), entry("a-dead", "alpha", "wire-dead", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-a", "wire-dead"));
        int[] pings = {0};
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers,
                (p, s) -> {
                    pings[0]++;
                    return s.getId().equals("a-busy") ? overloaded(s) : down(s);
                }, Duration.ZERO).discover();
        assertEquals(CatalogDiscovery.PING_ATTEMPTS + 1, pings[0], "the transient entry used every attempt, the rejected one only one");
        assertEquals(CatalogDiscovery.Verdict.UNVERIFIED, verdict(result, "a-busy"));
        assertEquals(CatalogDiscovery.Verdict.UNREACHABLE, verdict(result, "a-dead"));
        assertEquals(Set.of("a-busy"), ids(result.catalog()), "unverified is kept, rejected is dropped");
        assertEquals(1000, result.catalog().get("models").get(0).get("tpm").intValue(), "kept with the limit it had");
        assertTrue(result.report().contains("unverified - run again"), result.report());
    }

    @Test
    void aSeedEntryOnAnotherGeographysProfileIsRewrittenToThisRegionsAndPingedAsWritten() {
        JsonModelsBackend seed = catalog(true, null,
                entry("opus", "alpha", "us.vendor.opus", 1000),
                entry("haiku-global", "alpha", "global.vendor.haiku", 1000),
                entry("sonnet", "alpha", "us.vendor.sonnet", 1000));
        // an EU region: its own geography's profiles, the global ones, and no us. at all;
        // sonnet is listed under two geographies here, which is ambiguous and stays unmatched.
        // A second key of the same platform lists the same profiles (as the four Bedrock
        // providers do); every model it lists is named by an entry on the platform, so it
        // reports nothing as a person's chore
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "eu.vendor.opus", "global.vendor.haiku", "eu.vendor.sonnet", "apac.vendor.sonnet"),
                "alpha-embeddings", Scripted.listing("alpha-embeddings", "eu.vendor.opus", "global.vendor.haiku", "eu.vendor.sonnet", "apac.vendor.sonnet"));
        List<String> pinged = new ArrayList<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            pinged.add(s.getWireModelId());
            return ok(s, 55000);
        }).discover();
        assertEquals(List.of("eu.vendor.opus", "global.vendor.haiku"), pinged, "what is pinged is what is written");
        assertEquals(Set.of("opus", "haiku-global"), ids(result.catalog()));
        JsonNode opus = result.catalog().get("models").get(0);
        assertEquals("eu.vendor.opus", opus.get("wire_model_id").asText());
        assertEquals("opus", opus.get("id").asText(), "the catalog id stays");
        assertEquals(CatalogDiscovery.Verdict.REACHABLE, verdict(result, "opus"));
        assertEquals(CatalogDiscovery.Verdict.NOT_LISTED, verdict(result, "sonnet"));
        assertTrue(result.report().contains("[us.vendor.opus -> eu.vendor.opus, this region's own profile for the model]"), result.report());
        assertTrue(result.report().contains("more than one geography, [apac.vendor.sonnet, eu.vendor.sonnet]"), result.report());
        assertTrue(result.unknown().isEmpty(), "every listed id names a model some entry already names: nothing is unknown " + result.unknown());
    }

    @Test
    void aBareOnDemandIdIsWrittenAsTheRegionsProfileWhenItHasOne() {
        JsonModelsBackend seed = catalog(true, null,
                entry("nova", "alpha", "vendor.nova", 1000),
                entry("plain", "alpha", "vendor.plain", 1000));
        // the region lists nova both on demand and on its profile, plain on demand only
        Map<String, ClientProvider<?>> providers = Map.of("alpha",
                Scripted.listing("alpha", "vendor.nova", "eu.vendor.nova", "vendor.plain"));
        List<String> pinged = new ArrayList<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            pinged.add(s.getWireModelId());
            return ok(s, 55000);
        }).discover();
        assertEquals(List.of("eu.vendor.nova", "vendor.plain"), pinged);
        assertEquals("eu.vendor.nova", result.catalog().get("models").get(0).get("wire_model_id").asText());
        assertEquals("vendor.plain", result.catalog().get("models").get(1).get("wire_model_id").asText());
    }

    @Test
    void geographyRules() {
        assertEquals(List.of("eu.v.m"), CatalogDiscovery.regionsProfiles("us.v.m", List.of("eu.v.m", "global.v.m", "eu.v.other")));
        assertEquals(List.of(), CatalogDiscovery.regionsProfiles("global.v.m", List.of("eu.v.m")), "global is the same string everywhere");
        assertEquals(List.of("eu.v.m"), CatalogDiscovery.regionsProfiles("v.m", List.of("v.m", "eu.v.m")), "a bare id takes the region's profile");
        assertEquals(List.of(), CatalogDiscovery.regionsProfiles("us.v.m", List.of("us.v.m")), "the id itself is not its twin");
        assertEquals(List.of("apac.v.m", "eu.v.m"), CatalogDiscovery.regionsProfiles("us.v.m", List.of("eu.v.m", "apac.v.m")), "two candidates is a person's call");
        assertEquals(List.of(), CatalogDiscovery.regionsProfiles("qwen.x", List.of("meta.x", "xai.x")), "vendor prefixes are not geographies");
        assertEquals("v.m", ModelLineage.bare("us.v.m"));
        assertEquals("v.m", ModelLineage.bare("global.v.m"));
        assertEquals("qwen.x", ModelLineage.bare("qwen.x"));
        assertEquals("v.m", ModelLineage.bare("us-gov.v.m"));
    }

    private static JsonNode written(CatalogDiscovery.Result result, String id) {
        for (JsonNode model : result.catalog().get("models")) {
            if (model.get("id").asText().equals(id)) {
                return model;
            }
        }
        throw new AssertionError(id + " is not in the written catalog: " + ids(result.catalog()));
    }

    @Test
    void aNewerVersionOfAKnownModelIsAddedWithTheAncestorsShapeAndPingedAsWritten() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-4.8", "alpha", "us.v.opus-4-8", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "us.v.opus-4-8", "us.v.opus-5", "v.opus-5"));
        List<String> pinged = new ArrayList<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            pinged.add(s.getWireModelId());
            return ok(s, 55000);
        }).discover();
        assertEquals(List.of("us.v.opus-4-8", "us.v.opus-5"), pinged, "the addition is pinged on the region's profile");
        JsonNode added = written(result, "opus-5@alpha");
        assertEquals("opus-5", added.get("identity").asText());
        assertEquals("us.v.opus-5", added.get("wire_model_id").asText());
        assertEquals("SMALL", added.get("grade").asText(), "inherited from opus-4.8");
        assertEquals(55000, added.get("tpm").intValue(), "then corrected by the ping");
        assertTrue(added.get("note").asText().contains("newer than opus-4.8"), added.get("note").asText());
        assertFalse(added.has("status"), "open, like its ancestor");
        assertTrue(result.report().contains("+ opus-5@alpha"), result.report());
        assertTrue(result.unknown().isEmpty(), "nothing is left for a person: " + result.unknown());
    }

    @Test
    void aModelAddedByALaterKeyOfThePlatformIsNotLeftInAnEarlierKeysChoreList() {
        // alpha is visited first and has no nova entry; alpha-mantle has the ancestor and adds
        // nova-2-lite, so by the end the platform names the model and nobody has to write it
        JsonModelsBackend seed = catalog(true, null, entry("nova-lite", "alpha-mantle", "v.nova-lite", 1000));
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "v.nova-2-lite"),
                "alpha-mantle", Scripted.listing("alpha-mantle", "v.nova-lite", "v.nova-2-lite"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(Set.of("nova-lite", "nova-2-lite@alpha-mantle"), ids(result.catalog()));
        assertTrue(result.unknown().isEmpty(), result.unknown().toString());
    }

    @Test
    void anotherSurfacesSpellingOfAKnownModelIsNeitherAddedNorAChore() {
        // the runtime surface spells it v.oss-120b-1:0, the Mantle surface v.oss-120b: one model,
        // one identity, already in the catalog on the platform
        JsonModelsBackend seed = catalog(true, null, entry("oss-120b", "alpha", "v.oss-120b-1:0", 1000));
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "v.oss-120b-1:0"),
                "alpha-mantle", Scripted.listing("alpha-mantle", "v.oss-120b"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(Set.of("oss-120b"), ids(result.catalog()));
        assertTrue(result.unknown().isEmpty(), result.unknown().toString());
    }

    @Test
    void aNewerVersionOfADisabledModelArrivesDisabledAndNothingDisabledIsPinged() {
        JsonModelsBackend seed = catalog(true, null,
                entry("opus-4.8", "alpha", "us.v.opus-4-8", 1000).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"DISABLED\""));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "us.v.opus-4-8", "us.v.opus-5"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            throw new AssertionError("a disabled model is never pinged: " + s.getId());
        }).discover();
        assertEquals(Set.of("opus-4.8", "opus-5@alpha"), ids(result.catalog()));
        assertEquals("DISABLED", written(result, "opus-5@alpha").get("status").asText());
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "opus-4.8"));
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "opus-5@alpha"));
    }

    @Test
    void aModelDisabledOnAnotherKeyOfThePlatformArrivesDisabledAndOnAnotherPlatformIsJustUnknown() {
        JsonModelsBackend seed = catalog(true, null,
                entry("opus-5", "alpha", "us.v.opus-5", 1000).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"DISABLED\""));
        // alpha-mantle is the same platform as alpha (same key stem); beta is another
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "us.v.opus-5"),
                "alpha-mantle", Scripted.listing("alpha-mantle", "v.opus-5"),
                "beta", Scripted.listing("beta", "v.opus-5"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            throw new AssertionError("nothing here is open to ping: " + s.getId());
        }).discover();
        JsonNode mantle = written(result, "opus-5@alpha-mantle");
        assertEquals("DISABLED", mantle.get("status").asText());
        assertEquals("alpha-mantle", mantle.get("provider_key").asText());
        assertTrue(mantle.get("note").asText().contains("disabled on alpha"), mantle.get("note").asText());
        assertEquals(List.of("v.opus-5"), result.unknown().get("beta").stream().map(DiscoveredModel::wireModelId).toList(),
                "a disable does not cross platforms");
        assertTrue(result.report().contains("No previous file: a first pull"), result.report());
        assertTrue(result.report().contains("Pins:\n  none:"), "a first pull has no pins and says so: " + result.report());
    }

    @Test
    void aNewerVersionOfAPinnedModelIsAnnouncedAndThePinIsUntouched() {
        JsonModelsBackend previous = catalog(false, "{ \"SMALL\": [\"opus-4.8\"] }", entry("opus-4.8", "alpha", "us.v.opus-4-8", 1000));
        JsonModelsBackend seed = catalog(true, null);
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "us.v.opus-4-8", "us.v.opus-5"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals("opus-4.8", result.catalog().get("pins").get("SMALL").get(0).asText(), "the order is a person's");
        assertEquals(List.of("opus-5@alpha is newer than opus-4.8, which is pinned; the pin is unchanged"), result.newerThanPinned());
        assertTrue(result.report().startsWith("Catalog discovery"), result.report());
        assertTrue(result.report().split("\n")[1].startsWith("NEWER THAN A PIN"), "announced at the top: " + result.report());
        assertTrue(result.report().contains("Previous file: previous, 1 entries"), "the run says which file it read: " + result.report());
        assertTrue(result.report().contains("Pins:\n  SMALL 1     opus-4.8                           SKIPPED"),
                "the pinned entry is the deployment's file's, kept as it was and never re-evaluated: " + result.report());
    }

    @Test
    void anOlderVersionTheAccountListsIsLeftOutUnlessAskedFor() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-5", "alpha", "us.v.opus-5", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "us.v.opus-5", "us.v.opus-4-8"));
        CatalogDiscovery.Result quiet = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000)).discover();
        assertEquals(Set.of("opus-5"), ids(quiet.catalog()));
        assertEquals(1, quiet.olderLeftOut().size(), quiet.olderLeftOut().toString());
        // the scripted provider reads identities the generic way, so the listed one is spelled opus-4-8
        assertTrue(quiet.olderLeftOut().get(0).contains("opus-4-8 is not newer than opus-5"), quiet.olderLeftOut().get(0));
        assertTrue(quiet.report().contains("--include-older"), quiet.report());
        CatalogDiscovery.Result asked = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000), Duration.ZERO, true).discover();
        assertEquals(Set.of("opus-5", "opus-4-8@alpha"), ids(asked.catalog()));
        assertEquals("SMALL", written(asked, "opus-4-8@alpha").get("grade").asText(), "inherits from the newest of the family");
    }

    @Test
    void aFirstPullKeepsOnlyTheNewestOfEachFamilyPerKey() {
        JsonModelsBackend seed = catalog(true, null,
                entry("opus-4.8", "alpha", "us.v.opus-4-8", 1000),
                entry("opus-5", "alpha", "us.v.opus-5", 1000),
                entry("haiku-4.5", "alpha", "us.v.haiku-4-5", 1000),
                entry("opus-4.8-b", "beta", "v.opus-4-8", 1000).replace("\"identity\": \"opus-4.8-b\"", "\"identity\": \"opus-4.8\""));
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "us.v.opus-4-8", "us.v.opus-5", "us.v.haiku-4-5"),
                "beta", Scripted.listing("beta", "v.opus-4-8"));
        List<String> pinged = new ArrayList<>();
        CatalogDiscovery.Result first = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            pinged.add(s.getId());
            return ok(s, 55000);
        }).discover();
        assertEquals(Set.of("opus-5", "haiku-4.5", "opus-4.8-b"), ids(first.catalog()), "the older opus on alpha is out; beta's only opus stays");
        assertEquals(CatalogDiscovery.Verdict.OLDER, verdict(first, "opus-4.8"));
        assertFalse(pinged.contains("opus-4.8"), "and it was not pinged");
        CatalogDiscovery.Result all = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000), Duration.ZERO, true).discover();
        assertEquals(Set.of("opus-4.8", "opus-5", "haiku-4.5", "opus-4.8-b"), ids(all.catalog()));
        JsonModelsBackend previous = catalog(false, null, entry("opus-4.8", "alpha", "us.v.opus-4-8", 1000), entry("opus-5", "alpha", "us.v.opus-5", 1000));
        CatalogDiscovery.Result later = new CatalogDiscovery(seed, previous, providers, (p, s) -> ok(s, 55000)).discover();
        assertTrue(ids(later.catalog()).contains("opus-4.8"), "a previous file is never shrunk, whatever its versions");
    }

    @Test
    void aModelTheVendorRetiredIsMarkedDeprecatedAndNotPinged() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-4.8", "alpha", "us.v.opus-4-8", 1000));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-4-8");
        alpha.markRetired("us.v.opus-4-8");
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> {
            throw new AssertionError("a retired model is not worth a call: " + s.getId());
        }).discover();
        assertEquals("DEPRECATED", written(result, "opus-4.8").get("status").asText());
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "opus-4.8"));
        assertTrue(result.report().contains("marks it retired"), result.report());
    }

    /**
     * A model the account offers in a modality the runtime has no client for is still a fact
     * about the account: it is written as an entry stating what it takes and produces, with no
     * grade, no ceilings and no prices, never pinged, and the catalog loads it as a seatless
     * spec no picker can land on.
     */
    @Test
    void aModelOfAnotherModalityIsWrittenAsASeatlessEntryAndNeverPinged() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-5", "alpha", "us.v.opus-5", 1000));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-5", "v.canvas", "v.sonic");
        alpha.markModalities("v.canvas", List.of("TEXT", "IMAGE"), List.of("IMAGE"));
        alpha.markModalities("v.sonic", List.of("SPEECH"), List.of("SPEECH", "TEXT"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> {
            if (!"opus-5".equals(s.getId())) {
                throw new AssertionError("a seatless entry is never pinged: " + s.getId());
            }
            return ok(s, 55000);
        }).discover();
        assertTrue(result.unknown().isEmpty(), "nothing for a person or a classifier to write: " + result.unknown());
        assertTrue(result.unserved().isEmpty(), result.unserved().toString());
        JsonNode canvas = written(result, "canvas@alpha");
        assertEquals(List.of("TEXT", "IMAGE"), texts(canvas.get("input_modalities")), "what it takes, as the listing said");
        assertEquals(List.of("IMAGE"), texts(canvas.get("output_modalities")), "what it produces, as the listing said");
        assertTrue(canvas.get("supports_vision").asBoolean(), "image input is vision, the runtime's word for it");
        assertFalse(canvas.has("grade"), "no grade: an image model is on no rung");
        assertFalse(canvas.has("max_context_tokens") || canvas.has("tpm"), "nothing a call would need, since none is ever made");
        assertTrue(canvas.get("note").asText().contains("no seat"), canvas.get("note").asText());
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "canvas@alpha"));
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "sonic@alpha"), "speech in, speech and text out: no text seat either");
        JsonModelsBackend loaded = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("written", result.catalog().toString(), false)),
                Map.of("alpha", alpha));
        ModelSpec spec = loaded.spec("canvas@alpha");
        assertFalse(spec.hasSeat(), "the loaded spec has no seat");
        assertEquals(List.of("IMAGE"), spec.getOutputModalities());
        assertTrue(result.report().contains("no seat in the runtime, not pinged"), result.report());
    }

    /**
     * A listed model no key of its platform has a client for is a fact the runtime cannot act
     * on: an embeddings family with its own request shape on a key that speaks another, or a
     * vendor the surface's SDK does not speak. It is reported once, on its own line, and never
     * handed to the classifier, whose entry would fail on its first call.
     */
    @Test
    void aListedModelNoKeyServesIsReportedUnservedAndNeverClassified() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-5", "alpha", "us.v.opus-5", 1000));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-5", "v.other-vendor-9", "m.only-here");
        alpha.servesOnly(wireId -> wireId.contains("opus"));
        Scripted alphaEmbeddings = Scripted.listing("alpha-embeddings", "us.v.opus-5", "v.other-vendor-9", "t.embed-7");
        alphaEmbeddings.servesOnly(wireId -> wireId.contains("opus"));
        alphaEmbeddings.markModalities("t.embed-7", List.of("TEXT"), List.of("EMBEDDING"));
        // serves every family, but its own listing does not name m.only-here: no home for it
        Scripted alphaGeneral = Scripted.listing("alpha-general", "us.v.opus-5");
        CatalogDiscovery.ClassifierCall classifier = (provider, spec, prompt) -> {
            throw new AssertionError("an unserved listing is never classified: " + prompt);
        };
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null,
                Map.of("alpha", alpha, "alpha-embeddings", alphaEmbeddings, "alpha-general", alphaGeneral), (p, s) -> ok(s, 55000),
                Duration.ZERO, false, null, classifier, CatalogDiscovery.permitting()).discover();
        assertEquals(List.of("v.other-vendor-9", "m.only-here", "t.embed-7"), result.unserved(), "each once, whichever keys listed it");
        assertTrue(result.unknown().isEmpty(), "not a chore for a person either: " + result.unknown());
        assertTrue(result.report().contains("no client in the runtime speaks its request shape"), result.report());
    }

    /**
     * An embeddings model the platform lists is placed on the embeddings key, an LLM on an LLM
     * key: the shared listing does not sort that out, the modalities do.
     */
    @Test
    void anEmbeddingsListingRidesTheEmbeddingsKeyOfItsPlatform() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-5", "alpha", "us.v.opus-5", 1000),
                entry("embed-1", "alpha-embeddings", "c.embed-1", 1000).replace("\"grade\": \"SMALL\"", "\"embedding_dimensions\": 1024"));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-5", "c.embed-2");
        alpha.markModalities("c.embed-2", List.of("TEXT"), List.of("EMBEDDING"));
        Scripted alphaEmbeddings = Scripted.listing("alpha-embeddings", "us.v.opus-5", "c.embed-2");
        alphaEmbeddings.markModalities("c.embed-2", List.of("TEXT"), List.of("EMBEDDING"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null,
                Map.of("alpha", alpha, "alpha-embeddings", alphaEmbeddings), (p, s) -> ok(s, 55000)).discover();
        assertEquals("alpha-embeddings", written(result, "embed-2@alpha-embeddings").get("provider_key").asText(),
                "the embeddings key holds it, though the LLM key listed it first: " + ids(result.catalog()));
        assertFalse(ids(result.catalog()).contains("embed-2@alpha"), "and the LLM key does not double it");
        assertTrue(result.unserved().isEmpty(), result.unserved().toString());
    }

    /**
     * The classifier's proposal must name a key whose client speaks the model's family: a
     * listing of one surface of the platform proposed on a sibling whose runtime does not know
     * the id is refused at the gate, never pinged into a failure and never written.
     */
    @Test
    void aProposalOnAKeyThatDoesNotServeTheModelIsRefused() {
        JsonModelsBackend seed = catalog(true, null, entry("a-strong", "alpha", "wire-strong", 1000));
        Scripted alpha = Scripted.listing("alpha", "wire-strong");
        alpha.servesOnly(wireId -> !wireId.startsWith("other."));
        Scripted alphaSibling = Scripted.listing("alpha-sibling", "other.mystery");
        String priced = "\"grade\": \"SMALL\", \"currency\": \"USD\", \"input_price_per_million\": 1.0, \"output_price_per_million\": 2.0";
        CatalogDiscovery.ClassifierCall classifier = (provider, spec, prompt) ->
                "{\"entries\": [" + entry("mystery@alpha", "alpha", "other.mystery", 500).replace("\"grade\": \"SMALL\"", priced) + "]}";
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha, "alpha-sibling", alphaSibling),
                (p, s) -> "mystery@alpha".equals(s.getId()) ? down(s) : ok(s, 55000),
                Duration.ZERO, false, null, classifier, CatalogDiscovery.permitting()).discover();
        assertFalse(ids(result.catalog()).contains("mystery@alpha"), "refused at the gate: " + ids(result.catalog()));
        assertTrue(result.classifiedDropped().isEmpty(), "never pinged, so never 'dropped by its own ping'");
        assertEquals(List.of("other.mystery"), result.unknown().get("alpha-sibling").stream().map(DiscoveredModel::wireModelId).toList(),
                "it stays on the person's list");
    }

    /** A retired listing stays on the person's list, flagged, and costs no classification call. */
    @Test
    void aRetiredUnknownIsNotSentToTheClassifier() {
        JsonModelsBackend seed = catalog(true, null, entry("a-strong", "alpha", "wire-strong", 1000));
        Scripted alpha = Scripted.listing("alpha", "wire-strong", "wire-legacy");
        alpha.markRetired("wire-legacy");
        CatalogDiscovery.ClassifierCall classifier = (provider, spec, prompt) -> {
            throw new AssertionError("a retired listing is not worth a classification call: " + prompt);
        };
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000),
                Duration.ZERO, false, null, classifier, CatalogDiscovery.permitting()).discover();
        assertNull(result.classifierFailure());
        assertTrue(result.report().contains("wire-legacy") && result.report().contains("provider marks it retired"), result.report());
    }

    /**
     * A ping refused because the model does not offer the zero-retention mode the account
     * runs at is the model's posture, never its reach: a new entry is left out under its own
     * heading, and an entry the seed carries is kept and flagged.
     */
    @Test
    void aZeroRetentionRefusalIsItsOwnVerdictNeverUnreachable() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-5", "alpha", "us.v.opus-5", 1000));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-5", "us.v.opus-6");
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> {
            if ("opus-6@alpha".equals(s.getId())) {
                ProbeOutcome refused = down(s);
                refused.setClassification(ProbeOutcome.Classification.OTHER);
                refused.setErrorClass("ValidationException");
                refused.setErrorMessage("The model returned the following errors: data retention mode 'none' is not available for this model");
                return refused;
            }
            return ok(s, 55000);
        }).discover();
        assertEquals(CatalogDiscovery.Verdict.NON_ZDR, verdict(result, "opus-6@alpha"));
        assertFalse(ids(result.catalog()).contains("opus-6@alpha"), "no entry on a surface that routes it no other way");
        assertEquals(1, result.nonZdr().size(), result.nonZdr().toString());
        assertTrue(result.nonZdr().get(0).startsWith("opus-6@alpha"), result.nonZdr().toString());
        assertTrue(result.report().contains("Served only under provider data share"), result.report());
        assertFalse(result.report().contains("dropped by their own ping") || result.report().contains("Classified but dropped"),
                "never worded as the account's reach: " + result.report());
    }

    /**
     * An entry this process cannot route is not pinged and is kept: its compliance envelope
     * refuses the model, or the model needs the LAX project and none is configured - either is
     * the deployment's own state and says nothing about whether the account can call the model.
     * A discovery run inside an application inherits that application's seal.
     */
    @Test
    void anEntryThisProcessCannotRouteIsKeptAndNotPinged() {
        JsonModelsBackend seed = catalog(true, null, entry("opus-5", "alpha", "us.v.opus-5", 1000),
                entry("fable-5", "alpha", "us.v.fable-5", 1000).replace("\"grade\": \"SMALL\"", "\"grade\": \"SMALL\", \"requires_lax\": true"));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-5", "us.v.fable-5");
        BiFunction<ClientProvider<?>, ModelSpec, ProbeOutcome> ping = (p, s) -> {
            if ("fable-5".equals(s.getId())) {
                throw new AssertionError("an entry this process cannot route is never pinged");
            }
            return ok(s, 55000);
        };
        ComplianceEnvelope refusing = spec -> !spec.requiresLax();
        CatalogDiscovery.Result refused = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), ping,
                Duration.ZERO, false, null, null, refusing).discover();
        assertEquals(CatalogDiscovery.Verdict.NOT_ROUTABLE_HERE, verdict(refused, "fable-5"), "the envelope refuses it");
        assertTrue(ids(refused.catalog()).contains("fable-5"), "kept: " + ids(refused.catalog()));
        assertTrue(refused.report().contains("cannot route the model"), refused.report());
        assertNull(Settings.get(ModelSettings.class).mantleLaxProject, "the test deployment configures no LAX project");
        CatalogDiscovery.Result unrouted = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), ping,
                Duration.ZERO, false, null, null, CatalogDiscovery.permitting()).discover();
        assertEquals(CatalogDiscovery.Verdict.NOT_ROUTABLE_HERE, verdict(unrouted, "fable-5"),
                "the envelope permits it, and the deployment has no LAX project to route it through");
        assertTrue(ids(unrouted.catalog()).contains("fable-5"), "kept: " + ids(unrouted.catalog()));
    }

    /**
     * A new entry's retention posture is the listing's word alone: an ancestor's or a
     * proposal's {@code requires_lax} is not inherited across surfaces, and a listing that
     * states zero retention is unavailable stamps it before the ping so the ping is routed
     * as the model needs.
     */
    @Test
    void aNewEntrysRetentionPostureComesFromTheListingNotTheAncestor() {
        JsonModelsBackend seed = catalog(true, null,
                entry("fable-5", "alpha", "us.v.fable-5", 1000).replace("\"grade\": \"SMALL\"", "\"grade\": \"SMALL\", \"requires_lax\": true"));
        Scripted alpha = Scripted.listing("alpha", "us.v.fable-5", "us.v.fable-6");
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000)).discover();
        JsonNode inherited = written(result, "fable-6@alpha");
        assertFalse(inherited.has("requires_lax"), "the ancestor's posture is not the new listing's: " + inherited);
        assertEquals(CatalogDiscovery.Verdict.REACHABLE, verdict(result, "fable-6@alpha"), "so it was pinged, and answered");
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    /**
     * An entry the deployment's file already carries is never re-evaluated: no ping, no
     * rewrite, its facts exactly as the person left them - and it leaves the run's good
     * graces only when the listing itself says the model is gone, which costs no call.
     */
    @Test
    void entriesAlreadyInTheDeploymentsFileAreNeverReEvaluated() {
        JsonModelsBackend seed = catalog(true, null, entry("a-kept", "alpha", "wire-kept", 1000));
        JsonModelsBackend previous = catalog(false, null,
                entry("a-kept", "alpha", "wire-kept", 4242),
                entry("a-gone", "alpha", "wire-gone", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-kept"));
        Set<String> pinged = new HashSet<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, providers, (p, s) -> {
            pinged.add(s.getId());
            return ok(s, 55000);
        }).discover();
        assertEquals(Set.of(), pinged, "no call is spent on what the file already states");
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "a-kept"));
        assertEquals(CatalogDiscovery.Verdict.NOT_LISTED, verdict(result, "a-gone"),
                "unavailability comes from the listing alone: gone from it means flagged, still without a call");
        for (JsonNode model : result.catalog().get("models")) {
            if (model.get("id").asText().equals("a-kept")) {
                assertEquals(4242, model.get("tpm").intValue(), "the file's numbers stay exactly as the person left them");
            }
        }
        assertTrue(ids(result.catalog()).contains("a-gone"), "never shrunk, only flagged: " + ids(result.catalog()));
        assertEquals("UNLISTED", written(result, "a-gone").get("status").asText(),
                "flagged means closed: the account's word, so no picker or benchmark calls what the account no longer serves");
        assertFalse(written(result, "a-kept").has("status"), "a listed entry stays open");
        assertTrue(result.report().contains("closed as UNLISTED"), result.report());
    }

    /**
     * The account's word is the one status the discovery also takes back: an UNLISTED entry
     * whose model the listing names again reopens and is pinged like a new entry, the spec the
     * ping pins already open, while a DISABLED one, the deployment's own word, stays closed
     * whatever the listing says.
     */
    @Test
    void anUnlistedEntryTheAccountListsAgainReopensAndIsPinged() {
        JsonModelsBackend seed = catalog(true, null, entry("a-back", "alpha", "wire-back", 1000));
        JsonModelsBackend previous = catalog(false, null,
                entry("a-back", "alpha", "wire-back", 4242).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"UNLISTED\""),
                entry("a-off", "alpha", "wire-off", 4242).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"DISABLED\""));
        Map<String, ModelStatus> pinged = new HashMap<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, Map.of("alpha", Scripted.listing("alpha", "wire-back", "wire-off")), (p, s) -> {
            pinged.put(s.getId(), s.getStatus());
            return ok(s, 55000);
        }).discover();
        assertEquals(Set.of("a-back"), pinged.keySet(), "the reopened entry earns a ping; the disabled one never does");
        assertEquals(ModelStatus.OPEN, pinged.get("a-back"), "the ping pins the entry as reopened: the pin gate refuses a closed spec");
        assertEquals(CatalogDiscovery.Verdict.REACHABLE, verdict(result, "a-back"));
        assertFalse(written(result, "a-back").has("status"), "reopened: " + written(result, "a-back"));
        assertEquals("DISABLED", written(result, "a-off").get("status").asText(), "the deployment's own word stands");
        assertTrue(result.report().contains("lists it again; reopened and pinged"), result.report());
    }

    /**
     * Kept is kept, nothing more. A provider whose endpoint is not served where this process
     * points fails its listing on an unknown host, which no retry changes: its seed entries
     * are left out, its entries from the deployment's file stay for the runs they served and
     * are closed as UNREACHABLE, so no picker or benchmark calls them, an entry already closed
     * by another word keeps that word, and no ping is spent on any of it.
     */
    @Test
    void aProviderNotServedHereClosesItsKeptEntriesAsUnreachableWithoutAPing() {
        JsonModelsBackend seed = catalog(true, null,
                entry("a-seed", "alpha", "wire-seed", 1000),
                entry("a-far", "alpha", "wire-far", 1000));
        JsonModelsBackend previous = catalog(false, null,
                entry("a-far", "alpha", "wire-far", 4242),
                entry("a-off", "alpha", "wire-off", 4242).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"DISABLED\""));
        Set<String> pinged = new HashSet<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, Map.of("alpha", Scripted.unreachable("alpha")), (p, s) -> {
            pinged.add(s.getId());
            return ok(s, 55000);
        }).discover();
        assertEquals(Set.of(), pinged, "an unknown host says everything; no call is spent on an endpoint that is not there");
        assertEquals(CatalogDiscovery.Verdict.UNREACHABLE, verdict(result, "a-seed"));
        assertFalse(ids(result.catalog()).contains("a-seed"), "a seed entry of an unreachable provider is left out: " + ids(result.catalog()));
        assertEquals(CatalogDiscovery.Verdict.UNREACHABLE, verdict(result, "a-far"));
        assertTrue(ids(result.catalog()).contains("a-far"), "never shrunk: " + ids(result.catalog()));
        assertEquals("UNREACHABLE", written(result, "a-far").get("status").asText(),
                "kept for the runs it served and closed, so nothing picks a model this deployment cannot reach");
        assertEquals(4242, written(result, "a-far").get("tpm").intValue(), "the file's numbers stay as the person left them");
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "a-off"));
        assertEquals("DISABLED", written(result, "a-off").get("status").asText(), "the deployment's own word stands");
        assertTrue(result.report().contains("UNREACHABLE - UnknownHostException"), result.report());
        assertTrue(result.report().contains("status set to UNREACHABLE"), result.report());
    }

    /**
     * This deployment's reach is a status the discovery also takes back: an UNREACHABLE entry
     * whose provider lists again reopens and is judged like a new one, pinged with its spec
     * already open when the listing names it and closed as UNLISTED when it does not.
     */
    @Test
    void anUnreachableEntryWhoseProviderAnswersAgainReopens() {
        JsonModelsBackend seed = catalog(true, null, entry("a-back", "alpha", "wire-back", 1000));
        JsonModelsBackend previous = catalog(false, null,
                entry("a-back", "alpha", "wire-back", 4242).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"UNREACHABLE\""),
                entry("a-gone", "alpha", "wire-gone", 4242).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"UNREACHABLE\""));
        Map<String, ModelStatus> pinged = new HashMap<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, Map.of("alpha", Scripted.listing("alpha", "wire-back")), (p, s) -> {
            pinged.put(s.getId(), s.getStatus());
            return ok(s, 55000);
        }).discover();
        assertEquals(Set.of("a-back"), pinged.keySet(), "the reopened entry the listing names earns a ping");
        assertEquals(ModelStatus.OPEN, pinged.get("a-back"), "the ping pins the entry as reopened: the pin gate refuses a closed spec");
        assertEquals(CatalogDiscovery.Verdict.REACHABLE, verdict(result, "a-back"));
        assertFalse(written(result, "a-back").has("status"), "reopened: " + written(result, "a-back"));
        assertEquals(CatalogDiscovery.Verdict.NOT_LISTED, verdict(result, "a-gone"));
        assertEquals("UNLISTED", written(result, "a-gone").get("status").asText(),
                "the provider answers but does not list it: the account's word replaces the reach's");
        assertTrue(result.report().contains("its provider answers again; reopened and pinged"), result.report());
    }

    /**
     * The same closing when the provider lists but the entry's own ping fails for a reason no
     * retry changes: a seed entry is left out, an entry from the deployment's file is kept and
     * closed as UNREACHABLE.
     */
    @Test
    void aKeptEntryWhosePingFailsForGoodIsClosedAsUnreachable() {
        JsonModelsBackend seed = catalog(true, null, entry("a-dead", "alpha", "wire-dead", 1000));
        JsonModelsBackend previous = catalog(false, null,
                entry("a-dead", "alpha", "wire-dead", 4242).replace("\"rpm\": 100", "\"rpm\": 100, \"status\": \"UNLISTED\""));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, Map.of("alpha", Scripted.listing("alpha", "wire-dead")),
                (p, s) -> down(s)).discover();
        assertEquals(CatalogDiscovery.Verdict.UNREACHABLE, verdict(result, "a-dead"), "reopened by the listing, then refused by the ping");
        assertTrue(ids(result.catalog()).contains("a-dead"), "never shrunk: " + ids(result.catalog()));
        assertEquals("UNREACHABLE", written(result, "a-dead").get("status").asText(), "kept, and closed");
        assertTrue(result.report().contains("kept from your previous file, closed as UNREACHABLE"), result.report());
    }

    /**
     * A scoped run re-discovers only the named providers: the out-of-scope one is neither
     * listed nor pinged, its previous entries are written back exactly as they were, and the
     * report says so. This is the incremental discovery a page runs as credentials arrive,
     * paying only for what changed.
     */
    @Test
    void aScopedRunKeepsOutOfScopeEntriesUntouchedAndNeverPingsThem() {
        JsonModelsBackend seed = catalog(true, null,
                entry("a-model", "alpha", "wire-a", 1000),
                entry("b-model", "beta", "wire-b", 2000));
        JsonModelsBackend previous = catalog(false, null,
                entry("a-model", "alpha", "wire-a", 1000),
                entry("b-model", "beta", "wire-b", 7777));
        Map<String, ClientProvider<?>> providers = Map.of(
                "alpha", Scripted.listing("alpha", "wire-a"),
                "beta", Scripted.listing("beta", "wire-b"));
        Set<String> pinged = new HashSet<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, providers, (p, s) -> {
            pinged.add(s.getProviderKey());
            return ok(s, 55000);
        }, Duration.ZERO, false, Set.of("alpha"), null, CatalogDiscovery.permitting()).discover();
        assertEquals(Set.of(), pinged,
                "nothing is pinged: the out-of-scope provider is skipped, and an in-scope entry the deployment's file"
                        + " already carries is never re-evaluated");
        assertTrue(ids(result.catalog()).containsAll(Set.of("a-model", "b-model")),
                "the out-of-scope provider's previous entry stays in the written catalog: " + ids(result.catalog()));
        for (JsonNode model : result.catalog().get("models")) {
            if (model.get("id").asText().equals("b-model")) {
                assertEquals(7777, model.get("tpm").asInt(),
                        "an out-of-scope entry rides through exactly as the previous file held it");
            }
        }
        assertTrue(result.providers().stream().anyMatch(p -> p.key().equals("beta")
                        && p.status() == CatalogDiscovery.ProviderStatus.OUT_OF_SCOPE),
                "the report names the provider as out of scope");
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "b-model"));
        assertTrue(result.report().contains("out of scope"), result.report());
    }

    /**
     * The classifier is the discovery's judgment seat for listings nothing can inherit a shape
     * from: the strongest callable model gets one call per unclassified listing, each call
     * naming its own model and carrying its platform's other listings as family context, its
     * proposals pass the deterministic gate (only a listed model of its own provider, never an
     * invented one), each accepted entry is pinged live and written with a note naming the
     * classifier, and the report says who classified what. A proposal for a model nobody
     * listed is refused and stays on the person's list. A tier word in the listed name decides
     * the rung over the classifier's judgment, and every classified entry is marked unverified
     * until a person confirms it.
     */
    @Test
    void unknownListingsAreClassifiedByTheStrongestModelGatedAndPinged() {
        JsonModelsBackend seed = catalog(true, null, entry("a-strong", "alpha", "wire-strong", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha",
                Scripted.listing("alpha", "wire-strong", "wire-mystery", "wire-figment", "wire-doomed", "wire-thing-nano"));
        // the calls run on virtual threads where a thrown assertion would just read as a failed
        // call, so the lambda records what it saw and the test asserts on the records
        Set<String> seats = ConcurrentHashMap.newKeySet();
        Map<String, String> prompts = new ConcurrentHashMap<>();
        String priced = "\"grade\": \"SMALL\", \"currency\": \"USD\","
                + " \"input_price_per_million\": 1.0, \"output_price_per_million\": 2.0";
        CatalogDiscovery.ClassifierCall classifier = (provider, spec, prompt) -> {
            seats.add(spec.getId());
            String subject = subjectOf(prompt);
            prompts.put(subject, prompt);
            return switch (subject) {
                case "wire-mystery" -> "{\"entries\": [" + entry("mystery@alpha", "alpha", "wire-mystery", 500).replace("\"grade\": \"SMALL\"", priced) + "]}";
                // a disobedient judgment: it proposes a wire id nobody listed, and the gate refuses it
                case "wire-figment" -> "{\"entries\": [" + entry("figment@alpha", "alpha", "wire-invented", 500).replace("\"grade\": \"SMALL\"", priced) + "]}";
                case "wire-doomed" -> "{\"entries\": [" + entry("doomed@alpha", "alpha", "wire-doomed", 500).replace("\"grade\": \"SMALL\"", priced) + "]}";
                // a judgment the name overrules: the vendor's own tier word says nano, whatever the classifier thought
                case "wire-thing-nano" -> "{\"entries\": [" + entry("tiny@alpha", "alpha", "wire-thing-nano", 500).replace("\"grade\": \"SMALL\"", priced.replace("SMALL", "LARGE")) + "]}";
                default -> throw new IllegalStateException("a call for a listing that is not unclassified: " + subject);
            };
        };
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers,
                (p, s) -> "doomed@alpha".equals(s.getId()) ? down(s) : ok(s, 55000),
                Duration.ZERO, false, null, classifier, CatalogDiscovery.permitting()).discover();
        assertEquals(Set.of("a-strong"), seats, "every call sits on the strongest callable entry");
        assertEquals(Set.of("wire-mystery", "wire-figment", "wire-doomed", "wire-thing-nano"), prompts.keySet(), "one call per unclassified listing");
        assertTrue(prompts.get("wire-mystery").contains("never by its price"), "the call carries the grading criterion");
        JsonNode tiny = written(result, "tiny@alpha");
        assertEquals("MICRO", tiny.get("grade").asText(), "the tier word in the name decides the rung over the classifier's LARGE");
        assertTrue(tiny.get("note").asText().contains("grade MICRO from 'nano' in its name"), tiny.get("note").asText());
        assertTrue(tiny.get("unverified").asBoolean(), "a classified entry is the discovery's inference until a person confirms it");
        assertTrue(written(result, "mystery@alpha").get("unverified").asBoolean());
        assertTrue(prompts.get("wire-mystery").contains("wire-doomed"),
                "each call carries its platform's other unclassified listings as family context");
        assertEquals("a-strong", result.classifiedBy());
        assertNull(result.classifierFailure(), "a refused proposal is the gate's verdict, not a failed call");
        assertEquals(List.of("mystery@alpha", "tiny@alpha"), result.classified());
        assertTrue(ids(result.catalog()).contains("mystery@alpha"), "the accepted proposal is written: " + ids(result.catalog()));
        assertEquals(CatalogDiscovery.Verdict.REACHABLE, verdict(result, "mystery@alpha"), "a classified entry is pinged like any other");
        assertFalse(ids(result.catalog()).contains("figment@alpha"),
                "a proposal whose wire id nobody listed is refused - a classifier suggests, it does not invent");
        assertEquals(List.of("doomed@alpha"), result.classifiedDropped(),
                "a classified entry whose own ping refused it is dropped and reported as dropped, never claimed");
        assertFalse(ids(result.catalog()).contains("doomed@alpha"), ids(result.catalog()).toString());
        String written = result.catalog().get("models").toString();
        assertTrue(written.contains("classified by a-strong"), "the entry's note names the classifier: " + written);
        assertTrue(result.report().contains("Classified by a-strong"), result.report());
        assertTrue(result.report().contains("Classified but dropped"), result.report());
    }

    /**
     * The deployment's deliberate choice outranks the classifier's judgment, per platform: a
     * classified new version of a family DISABLED on its own platform arrives disabled and
     * unpinged, while the same family on another platform is untouched by that choice.
     */
    @Test
    void aClassifiedVersionOfADisabledFamilyArrivesDisabledOnItsPlatformOnly() {
        JsonModelsBackend seed = catalog(true, null,
                entry("a-strong", "alpha", "wire-strong", 1000),
                entry("sol-1@alpha", "alpha", "wire-sol-1", 500).replace("\"identity\": \"sol-1@alpha\"",
                        "\"identity\": \"sol-1\", \"status\": \"DISABLED\""));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-strong", "wire-sol-1", "wire-sol-x"));
        Set<String> pinged = new HashSet<>();
        CatalogDiscovery.ClassifierCall classifier = (provider, spec, prompt) ->
                "{\"entries\": [" + entry("sol-2@alpha", "alpha", "wire-sol-x", 500)
                        .replace("\"identity\": \"sol-2@alpha\"", "\"identity\": \"sol-2\"")
                        .replace("\"grade\": \"SMALL\"", "\"grade\": \"SMALL\", \"currency\": \"USD\","
                                + " \"input_price_per_million\": 1.0, \"output_price_per_million\": 2.0") + "]}";
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> {
            pinged.add(s.getId());
            return ok(s, 55000);
        }, Duration.ZERO, false, null, classifier, CatalogDiscovery.permitting()).discover();
        assertEquals(List.of("sol-2@alpha"), result.classified());
        assertEquals(CatalogDiscovery.Verdict.SKIPPED, verdict(result, "sol-2@alpha"), "disabled, so never pinged");
        assertFalse(pinged.contains("sol-2@alpha"), "a disabled arrival is not worth a ping");
        for (JsonNode model : result.catalog().get("models")) {
            if (model.get("id").asText().equals("sol-2@alpha")) {
                assertEquals("DISABLED", model.get("status").asText(),
                        "a new version of a family the deployment disabled arrives disabled too");
            }
        }
        assertTrue(ids(result.catalog()).contains("sol-2@alpha"), ids(result.catalog()).toString());
    }

    /** A call that fails costs exactly its own listing: the others land, and the report counts the loss. */
    @Test
    void aFailedCallCostsOnlyItsOwnListing() {
        JsonModelsBackend seed = catalog(true, null, entry("a-strong", "alpha", "wire-strong", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha",
                Scripted.listing("alpha", "wire-strong", "wire-mystery", "wire-cursed"));
        CatalogDiscovery.ClassifierCall classifier = (provider, spec, prompt) -> {
            if (subjectOf(prompt).equals("wire-cursed")) {
                throw new IllegalStateException("this one call died");
            }
            return "{\"entries\": [" + entry("mystery@alpha", "alpha", "wire-mystery", 500)
                    .replace("\"grade\": \"SMALL\"", "\"grade\": \"SMALL\", \"currency\": \"USD\","
                            + " \"input_price_per_million\": 1.0, \"output_price_per_million\": 2.0") + "]}";
        };
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000),
                Duration.ZERO, false, null, classifier, CatalogDiscovery.permitting()).discover();
        assertEquals(List.of("mystery@alpha"), result.classified(), "the surviving call's model landed");
        assertEquals(1, result.unknown().get("alpha").size(), "the failed call's listing stays on the person's list");
        assertEquals("wire-cursed", result.unknown().get("alpha").get(0).wireModelId());
        assertTrue(result.classifierFailure().contains("1 of 2 model classifications failed"), result.classifierFailure());
        assertTrue(result.classifierFailure().contains("this one call died"), result.classifierFailure());
        assertTrue(ids(result.catalog()).contains("mystery@alpha"), ids(result.catalog()).toString());
    }

    /** A classification where every call fails leaves the unknowns exactly where they were, with the reason in the report. */
    @Test
    void aFailedClassificationLeavesTheUnknownsOnThePersonsList() {
        JsonModelsBackend seed = catalog(true, null, entry("a-strong", "alpha", "wire-strong", 1000));
        Map<String, ClientProvider<?>> providers = Map.of("alpha", Scripted.listing("alpha", "wire-strong", "wire-mystery"));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, providers, (p, s) -> ok(s, 55000),
                Duration.ZERO, false, null, (provider, spec, prompt) -> {
                    throw new IllegalStateException("the classifier's minute was bad");
                }, CatalogDiscovery.permitting()).discover();
        assertTrue(result.classified().isEmpty());
        assertEquals(1, result.unknown().get("alpha").size(), "the unknown stays reported");
        assertTrue(result.classifierFailure().contains("the classifier's minute was bad"), result.classifierFailure());
        assertTrue(result.report().contains("Unknowns left unclassified"), result.report());
    }

    private static String priced(String id, String provider, String wire, double input, double output, String cacheRead) {
        return "{ \"id\": \"" + id + "\", \"identity\": \"" + id + "\", \"grade\": \"SMALL\", \"provider_key\": \"" + provider + "\","
                + " \"wire_model_id\": \"" + wire + "\", \"max_context_tokens\": 1000, \"max_output_tokens\": 100,"
                + " \"supports_vision\": false, \"thinking_mode\": \"NONE\", \"tpm\": 1000, \"rpm\": 100,"
                + " \"input_price_per_million\": " + input + ", \"output_price_per_million\": " + output
                + (cacheRead != null ? ", \"cache_read_price_per_million\": " + cacheRead : "") + " }";
    }

    /**
     * The listed price is the one fact of the deployment's file a run rewrites: an entry the
     * provider's price list prices takes the listed rates without a ping, the cache rates the
     * list states and nothing a person typed beside them, the currency when it is not the
     * provider's default, and the report shows the rates before and after.
     */
    @Test
    void aListedPriceReplacesAnEntrysPricesWithoutAPing() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-kept", "alpha", "wire-kept", 1.0, 5.0, "0.1"));
        JsonModelsBackend previous = pricedCatalog(false, priced("a-kept", "alpha", "wire-kept", 1.0, 5.0, "0.1"));
        Scripted alpha = Scripted.listing("alpha", "wire-kept");
        alpha.price("wire-kept", 0.11, 0.55);
        Set<String> pinged = new HashSet<>();
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, previous, Map.of("alpha", alpha), (p, s) -> {
            pinged.add(s.getId());
            return ok(s, 55000);
        }).discover();
        assertEquals(Set.of(), pinged, "a price costs no call");
        JsonNode kept = written(result, "a-kept");
        assertEquals(0.11, kept.get("input_price_per_million").doubleValue());
        assertEquals(0.55, kept.get("output_price_per_million").doubleValue());
        assertFalse(kept.has("cache_read_price_per_million"), "a cache rate the list does not state is not kept from the typed entry");
        assertFalse(kept.has("currency"), "the list bills in the provider's default currency, so the entry states none of its own");
        assertEquals(1000, kept.get("tpm").intValue(), "everything else is as the person left it");
        assertTrue(result.report().contains("price 1/5 -> 0.11/0.55 (list)"), result.report());
        assertTrue(result.report().contains("its prices from the provider's price list"), result.report());
    }

    /** A new entry inherits its ancestor's shape and takes the list's prices over the ancestor's, and its note says which facts are left to verify. */
    @Test
    void anAddedEntryTakesTheListedPricesOverItsAncestors() {
        JsonModelsBackend seed = pricedCatalog(true, priced("opus-4.8", "alpha", "us.v.opus-4-8", 5.0, 25.0, null));
        Scripted alpha = Scripted.listing("alpha", "us.v.opus-4-8", "us.v.opus-5");
        alpha.price("us.v.opus-5", 4.0, 20.0);
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000)).discover();
        JsonNode added = written(result, "opus-5@alpha");
        assertEquals(4.0, added.get("input_price_per_million").doubleValue());
        assertEquals(20.0, added.get("output_price_per_million").doubleValue());
        assertTrue(added.get("note").asText().endsWith("verify grade, context and output ceilings; prices from the provider's price list"), added.get("note").asText());
        assertTrue(added.get("unverified").asBoolean(), "the inherited grade and ceilings are still the run's inference");
        assertTrue(result.report().contains("price 5/25 -> 4/20 (list)"), result.report());
        JsonNode ancestor = written(result, "opus-4.8");
        assertEquals(5.0, ancestor.get("input_price_per_million").doubleValue(), "an entry the list does not price keeps its own");
        assertFalse(result.report().contains("opus-4.8") && result.report().contains("price 5/25 (list"), result.report());
    }

    /** An LLM the list prices on input alone keeps the prices it had: half a listed price and half a typed one is nobody's number. */
    @Test
    void anLlmPricedOnInputAloneKeepsItsPrices() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-listed", "alpha", "wire-a", 1.0, 5.0, null));
        Scripted alpha = Scripted.listing("alpha", "wire-a");
        alpha.price("wire-a", 0.11, null);
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000)).discover();
        JsonNode entry = written(result, "a-listed");
        assertEquals(1.0, entry.get("input_price_per_million").doubleValue());
        assertEquals(5.0, entry.get("output_price_per_million").doubleValue());
        assertFalse(result.report().contains("(list"), result.report());
    }

    /** The rows of a price list, in an order that is not the alphabet's, so a test can tell the order was kept. */
    private static Map<String, Double> rows(Object... nameThenRate) {
        Map<String, Double> rows = new LinkedHashMap<>();
        for (int i = 0; i < nameThenRate.length; i += 2) {
            rows.put((String) nameThenRate[i], (Double) nameThenRate[i + 1]);
        }
        return rows;
    }

    /**
     * Every rate the provider's price list states for a model is written onto its entry as
     * {@code listed_rates}, each under the name the provider gives the row and in the order
     * the listing gave them, a row whose name says nothing the discovery knows included; the
     * four prices are what the list's standard rows made them; and the entry read back from
     * the written catalog hands the rows out through {@link ModelSpec#getListedRates()}.
     */
    @Test
    void everyRateTheListStatesIsWrittenOntoTheEntryUnderTheRowsOwnName() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-kept", "alpha", "wire-kept", 1.0, 5.0, null));
        Scripted alpha = Scripted.listing("alpha", "wire-kept");
        alpha.price("wire-kept", 2.0, 10.0);
        alpha.listRates("wire-kept", "USD", rows("R1-Output-Units", 10.0, "R1-Input-Units", 2.0, "R1-Input_Batch-Units", 1.0,
                "R1-Input_LongContext-Units", 4.0, "R1-Input_NeverSeenWord-Units", 7.5));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000)).discover();
        JsonNode entry = written(result, "a-kept");
        JsonNode listed = entry.get("listed_rates");
        assertEquals(List.of("R1-Output-Units", "R1-Input-Units", "R1-Input_Batch-Units", "R1-Input_LongContext-Units", "R1-Input_NeverSeenWord-Units"),
                listed.properties().stream().map(Map.Entry::getKey).toList(), "every row, under its own name, in the listing's order");
        assertEquals(1.0, listed.get("R1-Input_Batch-Units").doubleValue());
        assertEquals(7.5, listed.get("R1-Input_NeverSeenWord-Units").doubleValue(), "a row is kept whatever its name says");
        assertEquals(2.0, entry.get("input_price_per_million").doubleValue(), "the four prices are the standard rows' and no other row's");
        assertEquals(10.0, entry.get("output_price_per_million").doubleValue());
        JsonModelsBackend reread = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("previous", result.catalog().toString(), false)));
        Map<String, Double> rates = reread.specOf(reread.rawEntries().get("a-kept")).getListedRates();
        assertEquals(new ArrayList<>(rows("R1-Output-Units", 10.0, "R1-Input-Units", 2.0, "R1-Input_Batch-Units", 1.0,
                "R1-Input_LongContext-Units", 4.0, "R1-Input_NeverSeenWord-Units", 7.5).entrySet()), new ArrayList<>(rates.entrySet()),
                "the spec hands out the rows as the entry holds them");
    }

    /** A run over a changed list replaces an entry's listed rates whole: no row of the earlier reading is left beside the new ones. */
    @Test
    void aLaterReadingReplacesAnEntrysListedRatesWhole() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-kept", "alpha", "wire-kept", 1.0, 5.0, null));
        Scripted first = Scripted.listing("alpha", "wire-kept");
        first.listRates("wire-kept", "USD", rows("R1-Input-Units", 2.0, "R1-Input_Flex-Units", 1.0));
        CatalogDiscovery.Result earlier = new CatalogDiscovery(seed, null, Map.of("alpha", first), (p, s) -> ok(s, 55000)).discover();
        JsonModelsBackend previous = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("previous", earlier.catalog().toString(), false)));
        Scripted second = Scripted.listing("alpha", "wire-kept");
        second.listRates("wire-kept", "USD", rows("R1-Input-Units", 2.5, "R1-Input_Priority-Units", 5.0));
        CatalogDiscovery.Result later = new CatalogDiscovery(seed, previous, Map.of("alpha", second), (p, s) -> ok(s, 55000)).discover();
        JsonNode listed = written(later, "a-kept").get("listed_rates");
        assertEquals(List.of("R1-Input-Units", "R1-Input_Priority-Units"), listed.properties().stream().map(Map.Entry::getKey).toList(),
                "the flex row the list no longer states is gone");
        assertEquals(2.5, listed.get("R1-Input-Units").doubleValue(), "a rate that moved is the new one");
    }

    /**
     * The listed rates do not wait on the four prices: an LLM the list prices on input alone
     * keeps the prices a person typed and still gets its rows, and so does a model the list
     * has rows for and no standard price.
     */
    @Test
    void anEntryTheListDoesNotPriceStillGetsItsListedRates() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-half", "alpha", "wire-half", 1.0, 5.0, null), priced("a-rows", "alpha", "wire-rows", 3.0, 15.0, null));
        Scripted alpha = Scripted.listing("alpha", "wire-half", "wire-rows");
        alpha.price("wire-half", 0.11, null);
        alpha.listRates("wire-half", "USD", rows("R1-Input-Units", 0.11));
        alpha.listRates("wire-rows", "USD", rows("Zone A Input", 3.0, "Zone B Input", 3.3));
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000)).discover();
        JsonNode half = written(result, "a-half");
        assertEquals(1.0, half.get("input_price_per_million").doubleValue(), "the typed price stays");
        assertEquals(0.11, half.get("listed_rates").get("R1-Input-Units").doubleValue());
        JsonNode rowsOnly = written(result, "a-rows");
        assertEquals(3.0, rowsOnly.get("input_price_per_million").doubleValue(), "an entry with rows and no listed price keeps its typed prices");
        assertEquals(15.0, rowsOnly.get("output_price_per_million").doubleValue());
        assertEquals(3.3, rowsOnly.get("listed_rates").get("Zone B Input").doubleValue());
    }

    /**
     * An entry of a provider that lists no rate is written without the field and its spec has
     * no listed rates, null and never an empty map; an entry that holds rows from an earlier
     * run keeps them through a run whose listing states none, as it keeps its prices.
     */
    @Test
    void anEntryWithNoListedRowHasNoListedRates_andARunThatReadsNoneKeepsTheEarlierOnes() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-plain", "alpha", "wire-plain", 1.0, 5.0, null), priced("a-kept", "alpha", "wire-kept", 1.0, 5.0, null));
        Scripted first = Scripted.listing("alpha", "wire-plain", "wire-kept");
        first.listRates("wire-kept", "USD", rows("R1-Input-Units", 2.0));
        CatalogDiscovery.Result earlier = new CatalogDiscovery(seed, null, Map.of("alpha", first), (p, s) -> ok(s, 55000)).discover();
        assertFalse(written(earlier, "a-plain").has("listed_rates"), "no row, no field");
        JsonModelsBackend previous = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("previous", earlier.catalog().toString(), false)));
        assertNull(previous.specOf(previous.rawEntries().get("a-plain")).getListedRates(), "no listed rates is null, never an empty map");
        Scripted unreadable = Scripted.listing("alpha", "wire-plain", "wire-kept");
        CatalogDiscovery.Result later = new CatalogDiscovery(seed, previous, Map.of("alpha", unreadable), (p, s) -> ok(s, 55000)).discover();
        assertEquals(2.0, written(later, "a-kept").get("listed_rates").get("R1-Input-Units").doubleValue(), "a run that read no row leaves the earlier reading");
    }

    /**
     * The rates of an entry are in its one currency. Rows the list states in another are not
     * written, and the rows of an earlier run are removed with them, so the entry never holds
     * rates its currency does not describe.
     */
    @Test
    void rowsInAnotherCurrencyThanTheEntrysAreNotWritten_andEarlierRowsGoWithThem() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-kept", "alpha", "wire-kept", 1.0, 5.0, null));
        Scripted first = Scripted.listing("alpha", "wire-kept");
        first.listRates("wire-kept", "USD", rows("R1-Input-Units", 2.0));
        CatalogDiscovery.Result earlier = new CatalogDiscovery(seed, null, Map.of("alpha", first), (p, s) -> ok(s, 55000)).discover();
        JsonModelsBackend previous = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("previous", earlier.catalog().toString(), false)));
        Scripted second = Scripted.listing("alpha", "wire-kept");
        second.listRates("wire-kept", "CNY", rows("R1-Input-Units", 14.0));
        CatalogDiscovery.Result later = new CatalogDiscovery(seed, previous, Map.of("alpha", second), (p, s) -> ok(s, 55000)).discover();
        JsonNode entry = written(later, "a-kept");
        assertFalse(entry.has("listed_rates"), "a rate in yuan is not written onto an entry priced in dollars, and the dollar rows of the earlier list are not left to stand for it");
        assertEquals(1.0, entry.get("input_price_per_million").doubleValue());
    }

    /**
     * Where the list states a standard price per deployment type and the listing cannot say
     * which type a deployment is, the run writes no price and reports the entry whose typed
     * price is none of them, with the typed price and each price it could have been. An
     * entry typed at one of them is not reported, and neither entry is changed.
     */
    @Test
    void anEntryPricedAtNoneOfTheListedOptionsIsReportedAndLeftAsItIs() {
        JsonModelsBackend seed = pricedCatalog(true, priced("a-off", "alpha", "wire-off", 0.28, 0.42, null), priced("a-on", "alpha", "wire-on", 2.0, 10.0, null));
        Scripted alpha = Scripted.listing("alpha", "wire-off", "wire-on");
        alpha.priceOption("wire-off", "Global", 1.75, 3.48);
        alpha.priceOption("wire-off", "Data Zone", 1.925, 3.828);
        alpha.priceOption("wire-on", "Global", 2.0, 10.0);
        alpha.priceOption("wire-on", "Data Zone", 2.2, 11.0);
        CatalogDiscovery.Result result = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000)).discover();
        assertEquals(1, result.pricesThatAreNoOption().size(), result.pricesThatAreNoOption().toString());
        String line = result.pricesThatAreNoOption().get(0);
        assertTrue(line.startsWith("a-off"), line);
        assertTrue(line.endsWith("price 0.28/0.42; the list states Global 1.75/3.48, Data Zone 1.925/3.828"), line);
        assertTrue(result.report().contains("PRICES THAT ARE NONE OF THE LISTED ONES"), result.report());
        assertTrue(result.report().contains(line), result.report());
        assertEquals(0.28, written(result, "a-off").get("input_price_per_million").doubleValue(), "the entry keeps the price a person typed");
        assertEquals(0.42, written(result, "a-off").get("output_price_per_million").doubleValue());
        assertEquals(2.0, written(result, "a-on").get("input_price_per_million").doubleValue());
        assertFalse(result.report().contains("a-on  " + " price"), "an entry typed at one of the options is not reported");
    }

    /** An entry that thinks the way the test names, priced so the catalog loads. */
    private static String thinking(String id, String wire, String mode) {
        return priced(id, "alpha", wire, 1.0, 5.0, null).replace("\"thinking_mode\": \"NONE\"", "\"thinking_mode\": \"" + mode + "\"");
    }

    private static ProbeOutcome refused(ModelSpec spec, String message) {
        ProbeOutcome outcome = new ProbeOutcome();
        outcome.setSpecId(spec.getId());
        outcome.setStatus(ProbeOutcome.Status.FAILED);
        outcome.setClassification(ProbeOutcome.Classification.OTHER);
        outcome.setErrorMessage(message);
        return outcome;
    }

    /** A discovery whose thinking ping is scripted: the modes the model accepts, every other refused in the provider's words, each call recorded. */
    private static CatalogDiscovery thinkingDiscovery(JsonModelsBackend seed, Scripted alpha, Set<ThinkingMode> accepted, List<ThinkingMode> asked) {
        CatalogDiscovery discovery = new CatalogDiscovery(seed, null, Map.of("alpha", alpha), (p, s) -> ok(s, 55000));
        discovery.thinkingPing = (p, s) -> {
            asked.add(s.getThinkingMode());
            return accepted.contains(s.getThinkingMode()) ? ok(s, 55000) : refused(s, "400: \"thinking.type\" is not supported for this model");
        };
        return discovery;
    }

    /**
     * A new entry's thinking mode is the one the model accepts when asked to think, never the
     * one it inherited: a mode the model refuses is replaced by the one it accepts and the
     * report shows both; a mode it accepts costs that one call; a model that refuses both
     * thinks in no way this runtime can ask for and is written {@code NONE}.
     */
    @Test
    void aPingedEntrysThinkingModeIsTheOneTheModelAcceptsWhenAskedToThink() {
        List<ThinkingMode> asked = new ArrayList<>();
        JsonModelsBackend seed = pricedCatalog(true, thinking("a-new", "wire-new", "EXTENDED"));
        CatalogDiscovery.Result swapped = thinkingDiscovery(seed, Scripted.listing("alpha", "wire-new"), Set.of(ThinkingMode.ADAPTIVE), asked).discover();
        assertEquals("ADAPTIVE", written(swapped, "a-new").get("thinking_mode").asText(), "the model refused the inherited mode and accepted the other");
        assertEquals(List.of(ThinkingMode.EXTENDED, ThinkingMode.ADAPTIVE), asked, "the written mode first, then the other");
        assertEquals(1, swapped.thinkingModes().size());
        assertTrue(swapped.thinkingModes().get(0).endsWith("EXTENDED -> ADAPTIVE (the model refused EXTENDED and accepted ADAPTIVE)"), swapped.thinkingModes().get(0));
        assertTrue(swapped.report().contains("THINKING MODES"), swapped.report());
        asked.clear();
        CatalogDiscovery.Result confirmed = thinkingDiscovery(seed, Scripted.listing("alpha", "wire-new"), Set.of(ThinkingMode.EXTENDED), asked).discover();
        assertEquals("EXTENDED", written(confirmed, "a-new").get("thinking_mode").asText());
        assertEquals(List.of(ThinkingMode.EXTENDED), asked, "a mode the model accepts costs one call and no more");
        assertTrue(confirmed.thinkingModes().isEmpty(), "a confirmed mode is not news");
        assertFalse(confirmed.report().contains("THINKING MODES"), confirmed.report());
        asked.clear();
        CatalogDiscovery.Result neither = thinkingDiscovery(seed, Scripted.listing("alpha", "wire-new"), Set.of(), asked).discover();
        assertEquals("NONE", written(neither, "a-new").get("thinking_mode").asText(), "a model that refuses every request to think");
        assertTrue(neither.thinkingModes().get(0).endsWith("EXTENDED -> NONE (the model refused every request to think)"), neither.thinkingModes().get(0));
    }

    /**
     * A thinking call that fails for a reason other than the thinking parameters teaches
     * nothing: the entry keeps its mode and the report says it was not checked, with the
     * reason. An entry written {@code NONE} or {@code REASONING_EFFORT} is not asked at all,
     * and neither is any entry of a run with no thinking ping.
     */
    @Test
    void aThinkingCallThatFailsForAnotherReasonLeavesTheModeAndSaysItWasNotChecked() {
        JsonModelsBackend seed = pricedCatalog(true, thinking("a-new", "wire-new", "ADAPTIVE"), thinking("a-plain", "wire-plain", "NONE"),
                thinking("a-effort", "wire-effort", "REASONING_EFFORT"));
        List<String> asked = new ArrayList<>();
        CatalogDiscovery discovery = new CatalogDiscovery(seed, null, Map.of("alpha", Scripted.listing("alpha", "wire-new", "wire-plain", "wire-effort")), (p, s) -> ok(s, 55000));
        discovery.thinkingPing = (p, s) -> {
            asked.add(s.getId());
            return refused(s, "400: max_tokens: 9 is below the minimum");
        };
        CatalogDiscovery.Result result = discovery.discover();
        assertEquals(List.of("a-new"), asked, "only the entry written ADAPTIVE or EXTENDED is asked to think");
        assertEquals("ADAPTIVE", written(result, "a-new").get("thinking_mode").asText(), "a failure that does not name thinking changes nothing");
        assertEquals(1, result.thinkingModes().size());
        assertTrue(result.thinkingModes().get(0).contains("ADAPTIVE NOT CHECKED"), result.thinkingModes().get(0));
        assertTrue(result.thinkingModes().get(0).contains("max_tokens: 9 is below the minimum"), result.thinkingModes().get(0));
        CatalogDiscovery.Result untested = new CatalogDiscovery(seed, null, Map.of("alpha", Scripted.listing("alpha", "wire-new")), (p, s) -> ok(s, 55000)).discover();
        assertTrue(untested.thinkingModes().isEmpty(), "a run with no thinking ping reports nothing about thinking modes");
    }

    /** Where the listing states how a model thinks, that is the entry's mode, whatever it inherited, and the model is not asked. */
    @Test
    void aThinkingModeTheListingStatesIsWrittenAndTheModelIsNotAsked() {
        JsonModelsBackend seed = pricedCatalog(true, thinking("a-new", "wire-new", "EXTENDED"), thinking("a-same", "wire-same", "ADAPTIVE"));
        Scripted alpha = Scripted.listing("alpha", "wire-new", "wire-same");
        alpha.stateThinking("wire-new", ThinkingMode.ADAPTIVE);
        alpha.stateThinking("wire-same", ThinkingMode.ADAPTIVE);
        List<ThinkingMode> asked = new ArrayList<>();
        CatalogDiscovery.Result result = thinkingDiscovery(seed, alpha, Set.of(), asked).discover();
        assertEquals("ADAPTIVE", written(result, "a-new").get("thinking_mode").asText());
        assertTrue(asked.isEmpty(), "a mode the listing states needs no call");
        assertEquals(1, result.thinkingModes().size(), "the entry whose mode already was the stated one is not news");
        assertTrue(result.thinkingModes().get(0).endsWith("EXTENDED -> ADAPTIVE (stated by the provider's listing)"), result.thinkingModes().get(0));
    }

    /**
     * An open entry the deployment's file already holds is asked to think as written on every
     * run, though it is not pinged and nothing else of it is touched: a mode the model refuses
     * is corrected and reported, and a closed entry is left alone.
     */
    @Test
    void anEntryAlreadyInTheFileHasItsThinkingModeCheckedWithoutAPing() {
        String open = thinking("a-kept", "wire-kept", "EXTENDED");
        String closed = thinking("a-closed", "wire-closed", "EXTENDED").replace("\"grade\"", "\"status\": \"DISABLED\", \"grade\"");
        JsonModelsBackend seed = pricedCatalog(true, open, closed);
        JsonModelsBackend previous = pricedCatalog(false, open, closed);
        List<String> pinged = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        CatalogDiscovery discovery = new CatalogDiscovery(seed, previous, Map.of("alpha", Scripted.listing("alpha", "wire-kept", "wire-closed")), (p, s) -> {
            pinged.add(s.getId());
            return ok(s, 55000);
        });
        discovery.thinkingPing = (p, s) -> {
            asked.add(s.getId() + " " + s.getThinkingMode());
            return s.getThinkingMode() == ThinkingMode.ADAPTIVE ? ok(s, 55000) : refused(s, "400: \"thinking.type.enabled\" is not supported for this model");
        };
        CatalogDiscovery.Result result = discovery.discover();
        assertTrue(pinged.isEmpty(), "an entry already in the file is not pinged: " + pinged);
        assertEquals(List.of("a-kept EXTENDED", "a-kept ADAPTIVE"), asked, "the open entry is asked to think, the closed one is not");
        assertEquals("ADAPTIVE", written(result, "a-kept").get("thinking_mode").asText());
        assertEquals("EXTENDED", written(result, "a-closed").get("thinking_mode").asText(), "a closed entry is left as it is");
        assertEquals(1000, written(result, "a-kept").get("tpm").intValue(), "nothing else of the kept entry is touched");
        assertTrue(result.thinkingModes().get(0).startsWith("a-kept"), result.thinkingModes().toString());
    }

    private static CatalogDiscovery.Verdict verdict(CatalogDiscovery.Result result, String id) {
        return result.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().verdict();
    }

    /** The wire id a per-listing classifier call was asked about: the prompt's last line is "provider_key  wire_model_id". */
    private static String subjectOf(String prompt) {
        String[] lines = prompt.strip().split("\n");
        String last = lines[lines.length - 1];
        return last.substring(last.indexOf("  ") + 2).strip();
    }
}
