/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output from EPO INPADOC patent family retrieval.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOFamilyOutput  {
    @LLMDescription("List of family member patent numbers in DOCDB format")
    private List<String> familyMembers;
    @LLMDescription("INPADOC family identifier")
    private String familyId;
    public EPOFamilyOutput() {
    }
    public List<String> getFamilyMembers() {
        return familyMembers;
    }
    public void setFamilyMembers(List<String> familyMembers) {
        this.familyMembers = familyMembers;
    }
    public String getFamilyId() {
        return familyId;
    }
    public void setFamilyId(String familyId) {
        this.familyId = familyId;
    }
}
