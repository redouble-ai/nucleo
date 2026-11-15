/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.http.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.io.*;
import java.math.*;
import java.util.*;

/**
 * Microsoft's retail price list for the models of Azure AI Foundry in one region, read into
 * the rates it states for each model. Microsoft publishes the list as public JSON with no
 * credential to read it, {@code https://prices.azure.com/api/retail/prices}, a page of up to
 * a thousand meters at a time; the meters of the service {@code Foundry Models} are the models
 * a Foundry resource serves. A meter is one billing line of one region: its {@code meterName}
 * names the model and the dimension in free text, its {@code unitOfMeasure} is {@code 1K} or
 * {@code 1M}, and its {@code retailPrice} is the rate for that many tokens.
 *
 * <p>The list is read for a region because every region has its meters: a Global deployment
 * is one rate in all of them, a Data Zone deployment is not (the government regions bill it
 * higher), so the rates of a resource are the rates of the region it is in.
 *
 * <p>A meter has no field that names its model. Its name starts with the model in the
 * spelling of whoever entered it, {@code 5.6 terra ShortCo Inp Std Gl},
 * {@code 61 terra ShortCo Inp Std Gl} for GPT-6.1, {@code gpt-5-codex-inp-glbl},
 * {@code FW DeepSeek-V4-Pro Ch Inp DZ}, {@code Code Fast 1 Outp DZone}, and goes on with the
 * dimension. The model's part is every word before the first word of a dimension, and it
 * matches a deployment's base model when the two are the same letters and digits once the
 * punctuation and a leading vendor word ({@code gpt}, {@code grok}, {@code cohere}, the
 * reseller mark {@code FW}) are dropped; a vendor word dropped from the base model must be in
 * the meter's {@code productName}, so that {@code 4.7} of one vendor is not {@code 4.7} of
 * another. A base model that ends in a date the meters do not spell is tried without it. A
 * base model no meter matches has no rates, and says so: it never takes the meters of a model
 * with a similar name.
 *
 * <p>Every matched meter priced per token is one of the model's listed rates, under its
 * {@code meterName} and per million, whatever its words say. Beside them the list is read for
 * the standard price of each deployment type, because the listing a resource key reads does
 * not say which type a deployment is: a meter is a standard line when its dimension is a kind
 * ({@code Inp} or {@code In}; {@code Opt}, {@code Outp} or {@code out}; {@code Cd Inp}, {@code Cached}, {@code ccchd} or {@code Ch Inp};
 * {@code Cd Wr}), a deployment type ({@code Gl} or {@code glbl}, {@code DZ} or {@code DZone},
 * {@code regnl}) and at most {@code Std} and {@code ShortCo}. Any other word, {@code LongCo},
 * {@code Flex}, {@code PP}, a word Microsoft writes next month, makes it a variant that is no
 * one's standard price.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
final class AzurePriceList {
    private static final Logger log = LoggerFactory.getLogger(AzurePriceList.class);
    static final String HOST = "https://prices.azure.com";
    static final String PATH = "/api/retail/prices";
    /** The service whose meters are the models of a Foundry resource. */
    static final String SERVICE = "Foundry Models";
    /** A leading word of a model's name that one side writes and the other leaves out. */
    private static final List<String> VENDOR_WORDS = List.of("gpt", "grok", "cohere", "fw");
    /** The words a dimension starts at: what precedes the first of them in a meter's name is the model. */
    private static final Set<String> DIMENSION_WORDS = Set.of("shortco", "longco", "inp", "in", "opt", "outp", "out", "cd", "cached", "ch", "ccchd", "wr",
            "img", "txt", "gl", "glbl", "dz", "dzone", "regnl", "std", "flex", "pp", "batch");
    /** The words of a standard line that name neither its kind nor its deployment type. */
    private static final Set<String> STRUCTURAL = Set.of("std", "shortco", "1m", "1k", "tokens");
    /** The words that spell a line's kind. */
    private static final Set<String> KIND_WORDS = Set.of("wr", "cd", "cached", "ch", "ccchd", "inp", "in", "opt", "outp", "out");
    private static final Map<String, String> DEPLOYMENT_TYPES = Map.of("gl", "Global", "glbl", "Global", "dz", "Data Zone", "dzone", "Data Zone", "regnl", "Regional");

    enum Kind {INPUT, OUTPUT, CACHE_READ, CACHE_WRITE}

    /** What a standard line's dimension says: its kind and the deployment type it is the rate of. */
    record Line(Kind kind, String deploymentType) {}

    /** One meter priced per token: its name, the model its name starts with, its product, what its dimension says when it is a standard line, and its rate per million. */
    record Meter(String meterName, String model, String productName, Line line, double perMillion, String currency) {}

    /** What the list says of a base model: its listed rates, the standard price of each deployment type it states, and the note on what is missing; the first two null when no meter matches. */
    record Rates(DiscoveredModel.ListedRates listedRates, List<DiscoveredModel.PriceOption> priceOptions, String note) {}

    private final List<Meter> meters = new ArrayList<>();
    /** Why there is no list, in which case it holds no meter and this is every model's note; null for a list that was read. */
    private final String failure;

    private AzurePriceList(String failure) {
        this.failure = failure;
    }

    /** A list that could not be read: every model asked of it has no rates and the reason. */
    static AzurePriceList unreadable(String failure) {
        return new AzurePriceList("price list unreadable: " + failure);
    }

    /** No list, because the resource did not say which region it is in and the list is a region's: every model asked of it has no rates and that reason. */
    static AzurePriceList regionUnknown() {
        return new AzurePriceList("no listed rates: the resource's listing did not state its region");
    }

    /** The transport: the framework's own client against the public endpoint, no credential. */
    private static final class Reader extends AbstractApiClient {
        @Override
        protected String getServiceName() {
            return "Azure retail price list";
        }

        @Override
        protected String getBaseUrl() {
            return HOST;
        }

        String page(String pathAndQuery) throws LLMReadableCheckedException {
            return getText(pathAndQuery, "application/json");
        }
    }

    /**
     * Reads the region's meters, page after page along the list's own link to the next. The
     * list is the listing's enrichment, not its subject: a read that fails is a list with no
     * meter, and the reason.
     *
     * @param armRegion the region as the list names it ({@code eastus2}, {@code swedencentral})
     */
    static AzurePriceList read(String armRegion) {
        Reader reader = new Reader();
        List<String> pages = new ArrayList<>();
        String next = PATH + "?$filter=" + AbstractApiClient.encode("serviceName eq '" + SERVICE + "' and armRegionName eq '" + armRegion + "' and priceType eq 'Consumption'");
        try {
            while (next != null) {
                String body = reader.page(next);
                pages.add(body);
                String link = NucleoJsonSerializer.readTree(body).path("NextPageLink").asText(null);
                if (link != null && !link.startsWith(HOST)) {
                    throw new IOException("the price list's next page is on another host: " + link);
                }
                next = link != null ? link.substring(HOST.length()) : null;
            }
            return parse(pages);
        }
        catch (LLMReadableCheckedException | IOException e) {
            log.warn("The Azure retail price list for {} is not readable, no entry gets its listed rates: {}", armRegion, e.getMessage());
            return unreadable(e.getMessage());
        }
    }

    /** The pages' shape, as a pure function so it tests on recorded excerpts. */
    static AzurePriceList parse(List<String> pages) throws IOException {
        AzurePriceList list = new AzurePriceList(null);
        for (String page : pages) {
            JsonNode items = NucleoJsonSerializer.readTree(page).get("Items");
            if (items == null || !items.isArray()) {
                throw new IOException("A price list page carried no 'Items' array");
            }
            for (JsonNode item : items) {
                list.add(item);
            }
        }
        return list;
    }

    private void add(JsonNode item) {
        String meterName = item.path("meterName").asText(null);
        Integer perMillion = perMillion(item.path("unitOfMeasure").asText(""));
        // a meter of another unit (provisioned capacity per hour, pages, searches) is not a rate per token
        if (meterName == null || perMillion == null || !meterName.toLowerCase(Locale.ROOT).endsWith("tokens")
                || !"Consumption".equals(item.path("type").asText(null)) || !item.hasNonNull("retailPrice")) {
            return;
        }
        List<String> words = words(meterName);
        int dimension = 0;
        while (dimension < words.size() && !DIMENSION_WORDS.contains(words.get(dimension))) {
            dimension++;
        }
        // the rate is a decimal: scaled as one, so that a rate per thousand and the same rate per million are one number
        double scaled = new BigDecimal(item.get("retailPrice").asText()).multiply(BigDecimal.valueOf(perMillion)).doubleValue();
        meters.add(new Meter(meterName, stem(String.join("", words.subList(0, dimension))), item.path("productName").asText(""),
                analyze(words.subList(dimension, words.size())), scaled, item.path("currencyCode").asText(null)));
    }

    /** How many of the unit go in a million tokens, or null for a unit that is not a count of tokens. */
    private static Integer perMillion(String unit) {
        return switch (unit) {
            case "1K" -> 1000;
            case "1M" -> 1;
            default -> null;
        };
    }

    /** A name's words, lower-case: split on spaces and hyphens, the punctuation inside a word dropped, so {@code 6.1-sol} is {@code 61}, {@code sol}. */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String word : name.toLowerCase(Locale.ROOT).split("[\\s-]+")) {
            String bare = word.replaceAll("[^a-z0-9]", "");
            if (!bare.isEmpty()) {
                words.add(bare);
            }
        }
        return words;
    }

    /** A model's letters and digits without a leading vendor word. */
    private static String stem(String joined) {
        for (String vendor : VENDOR_WORDS) {
            if (joined.startsWith(vendor) && joined.length() > vendor.length()) {
                return joined.substring(vendor.length());
            }
        }
        return joined;
    }

    /** The vendor word a base model starts with, or null: a meter matched without it must name it in its product. */
    private static String vendorOf(String joined) {
        for (String vendor : VENDOR_WORDS) {
            if (joined.startsWith(vendor) && joined.length() > vendor.length()) {
                return vendor;
            }
        }
        return null;
    }

    /**
     * What a dimension says, or null for a meter that is not a standard line: the kind, the
     * deployment type, and no word beyond the structural ones. A dimension with no deployment
     * type is not a standard line, because there is no type it would be the rate of.
     */
    static Line analyze(List<String> dimension) {
        Kind kind;
        if (dimension.contains("wr")) {
            kind = Kind.CACHE_WRITE;
        }
        else if (dimension.contains("cd") || dimension.contains("cached") || dimension.contains("ch") || dimension.contains("ccchd")) {
            kind = Kind.CACHE_READ;
        }
        else if (dimension.contains("inp") || dimension.contains("in")) {
            kind = Kind.INPUT;
        }
        else if (dimension.contains("opt") || dimension.contains("outp") || dimension.contains("out")) {
            kind = Kind.OUTPUT;
        }
        else {
            return null;
        }
        String deploymentType = null;
        for (String word : dimension) {
            if (DEPLOYMENT_TYPES.containsKey(word)) {
                if (deploymentType != null) {
                    return null;
                }
                deploymentType = DEPLOYMENT_TYPES.get(word);
            }
            else if (!STRUCTURAL.contains(word) && !KIND_WORDS.contains(word)) {
                return null;
            }
        }
        return deploymentType != null ? new Line(kind, deploymentType) : null;
    }

    /**
     * The rates of a deployment's base model. Its listed rates are every matched meter under
     * its name, ordered by name so that two readings of the list differ only where a rate
     * did; a name the region states at two rates is left out, because one name holds one
     * rate, and the note counts those. Its price options are the standard price of each
     * deployment type the meters state, in the order Global, Data Zone, Regional; a type with
     * no input rate, or with two rates for one kind, is no option.
     *
     * @param baseModel the model behind a deployment, as the resource's listing names it
     */
    Rates ratesOf(String baseModel) {
        if (failure != null) {
            return new Rates(null, null, failure);
        }
        String joined = String.join("", words(baseModel));
        String vendor = vendorOf(joined);
        List<Meter> matched = matching(stem(joined), vendor);
        if (matched.isEmpty()) {
            // a base model that ends in a date or a version the meters do not spell: its words without the trailing numbers
            List<String> words = new ArrayList<>(words(baseModel));
            while (words.size() > 1 && words.get(words.size() - 1).chars().allMatch(Character::isDigit)) {
                words.remove(words.size() - 1);
            }
            matched = matching(stem(String.join("", words)), vendor);
        }
        if (matched.isEmpty()) {
            return new Rates(null, null, "not in the price list as " + baseModel);
        }
        List<String> currencies = matched.stream().map(Meter::currency).distinct().toList();
        if (currencies.size() > 1) {
            return new Rates(null, null, "no listed rates: the price list states this model in " + currencies);
        }
        Map<String, Double> perMillion = new TreeMap<>();
        Set<String> conflicting = new TreeSet<>();
        for (Meter meter : matched) {
            Double before = perMillion.putIfAbsent(meter.meterName(), meter.perMillion());
            if (before != null && before != meter.perMillion()) {
                conflicting.add(meter.meterName());
            }
        }
        conflicting.forEach(perMillion::remove);
        String note = conflicting.isEmpty() ? null : conflicting.size() + " listed at two rates and left out of the listed rates: " + conflicting;
        List<DiscoveredModel.PriceOption> options = new ArrayList<>();
        for (String type : List.of("Global", "Data Zone", "Regional")) {
            DiscoveredModel.Prices prices = standard(matched, type, currencies.get(0));
            if (prices != null) {
                options.add(new DiscoveredModel.PriceOption(type, prices));
            }
        }
        return new Rates(perMillion.isEmpty() ? null : new DiscoveredModel.ListedRates(perMillion, currencies.get(0)), options.isEmpty() ? null : options, note);
    }

    private List<Meter> matching(String stem, String vendor) {
        return meters.stream().filter(m -> m.model().equals(stem) && (vendor == null || m.productName().toLowerCase(Locale.ROOT).contains(vendor))).toList();
    }

    /** The standard price of one deployment type, or null when the meters state no input rate for it or two rates for one kind. */
    private static DiscoveredModel.Prices standard(List<Meter> matched, String deploymentType, String currency) {
        Map<Kind, Double> rates = new EnumMap<>(Kind.class);
        for (Meter meter : matched) {
            if (meter.line() == null || !meter.line().deploymentType().equals(deploymentType)) {
                continue;
            }
            Double before = rates.putIfAbsent(meter.line().kind(), meter.perMillion());
            if (before != null && before != meter.perMillion()) {
                return null;
            }
        }
        if (!rates.containsKey(Kind.INPUT)) {
            return null;
        }
        return new DiscoveredModel.Prices(rates.get(Kind.INPUT), rates.get(Kind.OUTPUT), rates.get(Kind.CACHE_READ), rates.get(Kind.CACHE_WRITE), currency);
    }
}
