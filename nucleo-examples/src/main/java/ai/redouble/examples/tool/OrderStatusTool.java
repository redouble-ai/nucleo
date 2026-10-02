/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.tool;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;

/**
 * A model on its own can only read and write text; for everything else it needs tools,
 * pieces of your code it may ask to have run. This is one: it looks an order up, and a
 * model calling it never touches the data itself - it says "run order_status with this
 * number", Nucleo runs this code, and the model reads what came back.
 *
 * <p>A model chooses a tool the way a person chooses a button: by reading its label.
 * {@code @ToolName} is the name it calls the tool by and {@code @ToolDescription} says what
 * the tool is for; {@code readOnly = true} promises the tool changes nothing, which lets it
 * be offered to agents that are only allowed to look. The input and output are plain beans:
 * from {@link OrderNumber} Nucleo builds the form a model fills in, and an
 * {@link OrderStatus} goes back to code as the object and to a model as text.
 *
 * <p>{@code getRequirements()} is where a tool asks for what it needs while it runs - a
 * connection, a transaction - and Nucleo hands them over when the tool starts and takes
 * them back when it ends. This one needs nothing.
 *
 * <p>The two failures are typed, and that is how the model knows what to do next:
 * {@link InvalidInputException} means the caller asked the wrong way and says how to ask
 * right, so a model corrects its call and tries again; {@link ResourceNotFoundException}
 * means the order does not exist.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// The MCP server example (ai.redouble.examples.mcpserver) serves this tool to outside
// clients; the annotation makes it offerable and changes nothing about local use.
@MCP
// region tool
@ToolName("order_status")
@ToolDescription(value = "Look up one order by its number: its status, its customer, the date it was promised for", readOnly = true)
public class OrderStatusTool extends AbstractTool<OrderNumber, OrderStatus> {
    public OrderStatusTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        requirements.setReadOnly(true);
        return requirements;
    }

    @Override
    public OrderStatus execute(JobResources resources, JobContext<OrderStatus> context) throws LLMReadableCheckedException {
        String number = getInput().getOrderNumber();
        if (!Orders.NUMBER.matcher(number).matches()) {
            throw new InvalidInputException("orderNumber", number, "an order number is the letter A, a dash and four digits, like A-1042");
        }
        OrderStatus order = Orders.find(number);
        if (order == null) {
            throw new ResourceNotFoundException("order", number);
        }
        return order;
    }
}
// endregion
