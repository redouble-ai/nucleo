/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output containing USPTO ODP legal events.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpLegalEventsOutput {
    @LLMDescription("Patent number")
    private String patentNumber;
    @LLMDescription("Current application status")
    private String legalStatus;
    @LLMDescription("Chronological legal event history")
    private List<String> legalEvents;
    public UsptoOdpLegalEventsOutput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public String getLegalStatus() {
        return legalStatus;
    }
    public void setLegalStatus(String legalStatus) {
        this.legalStatus = legalStatus;
    }
    public List<String> getLegalEvents() {
        return legalEvents;
    }
    public void setLegalEvents(List<String> legalEvents) {
        this.legalEvents = legalEvents;
    }
}
