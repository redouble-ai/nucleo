/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;

/**
 * Served leaf whose result is an artifact rather than a plain POJO. Nothing here registers
 * it in a registry - the serving layer mints the ref, which is exactly the path under test.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
@MCP
@ToolName("mcp_citing")
@DisplayName(value = "Citing", action = "Citing")
@ToolDescription(value = "Answers with a citation artifact.", readOnly = true)
public class CitingTool extends AbstractTool<NoFieldsInput, ServedCitation> {
    public static final String DOI = "10.1038/s41586-024-07386-0";

    public CitingTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public ServedCitation execute(JobResources resources, JobContext<ServedCitation> context) {
        ServedCitation citation = new ServedCitation();
        citation.setTitle("A paper worth citing");
        citation.setUrl("https://example.org/paper");
        citation.setDoi(DOI);
        return citation;
    }
}
