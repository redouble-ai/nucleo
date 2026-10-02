/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output containing USPTO ODP continuity data.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpContinuityOutput {
    @LLMDescription("Patent number")
    private String patentNumber;
    @LLMDescription("Parent application patent numbers")
    private List<String> parentPatents;
    @LLMDescription("Child application patent numbers")
    private List<String> childPatents;
    @LLMDescription("All related US patent/application numbers in the continuity chain")
    private List<String> familyMembers;
    public UsptoOdpContinuityOutput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public List<String> getParentPatents() {
        return parentPatents;
    }
    public void setParentPatents(List<String> parentPatents) {
        this.parentPatents = parentPatents;
    }
    public List<String> getChildPatents() {
        return childPatents;
    }
    public void setChildPatents(List<String> childPatents) {
        this.childPatents = childPatents;
    }
    public List<String> getFamilyMembers() {
        return familyMembers;
    }
    public void setFamilyMembers(List<String> familyMembers) {
        this.familyMembers = familyMembers;
    }
}
