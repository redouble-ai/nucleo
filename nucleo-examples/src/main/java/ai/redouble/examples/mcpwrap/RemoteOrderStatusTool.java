/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpwrap;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * One tool of the orders MCP server wrapped as a tool of this application's own, with the
 * input and output classes fixed here: the same {@link OrderNumber} in and
 * {@link OrderStatus} out as the local tool. Only the body reaches over the wire.
 *
 * <p>For a remote tool your workflows depend on, this is the right way, and the generic
 * call is for browsing. The contract is yours: rename a field on the server and agents on
 * the generic path quietly start seeing a different tool, while here the parse fails
 * loudly, in one place, with a typed seam to fix. Scopes and guardrails can judge typed
 * fields, where a claim inside a generic JSON blob is invisible to them. The
 * {@code readOnly} declaration is yours to assert, so a read-only agent may keep the tool -
 * a generic MCP tool is never read-only, because nothing can vouch for it. And a test hands
 * {@link OrdersServer} a fake and runs this without a network.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region wrapper
@ToolName("order_status")
@ToolDescription(value = "Look up one order by its number: its status, its customer, the date it was promised for", readOnly = true)
public class RemoteOrderStatusTool extends AbstractTool<OrderNumber, OrderStatus> {
    public RemoteOrderStatusTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        requirements.setReadOnly(true);
        // The call travels over HTTP, so it takes a place on the shared HTTP pool
        requirements.setRequiresHttpConnection(true);
        return requirements;
    }

    @Override
    public OrderStatus execute(JobResources resources, JobContext<OrderStatus> context) throws LLMReadableCheckedException {
        try {
            ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
            arguments.put("order_number", getInput().getOrderNumber());
            MCPToolResult result = OrdersServer.client().callTool("order_status", arguments);
            return NucleoJsonSerializer.parse(result.getTextContent(), OrderStatus.class);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
// endregion
