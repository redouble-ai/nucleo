/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import java.time.*;

/**
 * The tier for what the code could not place: a small model looks at the file's head and
 * says whether it is text under a strange extension, a binary with nothing to extract, or
 * something a person should look at. One cheap call per unknown file, at the grade a
 * verdict deserves.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
@DisplayName(value = "Classify File", action = "Judging an unknown file")
@ToolName("classify_file")
@ToolDescription(value = "Decides from a file's first bytes whether it is text, a binary, or a person's problem.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 2, max = 2)
public class ClassifyFileTool extends AbstractModelDependentTool<FileHead, Classification> {
    private static final String INSTRUCTIONS = "Decide what this file is from its name, size and first bytes. Text in any"
            + " language or format is TEXT, whatever the extension says. Compiled, compressed, encrypted or media content"
            + " is BINARY. If the head does not let you decide, say NEEDS_PERSON rather than guessing.";
    public ClassifyFileTool(Identifiable parent) {
        super(parent, Grade.SMALL);
        setTimeout(Duration.ofMinutes(2));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        wireConversation(req, Depth.IMMEDIATE, OutputDeclaration.of(OutputSize.VERDICT), Classification.class, INSTRUCTIONS);
        return req;
    }

    @Override
    public Classification execute(JobResources resources, JobContext<Classification> context) throws LLMReadableCheckedException {
        try {
            return converse(resources);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
