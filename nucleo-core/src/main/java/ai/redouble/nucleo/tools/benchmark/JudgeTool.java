/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import java.time.*;

/**
 * The benchmark's judge: the strongest model the deployment serves, comparing one
 * candidate run against the reference run under one fixed rubric, so every model is
 * measured by the same stick. The reference is the ground truth: the same job on the
 * strongest model, its transcript showing every tool it called and what each returned, and
 * its answer. The judge never calls a tool itself - the truth is in the reference - and
 * never learns which model the candidate was. One call per candidate, so a judge that fails
 * costs one score, never the table, and the candidates are judged in parallel as they land.
 *
 * <p>The rubric, spelled out in {@link #INSTRUCTIONS}: 7 points for accuracy - 7 for an
 * answer matching the reference in substance, 6 or 6.5 for a different answer under a
 * defensible reading of the task the reference did not take (inclusive versus exclusive day
 * counts, an unstated unit or rounding), 3 to 5 partly right, 0 to 2 wrong - with a tool
 * called wrongly, skipped or misread costing at least 3 of them even when everything after
 * the mistake is self-consistent; and 3 points for shown work, the steps visible and resting
 * on the tool results, length earning nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
@DisplayName(value = "Judge", action = "Judging a run of a benchmark against the reference")
@ToolName("benchmark_judge")
@ToolDescription(value = "Scores one run of a task against the reference run under a fixed rubric, blind.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 3, max = 3)
public class JudgeTool extends AbstractModelDependentTool<JudgeInput, JudgeVerdict> {
    static final String INSTRUCTIONS = """
            You judge one run of a job against the reference run of the same job. The reference is the \
            ground truth: it was made by the strongest model available, using the same tools; its transcript \
            shows every tool it called, what each tool returned, and the answer it gave. Do not call any tool \
            and do not work the task out yourself - compare the candidate to the reference. Score by exactly \
            these rules, so every model is measured by the same stick.

            ACCURACY, 0 to 7 points, in half points:
            - 7: the candidate's final answer matches the reference's answer in substance - the same facts, \
            values, dates and conclusions. Wording and formatting differences do not matter.
            - 6 or 6.5: the answer differs from the reference's, but the difference comes from a legitimate \
            reading of the task that the reference did not take - counting the first and last day of a period \
            or not, a unit or a rounding the task left unstated, a tie the task leaves open. Award this only \
            when the candidate's reading is defensible from the task text AND the answer is right under it.
            - 3 to 5: partly right - the main conclusion holds but a stated value, date or component is wrong \
            or missing.
            - 0 to 2: wrong, empty, or contradicting what the tools returned.
            TOOL USE is judged inside the accuracy points. The reference transcript shows what the tools \
            return for this task. A candidate that called a tool with wrong arguments, skipped a tool it \
            needed and guessed instead, or misread what a tool returned loses at least 3 accuracy points, \
            even when everything it did after that mistake is self-consistent: a right-looking answer built on \
            a misread tool result is not right. Name the fault in toolFault; when the tools were used right, \
            leave toolFault out of the answer entirely - never write "none".

            REASONING, 0 to 3 points, in half points: how well the run shows its work - the steps taken are \
            visible and sensible, the tool results are used as evidence, the answer says what it rests on. \
            3 is a clear, complete account; 0 is a bare answer with no work shown, or work that contradicts \
            the answer. Length is not work: a short correct chain outranks a long padded one.

            Report accuracy and reasoning as the two numbers, the tool fault only when there is one, and a \
            reason of two or three sentences naming what you compared and where the points went.""";

    public JudgeTool(Identifiable parent) {
        super(parent, Grade.CEILING);
        setTimeout(Duration.ofMinutes(6));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        wireConversation(req, Depth.STANDARD, OutputDeclaration.of(OutputSize.STANDARD), JudgeVerdict.class, INSTRUCTIONS);
        return req;
    }

    @Override
    public JudgeVerdict execute(JobResources resources, JobContext<JudgeVerdict> context) throws LLMReadableCheckedException {
        try {
            return converse(resources);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
