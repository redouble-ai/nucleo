/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for reading rows of a list the conversation holds.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@LLMDescription("Which rows of a list to read")
public class ReadRowsInput {
    @LLMRequired
    @LLMDescription("The reference of the list, exactly as shown beside it, e.g. «artifact:list~abc123»")
    private String list;
    @LLMDescription("The first row to read, counted from 1. Default: 1")
    private Long fromRow;
    @LLMDescription("How many rows to read. Default: 10")
    private Long howMany;

    public String getList() {
        return list;
    }

    public void setList(String list) {
        this.list = list;
    }

    public Long getFromRow() {
        return fromRow;
    }

    public void setFromRow(Long fromRow) {
        this.fromRow = fromRow;
    }

    public Long getHowMany() {
        return howMany;
    }

    public void setHowMany(Long howMany) {
        this.howMany = howMany;
    }
}
