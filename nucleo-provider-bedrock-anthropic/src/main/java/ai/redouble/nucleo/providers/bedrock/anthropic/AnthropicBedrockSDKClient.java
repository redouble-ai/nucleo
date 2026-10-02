/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.providers.anthropic.*;
import ai.redouble.nucleo.providers.bedrock.*;
import com.anthropic.backends.*;
import com.anthropic.bedrock.backends.*;
import com.anthropic.client.okhttp.*;
import org.slf4j.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.*;

import java.util.function.*;

/**
 * Anthropic Claude API client using AWS Bedrock as the backend.
 * Uses the official Anthropic Java SDK with BedrockBackend.
 *
 * <p>Key differences from direct API ({@link AnthropicSDKClient}):
 * <ul>
 *   <li>Uses AWS credentials instead of Anthropic API key</li>
 *   <li>Model IDs use Bedrock format: {@code anthropic.claude-opus-4-5-20251101-v1:0}</li>
 *   <li>Rate limiting uses AWS throttling (ThrottlingException)</li>
 * </ul>
 *
 * <p>Credentials and region per {@link BedrockClients}: AWS's own resolution (the standard
 * variables, a profile, or the role the process runs under), or the deployment's store records
 * under {@link BedrockClients#SECRET_ID} and {@link BedrockClients#REGION_ID}.
 *
 * @see AnthropicSDKClient
 * @see AnthropicModelResolver#resolve(ModelSpec)
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-02)
 */
public class AnthropicBedrockSDKClient extends AnthropicSDKClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicBedrockSDKClient.class);

    public AnthropicBedrockSDKClient() {
        this(BedrockBackend.builder()
                .awsCredentialsProvider(awsCredentialsProvider())
                .region(bedrockRegion())
                .build());
    }

    /**
     * Shared by the Bedrock endpoint variants. {@link AnthropicBedrockMantleSDKClient} passes a
     * {@link BedrockMantleBackend} here; everything downstream of the backend - throttle
     * classification, model resolution, rate-limit header extraction - is identical between the
     * two surfaces, so it lives once, in this class.
     */
    protected AnthropicBedrockSDKClient(Backend backend) {
        super(true);  // Skip parent's direct API initialization
        try {
            this.client = AnthropicOkHttpClient.builder()
                    .backend(backend)
                    .maxRetries(0)
                    .build();
            log.info("Initialized {} for region: {}, prompt caching {}", getClass().getSimpleName(), bedrockRegion(), (isCachingSupported() ? "enabled" : "disabled"));
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to initialize Bedrock client", e);
        }
    }

    /**
     * AWS credentials for either Bedrock surface, resolved exactly as the runtime client resolves
     * them ({@link BedrockClients#credentialsProvider()}). An unreadable secret surfaces as the
     * store's own unchecked failure: this client cannot be built at all.
     */
    protected static AwsCredentialsProvider awsCredentialsProvider() {
        return BedrockClients.credentialsProvider();
    }

    /** The deployment's Bedrock region, the same one for either surface. */
    protected static Region bedrockRegion() {
        return BedrockClients.region();
    }

    /**
     * The Anthropic call, with a Bedrock reading of one failure the shared client cannot give:
     * an unresolvable endpoint hostname means the model is not served in this deployment's
     * region, because the region built the hostname ({@code bedrock-mantle.<region>.api.aws}).
     */
    @Override
    protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
        try {
            return super.doSingleResponse(request, prepared);
        }
        catch (RuntimeException e) {
            throw BedrockClients.refineUnknownHost(e, model, bedrockRegion());
        }
    }

    @Override
    protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response, PreparedConversation prepared, Consumer<StreamChunk> chunkHandler) {
        try {
            super.doStreamResponse(request, response, prepared, chunkHandler);
        }
        catch (RuntimeException e) {
            throw BedrockClients.refineUnknownHost(e, model, bedrockRegion());
        }
    }

    @Override
    public APIDialect getDialect() {
        return APIDialect.BEDROCK_ANTHROPIC;
    }

    /**
     * Uses Bedrock model ID format.
     */
    @Override
    public void setModel(ModelSpec modelInfo) {
        this.model = modelInfo;  // Set parent's model field directly
        this.anthropicModel = AnthropicModelResolver.resolve(modelInfo);
    }

    /**
     * Bedrock surfaces two different 429 shapes depending on which layer hits
     * the limit. Anthropic's own TPM/RPM check is proxied through Bedrock as
     * {@code com.anthropic.errors.RateLimitException} with message
     * "429: Too many tokens..." - this is the common case, and the parent's
     * message-based detector already catches it. AWS-side throttling appears
     * as {@code ThrottlingException} / {@code ServiceQuotaExceededException}
     * in the cause chain, which we detect here. Failing to catch either of
     * these makes the job terminate hard instead of riding through the
     * dispatcher's transparent retry loop.
     */
    @Override
    protected boolean is429Error(Exception e) {
        if (super.is429Error(e)) {
            return true;
        }
        String className = e.getClass().getSimpleName().toLowerCase();
        if (className.contains("throttling") || className.contains("servicequotaexceeded")) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            String causeClassName = cause.getClass().getSimpleName().toLowerCase();
            if (causeClassName.contains("throttling") || causeClassName.contains("servicequotaexceeded")) {
                return true;
            }
            String causeMsg = cause.getMessage();
            if (causeMsg != null) {
                String lowerCause = causeMsg.toLowerCase();
                if (lowerCause.contains("throttling") || lowerCause.contains("rate exceeded")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    // extractRateLimitInfo intentionally NOT overridden. The parent's
    // implementation walks the exception chain, pulls response headers off
    // AnthropicServiceException (which the BedrockBackend populates with
    // the underlying AWS response headers), and logs whatever is present -
    // Retry-After and x-amzn-errortype / x-amzn-requestid for Bedrock
    // throttling, or the anthropic-ratelimit-* family for direct Anthropic.
    // One code path handles both.
}
