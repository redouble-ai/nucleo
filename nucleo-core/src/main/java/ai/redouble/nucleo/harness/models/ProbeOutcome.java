/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.time.*;
import java.util.*;

/**
 * One availability observation of one catalog spec: what a probe ping saw, in full.
 * Universal facts ride typed fields; everything else the provider's response envelope
 * carried (rate-limit headers, request ids, anything future) rides {@link #getHeaders()}
 * verbatim, so no provider fact is dropped because another provider lacks it.
 *
 * <p>{@link Classification} separates "the model is down" from "the model is busy" and
 * "our key is wrong": only {@link Classification#AVAILABILITY} failures count toward a
 * picker's turnoff policy - a 429 or a rotated key must never silently remove a healthy
 * model. An {@link Status#EXCLUDED} outcome records a compliance-envelope refusal and is
 * neither a success nor a failure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public class ProbeOutcome {

    public enum Status {OK, FAILED, EXCLUDED}

    /** What kind of failure this is; null unless {@link #getStatus()} is FAILED. */
    public enum Classification {AVAILABILITY, THROTTLE, AUTH, OTHER}

    private String specId;
    private String provider;
    private Status status;
    private Classification classification;
    private Instant probedAt;
    private Long latencyMs;
    private String servedModelId;
    private String providerRequestId;
    private Long inputTokens;
    private Long outputTokens;
    private String stopReason;
    private String errorClass;
    private String errorMessage;
    private Long observedTokensLimit;
    private Long observedRequestsLimit;
    private Map<String, String> headers;

    public String getSpecId() {
        return specId;
    }

    public void setSpecId(String specId) {
        this.specId = specId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Classification getClassification() {
        return classification;
    }

    public void setClassification(Classification classification) {
        this.classification = classification;
    }

    public Instant getProbedAt() {
        return probedAt;
    }

    public void setProbedAt(Instant probedAt) {
        this.probedAt = probedAt;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getServedModelId() {
        return servedModelId;
    }

    public void setServedModelId(String servedModelId) {
        this.servedModelId = servedModelId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getObservedTokensLimit() {
        return observedTokensLimit;
    }

    public void setObservedTokensLimit(Long observedTokensLimit) {
        this.observedTokensLimit = observedTokensLimit;
    }

    public Long getObservedRequestsLimit() {
        return observedRequestsLimit;
    }

    public void setObservedRequestsLimit(Long observedRequestsLimit) {
        this.observedRequestsLimit = observedRequestsLimit;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
}
