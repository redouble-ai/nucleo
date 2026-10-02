/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Thinker output that is itself an {@link Artifact}, so a thinker can serve as a
 * fan-out worker: its typed answer lands directly in the result list and the
 * artifact registry. Single inheritance forces this class to carry its own ref
 * field and summary cache instead of extending {@link AbstractArtifact} - the
 * {@link Artifact} interface is the contract, {@code AbstractArtifact} is only a
 * convenience base.
 *
 * <p>Concrete subclasses need a {@code @TypeAlias} annotation (registration mints
 * refs from it) and should keep their answer data in serializable fields: worker
 * outputs must be self-contained, because refs pointing into the worker thinker's
 * own conversation registry are worker-scoped and do not resolve in the parent.
 *
 * @param <R> the reasoning type for this response
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public abstract class ArtifactThinkerOutput<R extends Reasoning> extends ThinkerOutput<R> implements Artifact {
    @LLMDescription("Unique reference identifier for this artifact in format artifact:type~uuid")
    private String artifactRef;

    @JsonIgnore
    private transient Map<String, SummarizedField> summaryCache;

    @Override
    public String getArtifactRef() {
        return artifactRef;
    }

    @Override
    public void setArtifactRef(String artifactRef) {
        this.artifactRef = artifactRef;
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
        if (summaryCache == null) {
            summaryCache = new ConcurrentHashMap<>();
        }
        summaryCache.put(fieldName, value);
    }
}
