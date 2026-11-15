/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.models.discovery.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Microsoft's retail price list read on meters it answered on 2026-11-13 and 2026-11-14,
 * each with the name, product, unit and rate it carried, laid out as one region's page: a
 * deployment's base model finds its meters whatever spelling the meter gives the model and
 * never those of a model with a similar name; every matched meter priced per token is a
 * listed rate under its own name, per million; the standard price of each deployment type is
 * read from the meters whose words are all known; and a model with no meter, a list that
 * could not be read and a resource that named no region each say so.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
class AzurePriceListTest {

    static String recorded() {
        try (InputStream in = AzurePriceListTest.class.getResourceAsStream("/price-list/FoundryModels.json")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static AzurePriceList list() throws IOException {
        return AzurePriceList.parse(List.of(recorded()));
    }

    private static DiscoveredModel.PriceOption option(AzurePriceList.Rates rates, String name) {
        return rates.priceOptions().stream().filter(o -> o.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    void everyMeterOfAModelPricedPerTokenIsAListedRateUnderItsOwnName() throws IOException {
        DiscoveredModel.ListedRates terra = list().ratesOf("gpt-5.6-terra").listedRates();
        assertEquals("USD", terra.currency());
        assertEquals(List.of(
                "5.6 terra LongCo Cd Wr Std DZ 1M Tokens", "5.6 terra LongCo Cd Wr Std Gl 1M Tokens", "5.6 terra LongCo Inp PP Gl 1M Tokens",
                "5.6 terra LongCo Opt PP DZ 1M Tokens", "5.6 terra ShortCo Cd Inp PP Gl 1M Tokens", "5.6 terra ShortCo Cd Inp Std DZ 1M Tokens",
                "5.6 terra ShortCo Cd Wr PP Gl 1M Tokens", "5.6 terra ShortCo Inp PP Gl 1M Tokens", "5.6 terra ShortCo Inp Std DZ 1M Tokens",
                "5.6 terra ShortCo Inp Std Gl 1M Tokens", "5.6 terra ShortCo Opt PP Gl 1M Tokens"),
                new ArrayList<>(terra.perMillion().keySet()), "the eleven meters of the model, ordered by name, both deployment types side by side");
        assertEquals(2.0, terra.perMillion().get("5.6 terra ShortCo Inp Std Gl 1M Tokens"));
        assertEquals(2.2, terra.perMillion().get("5.6 terra ShortCo Inp Std DZ 1M Tokens"), "the Data Zone rate beside the Global one");
        assertEquals(8.0, terra.perMillion().get("5.6 terra LongCo Inp PP Gl 1M Tokens"), "a long-context priority rate is kept as it is written");
    }

    @Test
    void aRatePerThousandIsCarriedPerMillion() throws IOException {
        DiscoveredModel.ListedRates codex = list().ratesOf("gpt-5-codex").listedRates();
        assertEquals(1.25, codex.perMillion().get("gpt-5-codex-inp-glbl Tokens"), 1e-9, "stated as 0.00125 per thousand");
        assertEquals(0.138, codex.perMillion().get("gpt-5-codex-ccchd-inp-dzone Tokens"), 1e-9);
        assertEquals(6, codex.perMillion().size());
    }

    /**
     * A meter names its model in whatever spelling it was entered with, and the base model
     * finds it: the version without its dot, the vendor word present on one side only, a
     * reseller's mark in front, a date the meters do not spell.
     */
    @Test
    void aBaseModelFindsItsMetersWhateverSpellingTheyGiveIt() throws IOException {
        AzurePriceList list = list();
        assertEquals(Set.of("61 terra ShortCo Inp Std DZ 1M Tokens", "61 terra ShortCo Inp Std Gl 1M Tokens", "61 terra ShortCo Opt std Gl 1M Tokens"),
                list.ratesOf("gpt-6.1-terra").listedRates().perMillion().keySet(), "6.1 written as 61");
        assertEquals(3, list.ratesOf("DeepSeek-V4-Pro").listedRates().perMillion().size(), "the reseller's FW in front of the name");
        assertEquals(1.65, list.ratesOf("grok-code-fast-1").listedRates().perMillion().get("Code Fast 1 Outp DZone Tokens"), 1e-9, "the vendor word left out of the meter");
        assertEquals(6, list.ratesOf("grok-4.7").listedRates().perMillion().size(), "a meter that is the version alone");
        assertEquals(Set.of("Command A Plus Inp Glbl 1M Tokens", "Command A Plus Outp DZ 1M Tokens"),
                list.ratesOf("Cohere-command-a-plus-05-2026").listedRates().perMillion().keySet(), "the base model's date is not in the meters");
    }

    /** A model takes no meter of a model whose name it shares a part of, and a version of one vendor is not that version of another. */
    @Test
    void aModelNeverTakesTheMetersOfAModelWithASimilarName() throws IOException {
        AzurePriceList list = list();
        assertEquals(Set.of("6-sol ShortCo Opt Std DZ 1M Tokens", "6-sol ShortCo Opt Std Gl 1M Tokens"), list.ratesOf("gpt-6-sol").listedRates().perMillion().keySet(),
                "gpt-6-sol takes no meter of 6.1-sol");
        assertEquals(Set.of("6.1-sol LongCo Opt Std Gl 1M Tokens", "6.1-sol ShortCo Cd Wr Std DZ 1M Tokens"), list.ratesOf("gpt-6.1-sol").listedRates().perMillion().keySet());
        assertEquals(Set.of("Command A Inp Glbl Tokens", "Command A Outp Glbl Tokens"), list.ratesOf("Cohere-command-a").listedRates().perMillion().keySet(),
                "Command A takes no meter of Command A Plus");
        AzurePriceList.Rates otherVendor = list.ratesOf("gpt-4.7");
        assertNull(otherVendor.listedRates(), "4.7 of Grok is not 4.7 of GPT: the meter's product does not name the vendor");
        assertEquals("not in the price list as gpt-4.7", otherVendor.note());
        assertNull(list.ratesOf("gpt-5").listedRates(), "a base model whose name starts another's takes none of its meters");
    }

    /**
     * The standard price of each deployment type is read from the meters whose words are all
     * known, and offered as an option: the listing does not say which type a deployment is.
     * A long-context, priority or flex meter is no one's standard price.
     */
    @Test
    void theStandardPriceOfEachDeploymentTypeIsAnOption() throws IOException {
        AzurePriceList.Rates codex = list().ratesOf("gpt-5-codex");
        assertEquals(List.of("Global", "Data Zone"), codex.priceOptions().stream().map(DiscoveredModel.PriceOption::name).toList());
        DiscoveredModel.Prices global = option(codex, "Global").prices();
        assertEquals(1.25, global.inputPerMillion(), 1e-9);
        assertEquals(10.0, global.outputPerMillion(), 1e-9);
        assertEquals(0.125, global.cacheReadPerMillion(), 1e-9);
        assertNull(global.cacheWritePerMillion(), "the list states no cache write for it");
        DiscoveredModel.Prices dataZone = option(codex, "Data Zone").prices();
        assertEquals(1.375, dataZone.inputPerMillion(), 1e-9);
        assertEquals(11.0, dataZone.outputPerMillion(), 1e-9);
        AzurePriceList.Rates deepseek = list().ratesOf("DeepSeek-V4-Pro");
        assertEquals(1.925, option(deepseek, "Data Zone").prices().inputPerMillion(), 1e-9);
        assertEquals(3.828, option(deepseek, "Data Zone").prices().outputPerMillion(), 1e-9);
        assertEquals(0.165, option(deepseek, "Data Zone").prices().cacheReadPerMillion(), 1e-9, "Ch Inp is the cached input");
        AzurePriceList.Rates flash = list().ratesOf("DeepSeek-V4-Flash");
        assertEquals(0.15, option(flash, "Data Zone").prices().inputPerMillion(), 1e-9, "In is the input, as Inp is");
        assertEquals(0.03, option(flash, "Data Zone").prices().cacheReadPerMillion(), 1e-9);
        assertEquals(0.31, option(flash, "Data Zone").prices().outputPerMillion(), 1e-9);
        AzurePriceList.Rates terra = list().ratesOf("gpt-5.6-terra");
        assertEquals(2.0, option(terra, "Global").prices().inputPerMillion(), "the priority rate of 4.0 is not the standard input");
        assertNull(option(terra, "Global").prices().cacheWritePerMillion(), "the long-context cache write of 5.0 is not the standard one");
        assertNull(option(terra, "Global").prices().outputPerMillion(), "the priority output of 24.0 is not the standard output");
        AzurePriceList.Rates grok = list().ratesOf("grok-4.7");
        assertEquals(2.0, option(grok, "Global").prices().inputPerMillion(), "the L meter at 4.0 is the long-context rate");
        assertNull(option(grok, "Global").prices().outputPerMillion(), "the only Global output meter is the long-context one");
        assertEquals(6.6, option(grok, "Data Zone").prices().outputPerMillion());
        AzurePriceList.Rates sol = list().ratesOf("gpt-5.6-sol");
        assertEquals(List.of("Data Zone"), sol.priceOptions().stream().map(DiscoveredModel.PriceOption::name).toList(),
                "a deployment type with no standard input rate is no option");
    }

    @Test
    void aDimensionIsAStandardLineOnlyWhenEveryWordOfItIsKnown() {
        assertEquals(new AzurePriceList.Line(AzurePriceList.Kind.INPUT, "Global"), AzurePriceList.analyze(List.of("shortco", "inp", "std", "gl", "1m", "tokens")));
        assertEquals(new AzurePriceList.Line(AzurePriceList.Kind.CACHE_WRITE, "Data Zone"), AzurePriceList.analyze(List.of("shortco", "cd", "wr", "std", "dz", "1m", "tokens")));
        assertEquals(new AzurePriceList.Line(AzurePriceList.Kind.CACHE_READ, "Data Zone"), AzurePriceList.analyze(List.of("ccchd", "inp", "dzone", "tokens")));
        assertEquals(new AzurePriceList.Line(AzurePriceList.Kind.OUTPUT, "Regional"), AzurePriceList.analyze(List.of("outp", "regnl", "tokens")));
        for (List<String> variant : List.of(
                List.of("longco", "inp", "std", "gl", "1m", "tokens"),
                List.of("shortco", "inp", "pp", "gl", "1m", "tokens"),
                List.of("shortco", "opt", "flex", "gl", "1m", "tokens"),
                List.of("inp", "glbl", "l", "1m", "tokens"),
                List.of("inp", "glbl", "moonphase", "tokens"),
                List.of("inp", "tokens"),
                List.of("img", "glbl", "tokens"))) {
            assertNull(AzurePriceList.analyze(variant), variant + " is not a standard line");
        }
    }

    /** A meter of another unit is not a rate per token, and a meter that is not consumption pricing is not a retail rate. */
    @Test
    void aMeterNotPricedPerTokenIsNoListedRate() throws IOException {
        assertNull(list().ratesOf("Cohere-rerank-v4-pro").listedRates(), "a meter per thousand searches is not a rate per token");
        String page = """
                { "Items": [
                  { "currencyCode": "USD", "retailPrice": 1.0, "meterName": "Solo Inp Glbl 1M Tokens", "productName": "P", "unitOfMeasure": "1M", "type": "Consumption" },
                  { "currencyCode": "USD", "retailPrice": 0.5, "meterName": "Solo Inp Glbl Res 1M Tokens", "productName": "P", "unitOfMeasure": "1M", "type": "Reservation" },
                  { "currencyCode": "USD", "retailPrice": 9.0, "meterName": "Solo Provisioned Unit", "productName": "P", "unitOfMeasure": "1/Hour", "type": "Consumption" } ] }
                """;
        assertEquals(Set.of("Solo Inp Glbl 1M Tokens"), AzurePriceList.parse(List.of(page)).ratesOf("solo").listedRates().perMillion().keySet());
    }

    /** One name holds one rate: a meter name the pages state at two rates is left out of the listed rates, and the note counts it. */
    @Test
    void aMeterNameAtTwoRatesIsLeftOutAndTheNoteSaysSo() throws IOException {
        String first = """
                { "Items": [
                  { "currencyCode": "USD", "retailPrice": 2.2, "meterName": "Twin Inp DZ 1M Tokens", "productName": "P", "unitOfMeasure": "1M", "type": "Consumption" },
                  { "currencyCode": "USD", "retailPrice": 2.0, "meterName": "Twin Inp Gl 1M Tokens", "productName": "P", "unitOfMeasure": "1M", "type": "Consumption" } ] }
                """;
        String second = """
                { "Items": [
                  { "currencyCode": "USD", "retailPrice": 2.75, "meterName": "Twin Inp DZ 1M Tokens", "productName": "P", "unitOfMeasure": "1M", "type": "Consumption" } ] }
                """;
        AzurePriceList.Rates twin = AzurePriceList.parse(List.of(first, second)).ratesOf("twin");
        assertEquals(Set.of("Twin Inp Gl 1M Tokens"), twin.listedRates().perMillion().keySet(), "every page is read, and the name stated twice is left out");
        assertEquals("1 listed at two rates and left out of the listed rates: [Twin Inp DZ 1M Tokens]", twin.note());
        assertEquals(List.of("Global"), twin.priceOptions().stream().map(DiscoveredModel.PriceOption::name).toList(), "two rates for one kind are no option either");
    }

    @Test
    void aListThatCouldNotBeReadAndAResourceThatNamedNoRegionSaySo() {
        AzurePriceList.Rates unreadable = AzurePriceList.unreadable("prices.example answered 503").ratesOf("gpt-5.6-terra");
        assertNull(unreadable.listedRates());
        assertNull(unreadable.priceOptions());
        assertEquals("price list unreadable: prices.example answered 503", unreadable.note());
        assertEquals("no listed rates: the resource's listing did not state its region", AzurePriceList.regionUnknown().ratesOf("gpt-5.6-terra").note());
    }

    @Test
    void aPageWithoutItemsIsRefused() {
        assertThrows(IOException.class, () -> AzurePriceList.parse(List.of("{ \"error\": \"bad filter\" }")));
    }
}
