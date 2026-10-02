/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import java.time.*;

/**
 * Lineage metadata for a {@link Prompt}: content hash, production timestamp, optional
 * version and variant tag. Produced by the {@link Prompts} facade at build time; never
 * serialized into the wire payload sent to the LLM, and never persisted inside the Prompt
 * itself.
 *
 * <p>Equality and identity decisions use {@link #getContentHash()}: two Prompts with
 * identical (key, content) yield identical hashes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class PromptContext {
    private String contentHash;
    private Instant producedAt;
    private String version;
    private String variantTag;

    public PromptContext() {
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getProducedAt() {
        return producedAt;
    }

    public void setProducedAt(Instant producedAt) {
        this.producedAt = producedAt;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVariantTag() {
        return variantTag;
    }

    public void setVariantTag(String variantTag) {
        this.variantTag = variantTag;
    }
}
