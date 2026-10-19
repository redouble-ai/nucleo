/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.models.discovery.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The price list read on recorded excerpts of the two files AWS published for us-east-1 in
 * October 2026, both grammars of a billing line included: a listed id takes the standard
 * on-demand rate of its profile and its surface, per million whatever the file's unit; every
 * variant the runtime does not buy is left out; two rates for one kind price nothing; and a
 * list that cannot be read, or has no row for the id, says so.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-19)
 */
class BedrockPriceListTest {

    /** The recorded excerpt of a service's file, by its service code. */
    static String recorded(String service) {
        try (InputStream in = BedrockPriceListTest.class.getResourceAsStream("/price-list/" + service + ".json")) {
            return new String(Objects.requireNonNull(in, service).readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BedrockPriceList both() throws IOException {
        return BedrockPriceList.parse(List.of(recorded("AmazonBedrockFoundationModels"), recorded("AmazonBedrock")));
    }

    @Test
    void aBillingLineIsReadFromItsWordsInEitherGrammar() {
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.INPUT, true, false), BedrockPriceList.analyze("USE1-MP:USE1_InputTokenCount_Global-Units"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.CACHE_WRITE, false, false), BedrockPriceList.analyze("USE1-MP:USE1_CacheWriteInputTokenCount-Units"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.CACHE_READ, true, false), BedrockPriceList.analyze("EUW1-MP:EUW1_cache_read_tokens_global_standard-Units"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.INPUT, true, true), BedrockPriceList.analyze("USE1-xai.grok-4.6-mantle-input-tokens-global-standard"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.OUTPUT, true, false), BedrockPriceList.analyze("USE1-Nova2.0Pro-text-output-tokens-cross-region-global"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.INPUT, false, false), BedrockPriceList.analyze("USE1-Gemma-3-12B-IT-input-tokens"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.INPUT, false, false), BedrockPriceList.analyze("USE1-Claude2.1-input-tokens"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.INPUT, false, false), BedrockPriceList.analyze("USE1-NovaSonic2.0-text-input-tokens"));
        assertEquals(new BedrockPriceList.Line(BedrockPriceList.Kind.OUTPUT, true, false), BedrockPriceList.analyze("USE1-MP:USE1_outputTokenCount_Global-Units"));
    }

    @Test
    void aVariantTheRuntimeDoesNotBuyIsNoLine() {
        for (String variant : List.of(
                "USE1-MP:USE1_cache_write_tokens_1h_standard-Units",
                "USE1-MP:USE1_CacheWrite1hInputTokenCount-Units",
                "USE1-MP:USE1_input_tokens_long_ctx_standard-Units",
                "USE1-MP:USE1_InputTokenCount_Batch-Units",
                "USE1-MP:USE1_InputTokenCount_LatencyOptimized-Units",
                "USE1-MP:USE1_Reserved_1Month_InputTPM_Global-Units",
                "USE1-Nova2.0Pro-text-input-tokens-flex",
                "USE1-Nova2.0Pro-input-video-token-count",
                "USE1-xai.grok-4.6-mantle-output-tokens-priority",
                "USE1-deepseek.v3.1-mantle-cache-write-tokens-30m-standard",
                "USE1-Nova2.0Pro-input-tokens-custom-model",
                "USE1-MP:USE1_Reserved_1Month_OutputTPM_Geo-Units",
                "USE1-MP:USE1_MillionBatchInputTokens-Units",
                "USE1-MP:USE1_inputAudioSecond-Units",
                "USE1-NovaSonic2.0-speech-input-tokens",
                "USE1-TitanImageGenerator-standard-image-count")) {
            assertNull(BedrockPriceList.analyze(variant), variant);
        }
    }

    @Test
    void aProfileTakesItsOwnRateAndTheVariantsAreLeftOut() throws IOException {
        BedrockPriceList list = both();
        DiscoveredModel.Prices regional = list.pricesOf("us.anthropic.claude-sonnet-4-5-20250929-v1:0", "Anthropic", "Claude Sonnet 4.5", false).prices();
        assertEquals(3.3, regional.inputPerMillion());
        assertEquals(16.5, regional.outputPerMillion());
        assertEquals(0.33, regional.cacheReadPerMillion());
        assertEquals(4.125, regional.cacheWritePerMillion(), "the five-minute cache write, not the hour's 6.6");
        assertEquals("USD", regional.currency());
        DiscoveredModel.Prices global = list.pricesOf("global.anthropic.claude-sonnet-4-5-20250929-v1:0", "Anthropic", "Claude Sonnet 4.5", false).prices();
        assertEquals(3.0, global.inputPerMillion());
        assertEquals(15.0, global.outputPerMillion());
        assertEquals(0.3, global.cacheReadPerMillion());
        assertEquals(3.75, global.cacheWritePerMillion());
        DiscoveredModel.Prices bare = list.pricesOf("anthropic.claude-sonnet-4-5-20250929-v1:0", "Anthropic", "Claude Sonnet 4.5", false).prices();
        assertEquals(3.3, bare.inputPerMillion(), "a bare id is billed at the regional rate");
    }

    @Test
    void theNewerGrammarReadsTheSameWay() throws IOException {
        DiscoveredModel.Prices haiku = both().pricesOf("us.anthropic.claude-haiku-5-5", "Anthropic", "Claude Haiku 5.5", false).prices();
        assertEquals(0.11, haiku.inputPerMillion(), "the standard rate, not the long-context 0.55");
        assertEquals(0.55, haiku.outputPerMillion());
        assertEquals(0.011, haiku.cacheReadPerMillion());
        assertEquals(0.1375, haiku.cacheWritePerMillion(), "the five-minute cache write, not the hour's 0.22");
    }

    @Test
    void aRatePerThousandIsCarriedPerMillion() throws IOException {
        BedrockPriceList list = both();
        BedrockPriceList.Pricing nova = list.pricesOf("us.amazon.nova-2-pro-v1:0", "Amazon", "Nova 2.0 Pro", false);
        assertNull(nova.note(), "the file states no provider for Nova, so the model name alone finds it");
        assertEquals(1.375, nova.prices().inputPerMillion(), 1e-9);
        assertEquals(11.0, nova.prices().outputPerMillion(), 1e-9);
        assertNull(nova.prices().cacheReadPerMillion(), "no cache row, no cache rate");
        DiscoveredModel.Prices global = list.pricesOf("global.amazon.nova-2-pro-v1:0", "Amazon", "Nova 2.0 Pro", false).prices();
        assertEquals(1.25, global.inputPerMillion(), 1e-9);
        assertEquals(10.0, global.outputPerMillion(), 1e-9);
    }

    @Test
    void aMantleIdTakesTheMantleRowsAndARuntimeIdTheOthers() throws IOException {
        BedrockPriceList list = both();
        DiscoveredModel.Prices grok = list.pricesOf("xai.grok-4.6", null, null, true).prices();
        assertEquals(2.2, grok.inputPerMillion(), 1e-9, "found by the raw id the file spells, no names needed");
        assertEquals(6.6, grok.outputPerMillion(), 1e-9);
        assertEquals(0.55, grok.cacheReadPerMillion(), 1e-9);
        assertNull(grok.cacheWritePerMillion());
        // Gemma is priced on both surfaces under one name; the surface asked for picks the rows
        DiscoveredModel.Prices converse = list.pricesOf("google.gemma-3-12b-it", "Google", "Gemma 3 12B", false).prices();
        assertEquals(0.09, converse.inputPerMillion(), 1e-9);
        assertEquals(0.29, converse.outputPerMillion(), 1e-9);
        // and a surface priced apart at another rate is told apart
        String apart = """
                { "products": {
                    "A": { "sku": "A", "attributes": { "usagetype": "USE1-Dual-input-tokens", "model": "Dual", "provider": "V" } },
                    "B": { "sku": "B", "attributes": { "usagetype": "USE1-v.dual-mantle-input-tokens-standard", "model": "Dual", "provider": "V" } } },
                  "terms": { "OnDemand": {
                    "A": { "A.1": { "priceDimensions": { "A.1.1": { "unit": "1K tokens", "pricePerUnit": { "USD": "0.001" } } } } },
                    "B": { "B.1": { "priceDimensions": { "B.1.1": { "unit": "1M tokens", "pricePerUnit": { "USD": "2" } } } } } } } }
                """;
        BedrockPriceList dual = BedrockPriceList.parse(List.of(apart));
        assertEquals(1.0, dual.pricesOf("v.dual", "V", "Dual", false).prices().inputPerMillion(), 1e-9);
        assertEquals(2.0, dual.pricesOf("v.dual", "V", "Dual", true).prices().inputPerMillion(), 1e-9);
        assertNull(dual.pricesOf("v.dual", "V", "Dual", false).prices().outputPerMillion(), "an input-only listing carries no output rate");
    }

    @Test
    void twoRatesForOneKindPriceNothingAndSaySo() throws IOException {
        BedrockPriceList.Pricing old = both().pricesOf("anthropic.claude-v2", "Anthropic", "Claude 2.0", false);
        assertNull(old.prices());
        assertTrue(old.note().contains("2 INPUT rows at different rates"), old.note());
        assertTrue(old.note().contains("USE1-MP:USE1_InputTokenCount-Units"), old.note());
    }

    @Test
    void aModelTheListDoesNotPriceAndAListThatCannotBeReadAreToldApart() throws IOException {
        BedrockPriceList.Pricing absent = both().pricesOf("us.meta.llama4-maverick-17b-instruct-v1:0", "Meta", "Llama 4 Maverick 17B Instruct", false);
        assertNull(absent.prices());
        assertEquals("not in the price list as Meta Llama 4 Maverick 17B Instruct", absent.note());
        BedrockPriceList.Pricing unreadable = BedrockPriceList.unreadable("https://pricing.example answered 404").pricesOf("xai.grok-4.6", null, null, true);
        assertNull(unreadable.prices());
        assertEquals("price list unreadable: https://pricing.example answered 404", unreadable.note());
    }

    @Test
    void aFileWithoutProductsIsRefused() {
        assertThrows(IOException.class, () -> BedrockPriceList.parse(List.of("{ \"formatVersion\": \"v1.0\" }")));
    }
}
