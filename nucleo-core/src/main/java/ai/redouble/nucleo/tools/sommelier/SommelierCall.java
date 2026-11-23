/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * One exchange of {@link ModelDrivenSommelier} with a model: the model reads a need and a set of
 * tool pages side by side and names the tools that fit. The model sees the tools together
 * on purpose. Judged one at a time, a tool that is merely plausible is accepted; judged
 * beside the others, the one that holds the answer is picked.
 *
 * <p>The instruction tells the model to name every tool it cannot tell apart from another
 * for this need. The asking thinker knows its task and chooses among them; a model that
 * reads only the catalog would be guessing.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@DisplayName(value = "Sommelier", action = "Finding the tools that serve a need")
@ToolName("pair_need_with_tools")
@LLMDescription("Names the tools of a catalog that serve a stated need.")
@ReadOnly
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class SommelierCall extends AbstractModelDependentTool<SommelierQuestion, SommelierAnswer> {

    /** The grade the lookup runs at when the deployment names no role for it. */
    public static final Grade GRADE = Grade.SMALL;

    static final String INSTRUCTIONS = """
            Someone working on a task needs something, and a catalog holds many tools. The input gives the need and every tool of the catalog.

            Name each tool for which calling it is a reasonable way to obtain what is needed, in whole or in a necessary part.

            Judge from each tool's name, description and fields as they are written. A tool that shares words with the need but holds something else does not fit. A tool that holds one necessary part of the answer fits.

            A catalog can hold several tools that look alike, such as the same kind of record kept by different applications. When what is written does not tell you which of them the need is about, name all of them: whoever asked knows things you do not and will choose. Never pick one of several look-alikes by guessing.""";

    private ModelRole role;

    public SommelierCall(Identifiable parent) {
        super(parent, "SOMMELIER", GRADE);
        // a hung-call guard for one model call, not its expected latency
        setTimeout(Duration.ofMinutes(6));
    }

    /** The role whose work this call does: its catalog entry serves the call, and its grade stands in when the entry cannot. */
    public void setRole(ModelRole role) {
        this.role = role;
        setGrade(role.grade());
    }

    @Override
    public ModelRole getRole() {
        return role;
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        // the answer is a short list of names
        wireConversation(req, Depth.QUICK, OutputDeclaration.of(OutputSize.COMPACT), SommelierAnswer.class, INSTRUCTIONS);
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public SommelierAnswer execute(JobResources resources, JobContext<SommelierAnswer> context) throws LLMReadableCheckedException {
        return converse(resources);
    }
}
