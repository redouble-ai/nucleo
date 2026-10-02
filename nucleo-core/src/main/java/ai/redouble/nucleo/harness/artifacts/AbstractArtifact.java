/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Base class for all artifact entities that can be preserved across agent workflows.
 *
 * <p>Provides the artifactRef field that uniquely identifies this artifact within
 * the agent execution context. The reference format is {@code «artifact:alias~uuid»} where:
 * <ul>
 *   <li>alias is the hierarchical {@code @TypeAlias} value (colons separate IS-A levels,
 *       e.g. "link:cite:pubmed" means PubMedArticle IS-A CitationArtifact IS-A LinkArtifact)</li>
 *   <li>uuid is a 6-character alphanumeric identifier</li>
 * </ul>
 *
 * <p>Implements summary caching from {@link Artifact}: the Jackson serializer lazily
 * populates the cache during {@code writeSummarized}/{@code writeSummarizedWithRefs} calls.
 * Subsequent serializations reuse cached summaries. Search and field access tools read
 * full text from the cached {@link SummarizedField} entries.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public abstract class AbstractArtifact implements Artifact {
    @LLMDescription("Unique reference identifier for this artifact in format artifact:type~uuid")
    private String artifactRef;

    /**
     * Cached summaries: snake_case field name -> SummarizedField.
     * Populated lazily by the Jackson serializer during summarized serialization.
     * Transient - not serialized with the artifact itself. Volatile with a synchronized
     * first-write: an artifact conveyed across jobs can be serialized by two threads at
     * once, and the lazy creation must not lose one thread's map.
     */
    @JsonIgnore
    private transient volatile Map<String, SummarizedField> summaryCache;

    @Override
    public String getArtifactRef() {
        return artifactRef;
    }

    @Override
    public void setArtifactRef(String artifactRef) {
        this.artifactRef = artifactRef;
    }

    /**
     * Checks if this artifact has been assigned a reference.
     *
     * @return true if artifactRef is set, false otherwise
     */
    public boolean hasReference() {
        return artifactRef != null && !artifactRef.trim().isEmpty();
    }

    @Override
    public SummarizedField getCachedSummary(String fieldName) {
        if (summaryCache == null) {
            return null;
        }
        return summaryCache.get(fieldName);
    }

    @Override
    public void cacheSummary(String fieldName, SummarizedField value) {
        Map<String, SummarizedField> cache = summaryCache;
        if (cache == null) {
            synchronized (this) {
                cache = summaryCache;
                if (cache == null) {
                    cache = new ConcurrentHashMap<>();
                    summaryCache = cache;
                }
            }
        }
        cache.put(fieldName, value);
    }

    @Override
    public String toString() {
        if (hasReference()) {
            return artifactRef;
        }
        return super.toString();
    }
}
