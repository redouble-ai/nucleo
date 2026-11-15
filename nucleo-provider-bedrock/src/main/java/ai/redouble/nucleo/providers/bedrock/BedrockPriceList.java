/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.http.apache5.*;
import software.amazon.awssdk.regions.*;

import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The AWS price list for Bedrock, read into the standard on-demand token rates of every model
 * it prices, so a listed model carries what a call on it costs. AWS publishes the list as
 * public JSON, one file per service and region and no credential to read it:
 * {@code AmazonBedrockFoundationModels} prices the first-party vendors (Anthropic, Amazon,
 * Cohere, OpenAI, AI21, Writer) and {@code AmazonBedrock} the Mantle models and Nova. A product
 * of a file is one billing line: its {@code provider} and {@code model} are the names the
 * control plane lists the model under, so a price matches a model on the names its quota
 * matches on, or {@code model} is the raw id where AWS lists no name; its {@code usagetype} names
 * the billing dimension and the variant it applies to; its on-demand term carries the rate per
 * {@code 1K tokens} or {@code 1M tokens}.
 *
 * <p>A billing line is understood through the words of its {@code usagetype}, which AWS writes
 * in two grammars: {@code USE1-MP:USE1_<dimension>-Units}, where the dimension is the whole
 * middle, and {@code USE1-<model>-<dimension>}, where the model's own name precedes the
 * dimension and the dimension starts at its kind word. The dimension's words are the kind
 * ({@code input}, {@code output}, {@code cache read}, {@code cache write}), {@code global} for
 * the rate of a {@code global.} profile, and a fixed vocabulary of structural words;
 * {@code mantle} anywhere in the line marks the Mantle surface's rate. Any other word marks a
 * variant the runtime does not buy, a tier ({@code flex}, {@code priority}, {@code batch}), a
 * long-context rate, a cache write held an hour, latency-optimized inference, reserved
 * capacity, a modality other than text, and the line prices nothing. A word this class does not
 * know is a variant by that rule, never silently the standard rate. Two lines left for one
 * kind at different rates (an old model whose versions share a name) price nothing and say
 * so, rather than pick one.
 *
 * <p>Beside the standard rates, every line of a model that is priced per token is kept as it
 * is written, the {@code usagetype} to the rate per million: the model's listed rates. No word
 * of a line decides whether it is kept, so a variant this class prices nothing from, and a
 * word AWS writes next month, are both there for whoever reads the catalog entry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-19)
 */
public final class BedrockPriceList {
    private static final Logger log = LoggerFactory.getLogger(BedrockPriceList.class);
    /** Where AWS serves the list; the host is the same for every region, the region names the file. */
    static final String OFFERS = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/";
    /** The two services whose files price the models the runtime calls. */
    static final List<String> SERVICES = List.of("AmazonBedrockFoundationModels", "AmazonBedrock");
    /** The words of a billing line that name its kind, its surface or nothing: every other word is a variant. */
    private static final Set<String> STRUCTURAL = Set.of("input", "output", "cache", "read", "write", "prompt", "text",
            "token", "tokens", "count", "units", "cross", "region", "standard", "mantle", "global");
    /** The words a billing dimension starts at in the model-named grammar: what precedes is the region and the model. */
    private static final Set<String> KIND_WORDS = Set.of("input", "output", "cache");
    /** A modality the model's name may be followed by before the kind word; only text is the standard rate. */
    private static final Set<String> OTHER_MODALITIES = Set.of("speech", "audio", "image", "video");
    /** The second word of the grammar whose dimension is the whole middle of the line. */
    private static final String MARKETPLACE = "mp";
    private static final Map<String, BedrockPriceList> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> CACHE_AT = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    enum Kind {INPUT, OUTPUT, CACHE_READ, CACHE_WRITE}

    /** One billing line of the list that prices a token at the standard on-demand rate, per million. */
    record Row(String provider, String model, String usageType, Kind kind, boolean global, boolean mantle, double perMillion, String currency) {}

    /** What a line's {@code usagetype} says: its kind, whether it is the global profile's rate, whether it is the Mantle surface's. */
    record Line(Kind kind, boolean global, boolean mantle) {}

    /** One billing line of the list priced per token, whatever its {@code usagetype} says, per million. */
    record Rate(String usageType, double perMillion, String currency) {}

