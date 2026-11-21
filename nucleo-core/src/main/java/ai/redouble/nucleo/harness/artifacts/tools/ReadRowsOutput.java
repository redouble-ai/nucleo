/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * The rows read from a list, with where they stand in it.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@LLMDescription("Rows read from a list")
public class ReadRowsOutput {
    @LLMDescription("How many rows the list holds in all")
    private Long rowsInList;
    @LLMDescription("The position in the list of the first row returned, counted from 1")
    private Long fromRow;
    @LLMDescription("The rows read, in the order of the list")
    private List<Artifact> rows;

    public Long getRowsInList() {
        return rowsInList;
    }

    public void setRowsInList(Long rowsInList) {
        this.rowsInList = rowsInList;
    }

    public Long getFromRow() {
        return fromRow;
    }

    public void setFromRow(Long fromRow) {
        this.fromRow = fromRow;
    }

    public List<Artifact> getRows() {
        return rows;
    }

    public void setRows(List<Artifact> rows) {
        this.rows = rows;
    }
}
