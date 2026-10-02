/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.secrets.*;
import org.slf4j.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.exception.*;
import software.amazon.awssdk.core.exception.*;
import software.amazon.awssdk.http.apache5.*;
import software.amazon.awssdk.regions.*;
import software.amazon.awssdk.regions.providers.*;
import software.amazon.awssdk.services.bedrockruntime.*;

import java.net.*;

/**
 * Builds the framework's configured {@link BedrockRuntimeClient}: credentials per
 * {@link #credentialsProvider()}, the region per {@link #region()}, SDK retries disabled
 * (the framework's dispatcher owns retry/backoff), and the audit interceptor registered
 * (a no-op on calls without an LLM_REQUEST execution attribute, e.g. embeddings). Shared
 * by every Bedrock-backed client so the auth/transport wiring exists exactly once.
 *
 * <p>Bedrock takes three things from the deployment and nothing implies any of them from the
 * others: an access key id, a secret access key, and a region. An IAM key is account-wide and
 * region-free, and Bedrock is regional: model access, quotas and the listing are all per
 * region, and every call, including one on a cross-region or global inference profile, goes to
 * one region's endpoint. The runtime invents no way of providing them. AWS's own resolution,
 * the default credentials and region chains, reads what every AWS user already has:
 * {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY} and {@code AWS_REGION} in the
 * environment, a profile, or the role the process runs under on ECS, EC2 or EKS. A deployment
 * that keeps its secrets elsewhere holds them in its {@link Secrets} store under
 * {@link #SECRET_ID} and {@link #REGION_ID}, and a record there is used before the chain. Nothing
 * is defaulted: a region the deployment did not choose is a wrong region. A region where AWS
 * serves no Bedrock at all builds endpoint names DNS has never heard of, and the clients fail
 * such a call fast as an unresolvable-endpoint configuration error; a region that serves
 * Bedrock but not this account's models fails on the first call with the provider's own
 * error, which says nothing about regions.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-10)
 */
public final class BedrockClients {
    private static final Logger log = LoggerFactory.getLogger(BedrockClients.class);

    /**
     * The id of the AWS credential pair in a deployment's own secret store: user is the access key
     * id, secret the secret access key. A record is the pair, both halves; a half record is no
     * record, and the chain reads what AWS's own resolution finds.
     */
    public static final String SECRET_ID = "aws-access-key-id";
    /** The id of the region every Bedrock surface calls, in the same store: the secret part carries the region name. */
    public static final String REGION_ID = "aws-region";
    /** The pair as AWS names its two halves in the environment. */
    public static final CredentialShape KEY_PAIR_SHAPE = CredentialShape.userAndSecret(SECRET_ID,
            "AWS_ACCESS_KEY_ID", "the access key id", "AWS_SECRET_ACCESS_KEY", "the secret access key");
    /** The region as AWS names it in the environment. */
    public static final CredentialShape REGION_SHAPE = CredentialShape.secret(REGION_ID, "AWS_REGION",
            "the region every Bedrock call goes to, e.g. us-east-1");

    private BedrockClients() {}

    /**
     * The region every Bedrock surface calls: the store's record under {@link #REGION_ID} when the
     * deployment holds one, else what the AWS default region chain resolves. The chain's own
     * failure propagates, naming what it looked for.
     */
    public static Region region() {
        Credential record = Secrets.configured().find(REGION_ID);
        if (record != null) {
            return Region.of(record.secret());
        }
        return DefaultAwsRegionProviderChain.builder().build().getRegion();
    }

