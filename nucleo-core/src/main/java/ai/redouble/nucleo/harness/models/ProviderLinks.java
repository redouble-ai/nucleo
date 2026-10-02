/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/**
 * The link between a catalog entry and the provider that serves it. A catalog is written for
 * the providers of the classpath that wrote it, and a process whose classpath carries others
 * (a native image built without an SDK the JVM build has) reads the same file. So a written
 * catalog records its providers under {@link #PROVIDERS}, each with its {@link #PLATFORM} and
 * its {@link #ADDRESSING} (the endpoint family its wire ids are spelled for,
 * {@link ClientProvider#addressing()}), and every entry its own {@link #PLATFORM}: where it came
 * from is known at the moment it is written, and nowhere else afterwards.
 *
 * <p>A loader that finds an entry whose provider is not on its classpath links it to a
 * provider here of the same addressing ({@link #resolve}): the first, in key order, that
 * {@link ClientProvider#claims claims} the entry's model, else the first that
 * {@link ClientProvider#serves serves} it, always of the entry's {@link ModelKind}. The
 * catalog id, the wire id and every field the entry states are kept; only the provider
 * changes. An entry no provider here of its addressing fits cannot be served by this process.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public final class ProviderLinks {
    /** The catalog's record of the providers it was written for: provider key to {@code {"platform": ..., "addressing": ...}}. */
    public static final String PROVIDERS = "providers";
    /** The platform of a provider in the record, and of an entry. */
    public static final String PLATFORM = "platform";
    /** The addressing of a provider in the record: the endpoint family its wire ids are spelled for. */
    public static final String ADDRESSING = "addressing";

    private ProviderLinks() {}

    /**
     * Records in a catalog the providers it is written for, and stamps each entry with its
     * provider's platform. The record names every provider given, and every provider an entry
     * names that is not given but whose platform is known, from the entry itself or from the
     * record the catalog already carries: a catalog keeps entries of a provider the writing
     * process does not have, and says what they are. Such a provider's addressing is the carried
     * record's word, else its platform.
     */
    public static void stamp(ObjectNode catalog, Map<String, ClientProvider<?>> providers) {
        JsonNode carried = catalog.get(PROVIDERS);
        Map<String, Facts> recorded = new TreeMap<>();
        providers.forEach((key, provider) -> recorded.put(key, new Facts(provider.platform(), provider.addressing())));
        for (JsonNode entry : catalog.get("models")) {
            String key = entry.get("provider_key").asText();
            ClientProvider<?> provider = providers.get(key);
            if (provider != null) {
                ((ObjectNode) entry).put(PLATFORM, provider.platform());
            }
            else if (!recorded.containsKey(key)) {
                String platform = recordedPlatform(entry, key, carried);
                if (platform != null) {
                    recorded.put(key, new Facts(platform, recordedAddressing(entry, key, carried)));
                }
            }
        }
        ObjectNode record = NucleoJsonSerializer.createObjectNode();
        recorded.forEach((key, facts) -> {
            ObjectNode provider = record.putObject(key);
            provider.put(PLATFORM, facts.platform());
            provider.put(ADDRESSING, facts.addressing());
        });
        catalog.set(PROVIDERS, record);
    }

    /** What the record states about one provider. */
    private record Facts(String platform, String addressing) {}

    /** The platform an entry was written on: its own word, else the record's for its provider; null when neither says. */
    public static String recordedPlatform(JsonNode entry, String providerKey, JsonNode record) {
        JsonNode own = entry.get(PLATFORM);
        if (own != null && own.isTextual() && !own.asText().isBlank()) {
            return own.asText();
        }
        return recordedFact(record, providerKey, PLATFORM);
    }

    /**
     * The addressing an entry's wire id is spelled for: the record's word for its provider, else
     * its platform, the default addressing of every provider; null when not even the platform is
     * known.
     */
    public static String recordedAddressing(JsonNode entry, String providerKey, JsonNode record) {
        String addressing = recordedFact(record, providerKey, ADDRESSING);
        return addressing != null ? addressing : recordedPlatform(entry, providerKey, record);
    }

    private static String recordedFact(JsonNode record, String providerKey, String field) {
        JsonNode recorded = record != null ? record.get(providerKey) : null;
        JsonNode fact = recorded != null ? recorded.get(field) : null;
        return fact != null && fact.isTextual() && !fact.asText().isBlank() ? fact.asText() : null;
    }

    /** The provider keys a catalog records, or null when it records none. */
    public static Set<String> recordedKeys(JsonNode record) {
        if (record == null || !record.isObject()) {
            return null;
        }
        Set<String> keys = new TreeSet<>();
        record.properties().forEach(field -> keys.add(field.getKey()));
        return keys;
    }

    /**
     * The provider of an addressing to link an entry to, among the given ones, or null when none
     * fits: of the providers of that addressing and kind, the first in key order that claims the
     * wire id, else the first that serves it. The first hit wins; two providers claiming the
     * same model is a conflict between the artifacts that ship them, settled here by key order
     * so every run links the same way.
     */
    public static ClientProvider<?> resolve(Map<String, ClientProvider<?>> providers, String addressing, ModelKind kind, String wireModelId) {
        ClientProvider<?> serving = null;
        for (String key : new TreeSet<>(providers.keySet())) {
            ClientProvider<?> provider = providers.get(key);
            if (!addressing.equals(provider.addressing()) || ModelKind.ofProviderKey(key) != kind) {
                continue;
            }
            if (provider.claims(wireModelId)) {
                return provider;
            }
            if (serving == null && provider.serves(wireModelId)) {
                serving = provider;
            }
        }
        return serving;
    }
}
