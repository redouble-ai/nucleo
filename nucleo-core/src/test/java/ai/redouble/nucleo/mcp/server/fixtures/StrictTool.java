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
 * Served leaf that accepts every JSON type. It echoes nothing back, so any appearance of a
 * caller's text in a response came from an error path rather than from a result.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
@MCP
@ToolName("mcp_strict")
@DisplayName(value = "Strict", action = "Checking")
@ToolDescription(value = "Accepts a typed input and reports only how many fields arrived.", readOnly = true)
public class StrictTool extends AbstractTool<StrictInput, EchoOutput> {
    public StrictTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    /**
     * Reports what actually arrived in the input object, so a test can prove a call was
     * converted and not merely accepted. A dialect whose inverse dropped a field, or whose
     * spelling turned one into something else, would pass a test that only asked whether an
     * error came back.
     *
     * <p>Only the typed fields are named, never the free text: those come from a schema that
     * fixes their shape, so none of them can carry a caller's own words. {@code text} is
     * reported by length alone, and the collection by size, because a corpus that puts
     * hostile content in a well-formed string expects it carried and never reflected.
     */
    @Override
    public EchoOutput execute(JobResources resources, JobContext<EchoOutput> context) {
        StrictInput in = getInput();
        int supplied = 0;
        for (Object optional : new Object[] {in.getCount(), in.getLoud(), in.getTags(), in.getNested(),
                in.getWhen(), in.getMode(), in.getAt(), in.getRatio()}) {
            if (optional != null) {
                supplied++;
            }
        }
        String seen = "accepted " + supplied + " optional field(s):"
                + " text=" + (in.getText() == null ? null : in.getText().length())
                + " count=" + in.getCount()
                + " loud=" + in.getLoud()
                + " tags=" + (in.getTags() == null ? null : in.getTags().size())
                + " nested=" + (in.getNested() == null ? null : "present")
                + " when=" + in.getWhen()
                + " mode=" + in.getMode()
                + " at=" + in.getAt()
                + " ratio=" + in.getRatio();
        return new EchoOutput(seen, context.getUserId());
    }
}