    /**
     * What the list says of a listed id. Its standard prices, or the note on why it has none:
     * exactly one of those two is set. And every rate per token the list states for the model,
     * null when the list has no line for it, with a note on any line left out of them.
     */
    public record Pricing(DiscoveredModel.Prices prices, String note, DiscoveredModel.ListedRates listedRates, String listedNote) {}

    private final Map<String, List<Row>> byProviderAndModel = new HashMap<>();
    private final Map<String, List<Row>> byModel = new HashMap<>();
    private final Map<String, List<Rate>> ratesByProviderAndModel = new HashMap<>();
    private final Map<String, List<Rate>> ratesByModel = new HashMap<>();
    /** Why the list could not be read, in which case it holds no row; null for a list that was read. */
    private final String failure;

    private BedrockPriceList(String failure) {
        this.failure = failure;
    }

    /** A list that could not be read: every id asked of it carries no price and the reason. */
    static BedrockPriceList unreadable(String failure) {
        return new BedrockPriceList(failure);
    }

    /**
     * The region's list, held briefly: the three Bedrock surfaces of one discovery run read the
     * same two files, and a list does not move mid-run. A failed read is not cached, so the next
     * surface tries again.
     */
    public static BedrockPriceList forRegion(Region region) {
        BedrockPriceList cached = CACHE.get(region.id());
        Long at = CACHE_AT.get(region.id());
        if (cached != null && at != null && System.currentTimeMillis() - at < CACHE_TTL.toMillis()) {
            return cached;
        }
        BedrockPriceList fresh = read(region);
        if (fresh.failure == null) {
            CACHE.put(region.id(), fresh);
            CACHE_AT.put(region.id(), System.currentTimeMillis());
        }
        return fresh;
    }

    /** Reads the region's two files. The list is the listing's enrichment, not its subject: a read that fails is a list that prices nothing, with the reason. */
    static BedrockPriceList read(Region region) {
        List<String> bodies = new ArrayList<>();
        try (SdkHttpClient http = Apache5HttpClient.builder().build()) {
            for (String service : SERVICES) {
                bodies.add(fetch(http, URI.create(OFFERS + service + "/current/" + region.id() + "/index.json")));
            }
            return parse(bodies);
        }
        catch (IOException e) {
            log.warn("The AWS price list for {} is not readable, every listed entry keeps its price: {}", region.id(), e.getMessage());
            return unreadable(e.getMessage());
        }
    }

