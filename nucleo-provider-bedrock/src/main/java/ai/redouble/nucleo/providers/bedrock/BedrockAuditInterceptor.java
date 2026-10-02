/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import software.amazon.awssdk.core.interceptor.*;
import software.amazon.awssdk.core.sync.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Captures Bedrock Converse wire bytes onto the in-flight {@link LLMRequest}, for a
 * deployment's usage recorder to persist as an audit trail.
 *
 * <p>The AWS SDK marshals/unmarshals POJOs internally; without intercepting, the only
 * representation available outside the SDK is {@code toString()}, which is developer
 * format, not JSON. This interceptor reads the marshalled JSON request body after the
 * SDK builds it and the JSON response body as it streams back, and writes both verbatim
 * to {@code LLMRequest.inputJson} / {@code LLMRequest.outputJson}.
 *
 * <p>The current {@code LLMRequest} is passed through AWS's own {@link ExecutionAttributes}
 * via {@link #LLM_REQUEST} on each {@code ConverseRequest}'s
 * {@code AwsRequestOverrideConfiguration}. No thread-local state.
 *
 * <p>Response capture uses the read-and-replace pattern: the interceptor consumes the
 * upstream {@link InputStream} into a buffer, persists the bytes for audit, then returns
 * a fresh {@code ByteArrayInputStream} over the same bytes so the SDK's unmarshalling
 * sees identical content downstream.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-15)
 */
public class BedrockAuditInterceptor implements ExecutionInterceptor {

    /**
     * Per-request attribute carrying the {@link LLMRequest} whose {@code inputJson} /
     * {@code outputJson} this interceptor populates. Callers set it on the
     * {@code AwsRequestOverrideConfiguration} of each {@code ConverseRequest}.
     */
    public static final ExecutionAttribute<LLMRequest<?>> LLM_REQUEST =
            new ExecutionAttribute<>("ai.redouble.nucleo.providers.bedrock.llmRequest");

    @Override
    public void afterMarshalling(Context.AfterMarshalling context, ExecutionAttributes executionAttributes) {
        // The interceptor is registered client-wide. Any Bedrock call that did not originate
        // from BedrockConverseClient.doSingleResponse carries no LLM_REQUEST attribute and is
        // not an LLM exchange we audit - skip it rather than fabricate a target.
        LLMRequest<?> req = executionAttributes.getAttribute(LLM_REQUEST);
        if (req == null) {
            return;
        }
        Optional<RequestBody> body = context.requestBody();
        if (body.isEmpty()) {
            return;
        }
        try (InputStream is = body.get().contentStreamProvider().newStream()) {
            req.setInputJson(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException(
                    "Audit capture failed reading Bedrock request body: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context, ExecutionAttributes executionAttributes) {
        // Non-LLM call (no LLM_REQUEST attribute) or bodiless response: pass the stream
        // through untouched so the SDK's unmarshalling is unaffected.
        LLMRequest<?> req = executionAttributes.getAttribute(LLM_REQUEST);
        Optional<InputStream> source = context.responseBody();
        if (req == null || source.isEmpty()) {
            return source;
        }
        byte[] bytes;
        try (InputStream is = source.get()) {
            bytes = is.readAllBytes();
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException(
                    "Audit capture failed reading Bedrock response body: " + e.getMessage(), e);
        }
        req.setOutputJson(new String(bytes, StandardCharsets.UTF_8));
        return Optional.of(new ByteArrayInputStream(bytes));
    }
}
