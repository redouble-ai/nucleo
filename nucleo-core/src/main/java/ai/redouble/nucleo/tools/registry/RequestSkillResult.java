/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Result from requesting skills via {@code request_skill}. Structurally mirrors
 * {@link RequestToolsResult}: admitted names for confirmation, rejected entries with
 * reasons so the LLM can adapt.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class RequestSkillResult {
    @LLMDescription("Skills that were admitted into the conversation")
    private List<String> admitted;

    @LLMDescription("Skills that were rejected with reasons")
    private List<RejectedSkill> rejected;

    public List<String> getAdmitted() {
        return admitted;
    }

    public void setAdmitted(List<String> admitted) {
        this.admitted = admitted;
    }

    public List<RejectedSkill> getRejected() {
        return rejected;
    }

    public void setRejected(List<RejectedSkill> rejected) {
        this.rejected = rejected;
    }

    public static class RejectedSkill {
        @LLMDescription("Skill name")
        private String name;

        @LLMDescription("Reason for rejection")
        private String reason;

        public RejectedSkill() {
        }

        public RejectedSkill(String name, String reason) {
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
