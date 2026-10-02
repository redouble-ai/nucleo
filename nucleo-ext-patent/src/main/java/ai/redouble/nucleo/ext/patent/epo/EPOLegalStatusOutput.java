/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output from EPO patent legal status retrieval.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOLegalStatusOutput  {
    @LLMDescription("Chronological list of legal status events (e.g., 'Filed', 'Published', 'Granted', 'Lapsed')")
    private List<String> legalEvents;
    @LLMDescription("Current legal status of the patent (e.g., granted, pending, lapsed, expired)")
    private String currentStatus;
    public EPOLegalStatusOutput() {
    }
    public List<String> getLegalEvents() {
        return legalEvents;
    }
    public void setLegalEvents(List<String> legalEvents) {
        this.legalEvents = legalEvents;
    }
    public String getCurrentStatus() {
        return currentStatus;
    }
    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }
}