    private static String fetch(SdkHttpClient http, URI uri) throws IOException {
        SdkHttpFullRequest request = SdkHttpFullRequest.builder().method(SdkHttpMethod.GET).uri(uri).putHeader("Accept", "application/json").build();
        HttpExecuteResponse response = http.prepareRequest(HttpExecuteRequest.builder().request(request).build()).call();
        String body;
        try (InputStream is = response.responseBody().orElseThrow(() -> new IOException(uri + " returned no body"))) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!response.httpResponse().isSuccessful()) {
            throw new IOException(uri + " answered " + response.httpResponse().statusCode());
        }
        return body;
    }

    /** The files' shape, as a pure function so it tests on recorded excerpts. */
    static BedrockPriceList parse(List<String> bodies) throws IOException {
        BedrockPriceList list = new BedrockPriceList(null);
        for (String body : bodies) {
            JsonNode root = NucleoJsonSerializer.readTree(body);
            JsonNode products = root.get("products");
            if (products == null || !products.isObject()) {
                throw new IOException("A price list file carried no 'products' object");
            }
            JsonNode onDemand = root.path("terms").path("OnDemand");
            products.properties().forEach(product -> list.add(product.getKey(), product.getValue().path("attributes"), onDemand.path(product.getKey())));
        }
        return list;
    }

    private void add(String sku, JsonNode attributes, JsonNode terms) {
        String usageType = attributes.path("usagetype").asText(null);
        String model = attributes.path("model").asText(null);
        if (usageType == null || model == null) {
            return;
        }
        // null for a line that is not a standard rate: it prices nothing and is still a listed rate
        Line line = analyze(usageType);
        String provider = attributes.path("provider").asText(null);
        for (JsonNode term : terms) {
            for (JsonNode dimension : term.path("priceDimensions")) {
                Integer perMillion = perMillion(dimension.path("unit").asText(""));
                JsonNode pricePerUnit = dimension.path("pricePerUnit");
                if (perMillion == null || pricePerUnit.isEmpty()) {
                    continue;
                }
                // the rate is stated once, under its currency, as a decimal string: scaled as a
                // decimal, so that a rate per thousand and the same rate per million are one number
                Map.Entry<String, JsonNode> rate = pricePerUnit.properties().iterator().next();
                double scaled = new BigDecimal(rate.getValue().asText()).multiply(BigDecimal.valueOf(perMillion)).doubleValue();
                Rate listed = new Rate(usageType, scaled, rate.getKey());
                if (provider != null) {
                    ratesByProviderAndModel.computeIfAbsent(key(provider, model), k -> new ArrayList<>()).add(listed);
                }
                ratesByModel.computeIfAbsent(model.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(listed);
                if (line == null) {
                    continue;
                }
                Row row = new Row(provider, model, usageType, line.kind(), line.global(), line.mantle(), scaled, rate.getKey());
                if (provider != null) {
                    byProviderAndModel.computeIfAbsent(key(provider, model), k -> new ArrayList<>()).add(row);
                }
                byModel.computeIfAbsent(model.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(row);
            }
        }
    }

    /** How many of the unit go in a million tokens, or null for a dimension not priced per token (reserved capacity, model units, images). */
    private static Integer perMillion(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "1k tokens" -> 1000;
            case "1m tokens" -> 1;
            default -> null;
        };
    }

    /**
     * What a {@code usagetype} says, or null for a line that is not a standard on-demand token
     * rate. The line is split into words on its separators and its camel-case boundaries. In
     * the marketplace grammar ({@code USE1-MP:USE1_...}) the dimension is every word after the
     * second region code; in the model-named grammar it starts at the first kind word, what
     * precedes being the region code, the model's name and at most a modality, of which only
     * text is the standard rate. {@code mantle} is read from the whole line, because it precedes
     * the kind words. Every word of the dimension must be structural, except that {@code global}
     * is the variant this class understands; any other word is a variant it leaves out.
     */
    static Line analyze(String usageType) {
        List<String> words = new ArrayList<>();
        for (String segment : usageType.split("[-_:]")) {
            for (String word : segment.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (!word.isEmpty()) {
                    words.add(word.toLowerCase(Locale.ROOT));
                }
            }
        }
        int start;
        if (words.size() > 3 && MARKETPLACE.equals(words.get(1))) {
            start = 3;
        }
        else {
            start = -1;
            for (int i = 0; i < words.size() && start < 0; i++) {
                if (KIND_WORDS.contains(words.get(i))) {
                    start = i;
                }
            }
            if (start < 0 || (start > 0 && OTHER_MODALITIES.contains(words.get(start - 1)))) {
                return null;
            }
        }
        List<String> dimension = words.subList(start, words.size());
        Kind kind;
        if (dimension.contains("cache")) {
            kind = dimension.contains("read") ? Kind.CACHE_READ : dimension.contains("write") ? Kind.CACHE_WRITE : null;
        }
        else {
            kind = dimension.contains("input") ? Kind.INPUT : dimension.contains("output") ? Kind.OUTPUT : null;
        }
        if (kind == null) {
            return null;
        }
        for (String word : dimension) {
            if (!STRUCTURAL.contains(word)) {
                return null;
            }
        }
        return new Line(kind, dimension.contains("global"), words.contains("mantle"));
    }

    /**
     * The prices of a listed id, or why it has none. The model is found by the provider and
     * model names the control plane lists it under, by the model name alone where the list
     * states no provider (Nova), else by its bare id where the list spells the raw id; the rows
     * taken are the global profile's for a {@code global.} id and the regional ones for every
     * other, which is what AWS bills a geography profile at, and the Mantle surface's rows for
     * a Mantle id where the list prices the surface apart. The input rate is required; the
     * others are carried where the list has them. The listed rates are every per-token line
     * of the same model, of every profile and surface, whether or not it has a standard price.
     *
     * @param wireId       the id as listed
     * @param providerName the provider's name as the control plane lists it, null when unknown
     * @param modelName    the model's name as the control plane lists it, null when unknown
     * @param mantle       whether the id is the Mantle surface's
     */
    public Pricing pricesOf(String wireId, String providerName, String modelName, boolean mantle) {
        if (failure != null) {
            return new Pricing(null, "price list unreadable: " + failure, null, null);
        }
        Listed listed = listed(find(ratesByProviderAndModel, ratesByModel, wireId, providerName, modelName));
        Pricing standard = standardPricesOf(wireId, providerName, modelName, mantle);
        return new Pricing(standard.prices(), standard.note(), listed.rates(), listed.note());
    }

    /** A model's lines, found by the provider and model names, by the model name alone, else by the bare id; null when the list has none. */
    private static <T> List<T> find(Map<String, List<T>> byProviderAndModel, Map<String, List<T>> byModel, String wireId, String providerName, String modelName) {
        List<T> rows = providerName != null && modelName != null ? byProviderAndModel.get(key(providerName, modelName)) : null;
        if (rows == null && modelName != null) {
            rows = byModel.get(modelName.toLowerCase(Locale.ROOT));
        }
        if (rows == null) {
            rows = byModel.get(ModelLineage.bare(wireId).toLowerCase(Locale.ROOT));
        }
        return rows;
    }

    /** A model's listed rates, and a note on any line left out of them; both null when the list has no line for the model. */
    private record Listed(DiscoveredModel.ListedRates rates, String note) {}

    /**
     * Every per-token line of a model as its listed rates: the {@code usagetype} to the rate
     * per million, ordered by {@code usagetype} so that two readings of the list differ only
     * where a rate did. A {@code usagetype} the list states at two different rates (two
     * operations billed under one name) is left out, because one name holds one rate, and the
     * note counts those; lines in more than one currency are no listed rates at all, because
     * the rates of an entry are in its one currency.
     */
    private static Listed listed(List<Rate> rates) {
        if (rates == null) {
            return new Listed(null, null);
        }
        List<String> currencies = rates.stream().map(Rate::currency).distinct().toList();
        if (currencies.size() > 1) {
            return new Listed(null, "no listed rates: the price list states this model in " + currencies);
        }
        Map<String, Double> perMillion = new TreeMap<>();
        Set<String> conflicting = new TreeSet<>();
        for (Rate rate : rates) {
            Double before = perMillion.putIfAbsent(rate.usageType(), rate.perMillion());
            if (before != null && before != rate.perMillion()) {
                conflicting.add(rate.usageType());
            }
        }
        conflicting.forEach(perMillion::remove);
        String note = conflicting.isEmpty() ? null : conflicting.size() + " listed at two rates and left out of the listed rates: " + conflicting;
        return new Listed(perMillion.isEmpty() ? null : new DiscoveredModel.ListedRates(perMillion, currencies.get(0)), note);
    }

    private Pricing standardPricesOf(String wireId, String providerName, String modelName, boolean mantle) {
        List<Row> rows = find(byProviderAndModel, byModel, wireId, providerName, modelName);
        if (rows == null) {
            return new Pricing(null, "not in the price list" + (providerName != null && modelName != null ? " as " + providerName + " " + modelName : ""), null, null);
        }
        boolean onMantle = mantle && rows.stream().anyMatch(Row::mantle);
        boolean global = ModelLineage.GLOBAL.equals(ModelLineage.prefix(wireId));
        Map<Kind, Row> chosen = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            List<Row> candidates = rows.stream().filter(r -> r.kind() == kind && r.global() == global && r.mantle() == onMantle).toList();
            if (candidates.stream().map(Row::perMillion).distinct().count() > 1) {
                return new Pricing(null, "the price list has " + candidates.size() + " " + kind + " rows at different rates: "
                        + candidates.stream().map(Row::usageType).toList(), null, null);
            }
            if (!candidates.isEmpty()) {
                chosen.put(kind, candidates.get(0));
            }
        }
        Row input = chosen.get(Kind.INPUT);
        if (input == null) {
            return new Pricing(null, "no standard on-demand " + (global ? "global " : "") + "input rate in the price list", null, null);
        }
        return new Pricing(new DiscoveredModel.Prices(input.perMillion(), rate(chosen, Kind.OUTPUT), rate(chosen, Kind.CACHE_READ),
                rate(chosen, Kind.CACHE_WRITE), input.currency()), null, null, null);
    }

    private static Double rate(Map<Kind, Row> chosen, Kind kind) {
        Row row = chosen.get(kind);
        return row != null ? row.perMillion() : null;
    }

    private static String key(String provider, String model) {
        return provider.toLowerCase(Locale.ROOT) + "|" + model.toLowerCase(Locale.ROOT);
    }
}
