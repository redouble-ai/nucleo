/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import com.fasterxml.jackson.databind.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.core5.http.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.http.*;

/**
 * The deployments of an Azure AI Foundry resource, which is what a call to the resource can
 * reach: {@code GET /openai/deployments} under the resource key, on the classic authoring
 * surface, the one data-plane listing that names deployments. The resource's other listing,
 * {@code GET /openai/v1/models}, is its deployable catalog - every model Azure offers on the
 * resource, hundreds, Claude and image and audio models included, each claiming
 * {@code chat_completion} whatever surface serves it - and says nothing about what a person
 * has deployed; read as reach it reports the catalog as callable and a deployment's own name
 * as absent. A deployment is addressed by the id a person gave it, and its {@code model} names
 * the base model behind it, which the listing keeps as the note.
 *
 * <p>One resource holds deployments of every surface, and the caller says which base models
 * its surface serves: a Claude deployment answers the OpenAI surface with
 * {@code 404 Requested API is currently not supported} (a live run, 2026-09-15), because Claude
 * on Foundry is the Anthropic Messages surface, so the OpenAI-surface provider leaves those
 * out and an Anthropic-surface provider would keep only them.
 *
 * <p>The transport is the framework's own, with the resource key as the {@code api-key} header
 * the surface authenticates by; failures are typed by status and carry the upstream body only.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
final class AzureFoundryDeployments extends AbstractApiClient {
    /** The GA authoring version that lists deployments; the surface is versioned by query parameter. */
    static final String PATH = "/openai/deployments?api-version=2022-12-01";
    /** The response header in which an Azure resource names the region that answered, by its display name ({@code Sweden Central}). */
    static final String REGION_HEADER = "x-ms-region";
    private final String host;
    private final String apiKey;

    AzureFoundryDeployments(String host, String apiKey) {
        // whatever spelling the person provided - bare hostname, or the portal's endpoint
        // with scheme and path - addresses the same resource
        this.host = AzureFoundryOpenAIClient.resourceHost(host);
        this.apiKey = apiKey;
    }

    @Override
    protected String getServiceName() {
        return AzureFoundryOpenAIClient.SERVICE_NAME + " deployments at " + host;
    }

    @Override
    protected String getBaseUrl() {
        return "https://" + host;
    }

    @Override
    protected void decorateRequest(HttpUriRequestBase request) {
        request.setHeader("api-key", apiKey);
    }

    /** The region the resource's last answer named in its {@link #REGION_HEADER}, as it wrote it; null when it named none. */
    private volatile String region;

    @Override
    protected void postProcessResponse(int statusCode, Header[] headers, String responseBody) {
        for (Header header : headers) {
            if (REGION_HEADER.equalsIgnoreCase(header.getName())) {
                region = header.getValue();
            }
        }
    }

    /**
     * The resource's deployments whose base model the caller's surface serves, as the
     * discovery reads them, each with the rates Microsoft's retail price list states for its
     * base model in the resource's region. The region is what the resource's answer names in
     * its {@link #REGION_HEADER}; an answer that names none leaves every deployment without
     * rates, and its note says so.
     */
    List<DiscoveredModel> list(Predicate<String> servedHere) throws IOException {
        try {
            String body = getText(PATH, "application/json");
            String named = region;
            return parse(body, servedHere, named != null ? AzurePriceList.read(armRegion(named)) : AzurePriceList.regionUnknown());
        }
        catch (LLMReadableCheckedException e) {
            throw new IOException(e.getLLMMessage(), e);
        }
    }

    /** A region as the price list names it, from the display name a resource answers with: {@code East US 2} is {@code eastus2}. */
    static String armRegion(String displayName) {
        return displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * The listing's shape, as a pure function so it tests on a recorded body: one model per
     * deployment whose {@code status} is {@code succeeded} and whose base model the caller's
     * surface serves, its id the wire id, its base model the note. A deployment still being
     * created or failed is not reachable and is left out. From the price list a deployment
     * takes its base model's listed rates and the standard price of each deployment type,
     * and never a price: the listing does not say which type a deployment is, so which of
     * those prices it is billed at is not known here. What the list could not give is said
     * in the note.
     */
    static List<DiscoveredModel> parse(String body, Predicate<String> servedHere, AzurePriceList priceList) throws IOException {
        JsonNode data = NucleoJsonSerializer.readTree(body).get("data");
        if (data == null || !data.isArray()) {
            throw new IOException("Deployment listing carried no 'data' array: " + body);
        }
        List<DiscoveredModel> models = new ArrayList<>();
        for (JsonNode deployment : data) {
            String status = deployment.has("status") ? deployment.get("status").asText() : null;
            String model = deployment.has("model") ? deployment.get("model").asText() : null;
            if (!"succeeded".equals(status) || model == null || !servedHere.test(model)) {
                continue;
            }
            AzurePriceList.Rates rates = priceList.ratesOf(model);
            models.add(new DiscoveredModel(deployment.get("id").asText(), null, null, null, null, null, null, null, null,
                    "deployment of " + model + (rates.note() != null ? " (" + rates.note() + ")" : ""), null, rates.listedRates(), rates.priceOptions(), null));
        }
        return models;
    }
}
