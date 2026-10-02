/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * The stock {@link ComplianceEnvelope}: the normal compliance decisions as declared
 * state, refusing by default. A fresh instance permits everything except
 * provider-data-share models, which is the framework's default posture when a servlet
 * declares no envelope at all.
 *
 * <ul>
 *   <li>{@link #setAllowDataShare(boolean)} - opt-in for models that are served only
 *       under a data retention mode sharing prompts with the provider
 *       ({@link ModelSpec#requiresLax()}). Default false.
 *   <li>{@link #setAllowedProviderKeys(Set)} - when non-null, ONLY these provider keys
 *       may serve ("everything through our AWS account" is
 *       {@code setAllowedProviderKeys(Set.of("anthropic-bedrock", "anthropic-bedrock-mantle", ...))}).
 *   <li>{@link #setDeniedProviderKeys(Set)} - these provider keys never serve.
 *   <li>{@link #setDeniedIdentities(Set)} - these model identities never serve,
 *       regardless of endpoint.
 * </ul>
 *
 * <p>Policies beyond these knobs are authored as their own {@link ComplianceEnvelope}
 * implementations, not added here as flags.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public class DefaultComplianceEnvelope implements ComplianceEnvelope {
    private boolean allowDataShare;
    private Set<String> allowedProviderKeys;
    private Set<String> deniedProviderKeys;
    private Set<String> deniedIdentities;

    @Override
    public boolean permits(ModelSpec spec) {
        if (spec.requiresLax() && !allowDataShare) {
            return false;
        }
        if (allowedProviderKeys != null && !allowedProviderKeys.contains(spec.getProviderKey())) {
            return false;
        }
        if (deniedProviderKeys != null && deniedProviderKeys.contains(spec.getProviderKey())) {
            return false;
        }
        if (deniedIdentities != null && deniedIdentities.contains(spec.getIdentity())) {
            return false;
        }
        return true;
    }

    public boolean isAllowDataShare() {
        return allowDataShare;
    }

    public void setAllowDataShare(boolean allowDataShare) {
        this.allowDataShare = allowDataShare;
    }

    public Set<String> getAllowedProviderKeys() {
        return allowedProviderKeys;
    }

    public void setAllowedProviderKeys(Set<String> allowedProviderKeys) {
        this.allowedProviderKeys = allowedProviderKeys;
    }

    public Set<String> getDeniedProviderKeys() {
        return deniedProviderKeys;
    }

    public void setDeniedProviderKeys(Set<String> deniedProviderKeys) {
        this.deniedProviderKeys = deniedProviderKeys;
    }

    public Set<String> getDeniedIdentities() {
        return deniedIdentities;
    }

    public void setDeniedIdentities(Set<String> deniedIdentities) {
        this.deniedIdentities = deniedIdentities;
    }
}
