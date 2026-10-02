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
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.exception.*;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.http.apache5.*;
import software.amazon.awssdk.http.auth.aws.signer.*;
import software.amazon.awssdk.http.auth.spi.signer.*;
import software.amazon.awssdk.regions.*;
import software.amazon.awssdk.services.bedrock.*;
import software.amazon.awssdk.services.bedrock.model.*;
import software.amazon.awssdk.services.servicequotas.*;
import software.amazon.awssdk.services.servicequotas.model.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * What the AWS account can reach on Bedrock, from the two surfaces that expose it.
 *
 * <p>The runtime surface ({@link #runtimeModels()}): the control plane's foundation models
 * (id, modalities, lifecycle) and inference profiles (the {@code us.} / {@code global.} ids
 * the runtime actually sends), in the configured region, with each entry's tokens- and
 * requests-per-minute taken from Service Quotas, because Bedrock sends no rate-limit headers
 * on a response. A quota is matched to a model by the name AWS gives it: the profile prefix
 * picks the quota family (global cross-region, cross-region, on-demand) and the provider and
 * model names complete it; a name AWS spells differently from the model's own (a few Mistral
 * and OpenAI entries) matches nothing and the entry keeps its seed limit, which the discovery
 * reports.
 *
 * <p>The Mantle surface ({@link #mantleModels()}): {@code GET /v1/models} on the
 * {@code bedrock-mantle} endpoint, signed with SigV4 under the same credentials the runtime
 * signs with. It is the only surface that carries each model's {@code allowed_modes}, which is
 * where {@code requires_lax} comes from: a model whose allowed modes exclude {@code none}
 * cannot run at zero retention, so the runtime must route it through its LAX project.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
public final class BedrockDiscovery {
    private static final Logger log = LoggerFactory.getLogger(BedrockDiscovery.class);
    static final String QUOTA_SERVICE_CODE = "bedrock";
    static final String MANTLE_SIGNING_NAME = "bedrock-mantle";

    private BedrockDiscovery() {}

    /** The region's applied quotas by AWS name, or the reason they could not be read, in which case the map is empty. */
    record Quotas(Map<String, Double> values, String failure) {}

    public static List<DiscoveredModel> runtimeModels() {
        Region region = BedrockClients.region();
        AwsCredentialsProvider credentials = BedrockClients.credentialsProvider();
        Quotas quotas = quotas(region, credentials);
        List<DiscoveredModel> models = new ArrayList<>();
        try (BedrockClient bedrock = BedrockClient.builder().region(region).credentialsProvider(credentials)
                .httpClientBuilder(Apache5HttpClient.builder()).build()) {
            Map<String, FoundationModelSummary> byModelId = new HashMap<>();
            for (FoundationModelSummary summary : bedrock.listFoundationModels(r -> {}).modelSummaries()) {
                byModelId.put(summary.modelId(), summary);
                if (!onDemand(summary)) {
                    // the bare id of a model served only through provisioned throughput or an
                    // inference profile: a call on it is refused, so it is not what the account
                    // can reach - its profile, listed below, is
                    log.debug("{} is not invokable on demand ({}); its profile is listed in its place if the region has one",
                            summary.modelId(), summary.inferenceTypesSupportedAsStrings());
                    continue;
                }
                models.add(discovered(summary.modelId(), summary, quotas, true));
            }
            for (InferenceProfileSummary profile : bedrock.listInferenceProfilesPaginator(r -> {}).inferenceProfileSummaries()) {
                FoundationModelSummary underlying = null;
                for (InferenceProfileModel member : profile.models()) {
                    String arn = member.modelArn();
                    if (arn != null) {
                        underlying = byModelId.get(arn.substring(arn.lastIndexOf('/') + 1));
                        if (underlying != null) {
                            break;
                        }
                    }
                }
                if (underlying == null) {
                    // a profile whose model the region does not serve cannot be invoked here:
                    // not a model the account can reach, so not listed
                    log.debug("Inference profile {} has no foundation model behind it in {}; skipped", profile.inferenceProfileId(), region.id());
                    continue;
                }
                models.add(discovered(profile.inferenceProfileId(), underlying, quotas, true));
            }
        }
        return models;
    }

    /** The inference type under which a listed id answers a plain invocation. */
    static final String ON_DEMAND = "ON_DEMAND";

    /**
     * Whether a foundation id answers a plain invocation. AWS lists every model's inference
     * types; an id that offers only provisioned throughput, or only an inference profile, refuses
     * the call the runtime would make on it, and the profile the region lists for it is what the
     * account actually reaches.
     */
    static boolean onDemand(FoundationModelSummary summary) {
        return summary.inferenceTypesSupportedAsStrings() != null && summary.inferenceTypesSupportedAsStrings().contains(ON_DEMAND);
    }

    /**
     * A listed id with the facts the control plane states about its model. The modalities are
     * taken as strings, because the SDK's enum knows only text, image and embedding and maps a
     * video or speech modality to null, which would make a speech model read as one that takes
     * nothing.
     */
    static DiscoveredModel discovered(String wireId, FoundationModelSummary summary, Quotas quotas, boolean invokable) {
        if (summary == null) {
            return new DiscoveredModel(wireId, null, null, null, invokable, null, null, null, null,
                    "a profile with no foundation model behind it in this region");
        }
        List<String> inputs = summary.hasInputModalities() ? summary.inputModalitiesAsStrings() : null;
        List<String> outputs = summary.hasOutputModalities() ? summary.outputModalitiesAsStrings() : null;
        Boolean vision = inputs != null ? inputs.contains(ModelModality.IMAGE.toString()) : null;
        Boolean retired = summary.modelLifecycle() != null && summary.modelLifecycle().status() != null
                ? summary.modelLifecycle().status() == FoundationModelLifecycleStatus.LEGACY
                : null;
        Integer tpm = quota(quotas.values(), wireId, "tokens", summary);
        Integer rpm = quota(quotas.values(), wireId, "requests", summary);
        String note = summary.providerName() + " " + summary.modelName()
                + (summary.modelLifecycle() != null && summary.modelLifecycle().status() != null ? " [" + summary.modelLifecycle().statusAsString() + "]" : "")
                + (quotas.failure() != null ? " (quotas unreadable: " + quotas.failure() + ")" : tpm == null ? " (no quota matched)" : "");
        return new DiscoveredModel(wireId, inputs, outputs, vision, invokable, retired, null, tpm, rpm, note);
    }

    /** The region's quota table, held briefly: three Bedrock surfaces read the same table in one discovery run, and it takes ~25s to page through. */
    private static final Map<String, Quotas> QUOTA_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> QUOTA_CACHE_AT = new ConcurrentHashMap<>();
    private static final Duration QUOTA_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Every applied Bedrock quota in the region, by its AWS name. Quotas are the listing's
     * enrichment, not its subject: an identity allowed to invoke models but not to read Service
     * Quotas (a scoped demo key, a customer's minimal role) still gets its listing, and every
     * entry it yields says, verbatim, why it carries the seed limit instead of the account's.
     * The table is cached for a few minutes: one discovery run asks for it once per Bedrock
     * surface, it takes ~25 seconds to page through, and applied quotas do not move mid-run.
     */
    static Quotas quotas(Region region, AwsCredentialsProvider credentials) {
        Quotas cached = QUOTA_CACHE.get(region.id());
        Long at = QUOTA_CACHE_AT.get(region.id());
        if (cached != null && at != null && System.currentTimeMillis() - at < QUOTA_CACHE_TTL.toMillis()) {
            return cached;
        }
        Quotas fresh = readQuotas(region, credentials);
        // a failed read is not cached: the next surface may hold the permission this one lacked
        if (fresh.failure() == null) {
            QUOTA_CACHE.put(region.id(), fresh);
            QUOTA_CACHE_AT.put(region.id(), System.currentTimeMillis());
        }
        return fresh;
    }

    private static Quotas readQuotas(Region region, AwsCredentialsProvider credentials) {
        Map<String, Double> quotas = new HashMap<>();
        try (ServiceQuotasClient client = ServiceQuotasClient.builder().region(region).credentialsProvider(credentials)
                .httpClientBuilder(Apache5HttpClient.builder()).build()) {
            for (ServiceQuota quota : client.listServiceQuotasPaginator(r -> r.serviceCode(QUOTA_SERVICE_CODE)).quotas()) {
                if (quota.value() != null) {
                    quotas.put(quota.quotaName(), quota.value());
                }
            }
        }
        catch (AwsServiceException e) {
            String failure = e.getClass().getSimpleName() + ": " + e.awsErrorDetails().errorMessage();
            log.warn("Bedrock quotas in {} not readable, every listed entry keeps its seed limit: {}", region.id(), failure);
            return new Quotas(Map.of(), failure);
        }
        // a bookkeeping fact, interesting only when debugging a limit that did not match;
        // the user-facing story is the listing count and each entry's matched tpm/rpm
        log.debug("Read the Bedrock quota table in {}: {} records (per-model TPM/RPM and service limits);"
                + " entries take their limits from name matches against it", region.id(), quotas.size());
        return new Quotas(quotas, null);
    }

    /**
     * The per-minute quota for a wire id, by the name AWS gives it. The prefix of the wire id
     * picks the family; the model's provider and display name complete the name, with and
     * without the " V1" suffix AWS appends to some. Null when nothing matches, which the caller
     * reports rather than guesses.
     */
    static Integer quota(Map<String, Double> quotas, String wireId, String kind, FoundationModelSummary summary) {
        // the geography set is the one authority on what a profile prefix is: a vendor
        // prefix of the same length (meta., luma.) is an on-demand id, not a profile
        String prefix = ModelLineage.prefix(wireId);
        String family;
        if (ModelLineage.GLOBAL.equals(prefix)) {
            family = "Global cross-region model inference " + kind + " per minute for ";
        }
        else if (prefix != null) {
            family = "Cross-region model inference " + kind + " per minute for ";
        }
        else {
            family = "On-demand model inference " + kind + " per minute for ";
        }
        List<String> names = List.of(
                summary.providerName() + " " + summary.modelName(),
                summary.providerName() + " " + summary.modelName() + " V1",
                summary.modelName(),
                summary.modelName() + " V1");
        for (Map.Entry<String, Double> quota : quotas.entrySet()) {
            for (String name : names) {
                if (quota.getKey().equalsIgnoreCase(family + name)) {
                    return quota.getValue().intValue();
                }
            }
        }
        return null;
    }

    public static List<DiscoveredModel> mantleModels() throws IOException {
        Region region = BedrockClients.region();
        URI uri = URI.create("https://bedrock-mantle." + region.id() + ".api.aws/v1/models");
        SdkHttpFullRequest request = SdkHttpFullRequest.builder().method(SdkHttpMethod.GET).uri(uri)
                .putHeader("Accept", "application/json").build();
        AwsCredentials identity = BedrockClients.credentialsProvider().resolveCredentials();
        SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r.identity(identity).request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, MANTLE_SIGNING_NAME)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region.id()));
        try (SdkHttpClient http = Apache5HttpClient.builder().build()) {
            HttpExecuteResponse response = http.prepareRequest(HttpExecuteRequest.builder().request(signed.request()).build()).call();
            String body;
            try (InputStream is = response.responseBody().orElseThrow(() -> new IOException("Mantle listing returned no body"))) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!response.httpResponse().isSuccessful()) {
                throw new IOException("Mantle listing " + uri + " answered " + response.httpResponse().statusCode() + ": " + body);
            }
            return parseMantle(body);
        }
    }

    /** The Mantle listing's shape, as a pure function so it tests on a recorded body. */
    static List<DiscoveredModel> parseMantle(String body) throws IOException {
        JsonNode data = NucleoJsonSerializer.readTree(body).get("data");
        if (data == null || !data.isArray()) {
            throw new IOException("Mantle listing carried no 'data' array: " + body);
        }
        List<DiscoveredModel> models = new ArrayList<>();
        for (JsonNode model : data) {
            Boolean requiresLax = null;
            String note = null;
            JsonNode retention = model.get("data_retention");
            if (retention != null && retention.has("allowed_modes") && retention.get("allowed_modes").isArray()) {
                Set<String> modes = new HashSet<>();
                for (JsonNode mode : retention.get("allowed_modes")) {
                    modes.add(mode.asText());
                }
                // The flag answers the runtime's question - can this model run at zero retention,
                // the STRICT project's mode - and not whether data sharing is its only mode: a model
                // allowed aws_review and provider_data_share but not none still cannot ride STRICT
                requiresLax = !modes.contains("none");
                note = "allowed_modes " + modes;
            }
            models.add(new DiscoveredModel(model.get("id").asText(), null, null, null, null, null, requiresLax, null, null, note));
        }
        return models;
    }
}
