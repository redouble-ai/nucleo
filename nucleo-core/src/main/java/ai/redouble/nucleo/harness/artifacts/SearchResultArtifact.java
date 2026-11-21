/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;

/**
 * The answer of a search: how many rows match in the whole store, and one window of them.
 *
 * <p>A tool that searches a store returns this in place of a bare list, so that a model
 * always knows how much there is before it decides how much to read. The limit decides how
 * many rows are fetched and held in {@link #getRows() rows}; a model is shown that list as
 * its digest - the count held and the first few rows - whatever the limit, as it is shown
 * every {@link ListArtifact}. Every row is indexed under its own reference when the result is
 * registered.
 *
 * <p>The tool sets all four fields from its query: the count of matches, the limit and the
 * offset the query ran with, and the rows it fetched. Nothing here is specific to a database.
 *
 * @param <T> the artifact type of a row
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@TypeAlias("search_result")
public class SearchResultArtifact<T extends Artifact> extends AbstractArtifact {
    @LLMDescription("How many rows match the search in the whole store, of which rows holds one window")
    private Long totalMatching;
    @LLMDescription("The position of the first row of this window among the matching rows, counted from 0")
    private Long offset;
    @LLMDescription("The largest number of rows this window was allowed to hold")
    private Long limit;
    @LLMDescription("The rows of this window, in the order of the search")
    private ListArtifact<T> rows;

    public Long getTotalMatching() {
        return totalMatching;
    }

    public void setTotalMatching(Long totalMatching) {
        this.totalMatching = totalMatching;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public Long getLimit() {
        return limit;
    }

    public void setLimit(Long limit) {
        this.limit = limit;
    }

    public ListArtifact<T> getRows() {
        return rows;
    }

    public void setRows(ListArtifact<T> rows) {
        this.rows = rows;
    }
}
