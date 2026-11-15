/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.models.discovery.*;

/**
 * Manual live probe of the Azure price list reader against Microsoft's public endpoint. Not a
 * unit test - it reads the network - so it is a {@code main} that never runs in the suite. It
 * needs no credential. It reads one region's list and prints, for each base model named, how
 * many listed rates the model has, each rate, the standard price of each deployment type, and
 * the note, which is where a model the meters spell in a way the reader does not match shows.
 *
 * <p>Usage: {@code main(region, baseModel...)}, for example
 * {@code eastus2 gpt-5.6-sol gpt-6.1-terra DeepSeek-V4-Pro grok-code-fast-1}. With no
 * argument it runs the whole route instead, under the Azure AI Foundry credential the
 * process is configured with: the resource's deployments, the region its answer names, and
 * that region's rates for each deployment, one line per deployment.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
public class LiveAzurePriceListProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // the whole route under the configured resource key: the listing, the region its answer names, that region's list
            for (DiscoveredModel model : new AzureFoundryOpenAIProvider().listModels()) {
                System.out.println(model.wireModelId() + ": " + model.note() + " | "
                        + (model.listedRates() != null ? model.listedRates().perMillion().size() + " listed rates" : "no listed rates")
                        + (model.priceOptions() != null ? " | " + model.priceOptions().stream().map(DiscoveredModel.PriceOption::name).toList() : ""));
            }
            return;
        }
        AzurePriceList list = AzurePriceList.read(args[0]);
        for (int i = 1; i < args.length; i++) {
            AzurePriceList.Rates rates = list.ratesOf(args[i]);
            System.out.println(args[i] + ": " + (rates.listedRates() != null ? rates.listedRates().perMillion().size() + " listed rates in " + rates.listedRates().currency() : "no listed rates")
                    + (rates.note() != null ? " (" + rates.note() + ")" : ""));
            if (rates.listedRates() != null) {
                rates.listedRates().perMillion().forEach((name, rate) -> System.out.println("    " + name + " = " + rate));
            }
            if (rates.priceOptions() != null) {
                for (DiscoveredModel.PriceOption option : rates.priceOptions()) {
                    System.out.println("  " + option.name() + ": " + option.prices());
                }
            }
        }
    }
}