    /**
     * Refines an SDK failure whose cause is an unresolvable Bedrock hostname into the mistake a
     * person can act on: the model is not served in the deployment's region, because the region
     * is what built the hostname. Only the Bedrock clients can say this - other providers'
     * endpoints have nothing to do with regions - so they call this around their SDK calls,
     * and the generic classifier leaves the refined failure alone. Any other failure comes
     * back unchanged.
     */
    public static RuntimeException refineUnknownHost(RuntimeException e, ModelSpec model, Region region) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof UnknownHostException unknownHost) {
                return new UncorrectableRuntimeLLMException((model != null ? model.getId() + " (" + model.getWireModelId() + ")" : "The model")
                        + " is not available in region " + region.id() + ": AWS serves no such endpoint there ("
                        + unknownHost.getMessage() + "). Check the deployment's region against the models it expects to call."
                        + " Not retried: no retry changes DNS.", e);
            }
        }
        return e;
    }

    public static BedrockRuntimeClient newRuntimeClient() {
        Region region = region();
        BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                .credentialsProvider(credentialsProvider())
                .region(region)
                // the SDK's default Apache pool is ~50 connections with a 10s
                // acquire timeout - under the framework's HTTP permit gate a
                // wide fan-out starved it and 40% of calls died with 'Timeout
                // waiting for connection from pool'. The pool matches the
                // gate: the gate is the concurrency governor, this must never
                // be the narrower door. The same holds for time: the SDK's default
                // 30s socket timeout killed every call whose answer took longer to
                // write (a grouping over a whole corpus), so a response gets the
                // window the framework's own HTTP transport gives it.
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(Settings.get(HttpSettings.class).poolSize)
                        .connectionAcquisitionTimeout(java.time.Duration.ofSeconds(60))
                        .connectionTimeout(java.time.Duration.ofSeconds(HttpConnectionPools.CONNECT_TIMEOUT_SECONDS))
                        .socketTimeout(java.time.Duration.ofMinutes(HttpConnectionPools.RESPONSE_TIMEOUT_MINUTES)))
                .overrideConfiguration(c -> c.retryStrategy(s -> s.maxAttempts(1))
                        .addExecutionInterceptor(new BedrockAuditInterceptor()))
                .build();
        log.debug("Initialized BedrockRuntimeClient for region: {} ({})", region.id(), credentialsSource());
        return client;
    }

    /**
     * The credentials every Bedrock surface signs with - the runtime client here, and the
     * Anthropic SDK backends of {@code nucleo-provider-bedrock-anthropic}. One decision point, so a
     * deployment cannot end up authenticating one way for embeddings and another for chat.
     * <p>
     * The store's key pair under {@link #SECRET_ID} when the deployment holds one; otherwise the
     * AWS default provider chain, which resolves the identity the process already has - the
     * standard variables, a profile, or the role it runs under on ECS, EC2 or EKS. A deployment on
     * a role therefore provisions no key anywhere. The chain is handed over as a provider rather
     * than a fixed pair because role credentials expire and the SDK must fetch the next ones.
     */
    public static AwsCredentialsProvider credentialsProvider() {
        Credential aws = storedPair();
        if (aws != null) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(aws.user(), aws.secret()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    /**
     * The store's key pair, or null: a record is the pair, and a half record is no record. In
     * the environment the pair is AWS's own {@code AWS_ACCESS_KEY_ID} and
     * {@code AWS_SECRET_ACCESS_KEY} ({@link #KEY_PAIR_SHAPE}), so a person with the standard
     * variables exported has a record, and one with a profile or a role has none and the
     * chain resolves it.
     */
    private static Credential storedPair() {
        Credential aws = Secrets.configured().find(SECRET_ID);
        return aws != null && aws.user() != null && aws.secret() != null ? aws : null;
    }

    /** How the current credentials were obtained, for the initialization log line. */
    static String credentialsSource() {
        return storedPair() != null ? "stored key " + SECRET_ID : "AWS default credentials chain";
    }

    /**
     * Whether this deployment can call Bedrock: a region and an identity both resolve, from the
     * store or from the AWS chains. A chain's own failure IS the answer, so it is reported as
     * false rather than propagated. No Bedrock call is made.
     */
    public static boolean configured() {
        try {
            region();
            credentialsProvider().resolveCredentials();
            return true;
        }
        catch (SdkClientException e) {
            log.debug("AWS resolved no region or identity: {}", e.getMessage());
            return false;
        }
    }

    /**
     * How a person provides what {@link #configured()} checks, for a refusal that names it: the
     * region and the identity, each as the store describes it by the credential's shape, plus
     * AWS's own resolution, which the runtime hands the SDK when the store holds nothing.
     */
    public static String describeCredentials() {
        Secrets store = Secrets.configured();
        return store.describe(REGION_ID) + " (or AWS's own default region chain: a profile, instance metadata); and "
                + store.describe(SECRET_ID) + " (or AWS's own default credentials chain: a profile, the role the process runs under)";
    }

    /**
     * True when the failure, or any cause beneath it, is an HTTP 429 from the service.
     * Classification is by STATUS CODE, never by message wording - every Bedrock service
     * failure is an {@link AwsServiceException} carrying its code, and provider message
     * phrasings ("too many requests", "too many tokens", ...) vary per model family.
     * The cause-chain walk matters: some callers hand the classifier the raw SDK
     * exception, others an IOException wrapping it.
     */
    static boolean isThrottling(Exception e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof AwsServiceException aws && aws.statusCode() == 429) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the failure, or any cause beneath it, is a transient error: a
     * server-side 5xx, the model-timeout 408 (by status code, same rationale as
     * {@link #isThrottling}), or local transport starvation - the SDK's own
     * connection pool timing out before any HTTP was made. Starvation is
     * transient in exactly the operational sense this classification exists
     * for: the dispatcher's transparent retry backs off with jitter, which
     * self-throttles the fan-out until connections free, instead of failing
     * the job and flooring a unit that would decode fine a second later.
     */
    static boolean isServerError(Exception e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof AwsServiceException aws && (aws.statusCode() >= 500 || aws.statusCode() == 408)) {
                return true;
            }
            // the Apache transport's pool-acquire timeout, matched by class
            // identity (no import: transport internals stay a runtime detail)
            if (c.getClass().getName().equals("org.apache.http.conn.ConnectionPoolTimeoutException")) {
                return true;
            }
        }
        return false;
    }
}
