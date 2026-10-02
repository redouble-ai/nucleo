/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * Lightweight tool for asking focused questions about specific text.
 *
 * <p>This tool acts as a sub-agent, allowing the Thinker to ask quick,
 * targeted questions without the overhead of full conversation context,
 * and can be run in parallel for comparative analysis.
 *
 * <p>The grade travels IN THE INPUT, per question. This tool is how anyone, from
 * anywhere - a thinker's model, a doer, a servlet - asks one question without
 * paying for a thinker, and no fixed grade could serve every asker: the same tool
 * answers a lookup at SMALL and a subtle judgment at XL. So the tool is not a
 * {@link ModelDependent} seat with a grade of its own; the asker states the rung
 * in {@link QuickLLMQuestionInput#getGrade()}, and the deployment's picker turns
 * that rung into a model as for every seat.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-20)
 */
@DisplayName(value = "Quick Question", action = "Answering Quick Question with AI")
@ToolName("quick_llm_question")
@ToolDescription(value = "Ask a focused question about specific text at the grade the question deserves. Returns structured answer with confidence and reasoning.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 5, max = 5)
public class QuickLLMQuestionTool extends AbstractModelDependentTool<QuickLLMQuestionInput, QuickLLMQuestionOutput> {
    public QuickLLMQuestionTool(Identifiable parent) {
        super(parent);
        // 6 min: hung-call guard for one LLM call - "quick" is the intent, not a safe ceiling; a
        // reasoning-capable model routinely exceeds a few seconds. Matches LLMCall.
        setTimeout(Duration.ofMinutes(6));
    }

    private static final String INSTRUCTIONS =
            "Analyze the input data and provide a direct, concise answer. " +
            "Be precise and base your answer only on the provided context.";

    @Override
    public JobRequirements getRequirements() {
        if (input == null || input.getGrade() == null) {
            // A model caller is caught earlier by the @LLMRequired parse; this is the code caller
            // that built the input by hand and left the one field only it can decide
            throw new CorrectableRuntimeLLMException("quick_llm_question needs a grade in its input: only the asker"
                    + " knows how much model this question deserves (SMALL for a lookup, MEDIUM for reasoning over the"
                    + " context, XL for deep judgment, MEGA for the strongest available).");
        }
        JobRequirements req = new JobRequirements();
        // The asker names the grade (only it knows how hard the question is); the tool names the
        // answer size, because its own instruction fixes the shape: a direct, concise answer
        setGrade(input.getGrade());
        wireConversation(req, Depth.STANDARD, OutputDeclaration.of(OutputSize.COMPACT), QuickLLMQuestionOutput.class, INSTRUCTIONS);
        req.setRequiresTransaction(false);
        return req;
    }

    @Override
    public QuickLLMQuestionOutput execute(JobResources resources, JobContext<QuickLLMQuestionOutput> context) throws LLMReadableCheckedException {
        try {
            context.publish("Calling LLM", 30);
            // the wired conversation on the client the harness resolved, with the model corrected on a bad answer
            QuickLLMQuestionOutput output = converse(resources);
            context.publish("Complete", 100);
            return output;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}