/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Result from requesting tools via the catalog.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
public class RequestToolsResult  {
    @LLMDescription("Tools that were admitted and are now available to call")
    private List<String> admitted;
    @LLMDescription("Tools that were rejected with reasons")
    private List<RejectedTool> rejected;
    public List<String> getAdmitted() {
        return admitted;
    }
    public void setAdmitted(List<String> admitted) {
        this.admitted = admitted;
    }
    public List<RejectedTool> getRejected() {
        return rejected;
    }
    public void setRejected(List<RejectedTool> rejected) {
        this.rejected = rejected;
    }

    /**
     * A tool that was rejected during admission.
     */
    public static class RejectedTool  {
        @LLMDescription("Tool name")
        private String name;
        @LLMDescription("Reason for rejection")
        private String reason;
        public RejectedTool() {
        }
        public RejectedTool(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }
        public String getName() {
            return name;
        }
        public void setName(String name) {
            this.name = name;
        }
        public String getReason() {
            return reason;
        }
        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
