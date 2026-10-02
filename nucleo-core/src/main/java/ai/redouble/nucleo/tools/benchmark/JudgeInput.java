/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.harness.schema.*;

/**
 * What the judge sees: the task as the job received it, the reference run in full - the
 * strongest model's transcript, every tool it called and what each returned, and its answer,
 * the ground truth - the candidate run in full the same way, and any extra criteria the
 * caller gave. Nothing says which model the candidate was.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class JudgeInput {
    @LLMDescription("The task both runs were given, as the job received it")
    private String task;
    @LLMDescription("Extra criteria from the caller, beyond the rules; may be absent")
    private String rubric;
    @LLMDescription("The reference run, the ground truth: its transcript - every tool call, what each tool returned - and its final answer")
    private String reference;
    @LLMDescription("The run to judge: its transcript the same way, and its final answer")
    private String candidate;

    public String getTask() {return task;}

    public void setTask(String task) {this.task = task;}

    public String getRubric() {return rubric;}

    public void setRubric(String rubric) {this.rubric = rubric;}

    public String getReference() {return reference;}

    public void setReference(String reference) {this.reference = reference;}

    public String getCandidate() {return candidate;}

    public void setCandidate(String candidate) {this.candidate = candidate;}
}
