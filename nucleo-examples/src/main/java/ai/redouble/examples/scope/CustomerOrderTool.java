/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scope;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

/**
 * Looks an order up among one customer's orders only, so the customer the input claims is
 * the customer whose data the tool reads: the field the scope judges is the field the tool
 * acts on.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
@ToolName("customer_order")
@ToolDescription(value = "Look up one order of one customer: its status and the date it was promised for", readOnly = true)
public class CustomerOrderTool extends AbstractTool<CustomerOrder, OrderStatus> {
    public CustomerOrderTool(Identifiable parent) {
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
        for (OrderStatus order : Orders.ofCustomer(getInput().getCustomerId())) {
            if (order.getOrderNumber().equals(getInput().getOrderNumber())) {
                return order;
            }
        }
        throw new ResourceNotFoundException("order of " + getInput().getCustomerId(), getInput().getOrderNumber());
    }
}
