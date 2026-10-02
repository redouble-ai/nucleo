/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples;

import ai.redouble.examples.scope.*;
import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the example pages promise of the examples' own code, where no model is involved: the
 * order-status tool's three outcomes, and the customer scope refusing a claim on another
 * customer before the tool runs while admitting the bound customer's own.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class ExamplesTest {
    @BeforeAll
    static void start() {
        JobDispatcher.getInstance().start();
    }

    @AfterAll
    static void stop() {
        JobDispatcher.getInstance().shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    @Test
    void theToolReturnsTheOrderUnderItsNumber() throws Exception {
        OrderStatus order = lookUp("A-1002").get();
        assertEquals("DELAYED", order.getStatus(), "A-1002 is the delayed order of C-100");
        assertEquals("C-100", order.getCustomerId(), "the order carries its customer");
    }

    @Test
    void aMalformedNumberIsTheCallersMistakeAndSaysHowToFixIt() {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> lookUp("1002").get());
        InvalidInputException refused = assertInstanceOf(InvalidInputException.class, failure.getCause(), "a malformed number is correctable");
        assertTrue(refused.getMessage().contains("like A-1042"), "the refusal states the accepted shape: " + refused.getMessage());
    }

    @Test
    void aNumberNoOrderCarriesIsNotFound() {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> lookUp("A-9999").get());
        assertInstanceOf(ResourceNotFoundException.class, failure.getCause(), "a well-formed number with no order is a fetch that found nothing");
    }

    @Test
    void aFlowBoundToOneCustomerRefusesAClaimOnAnother() throws Exception {
        String outcome = boundTo("C-100", new CustomerOrder("C-200", "A-1003"));
        assertEquals("refused: Scope mismatch: this flow is bound to CustomerScope[customerId=C-100] but the input names CustomerScope[customerId=C-200]",
                outcome, "the claim on C-200 is refused before the tool runs");
    }

    @Test
    void aFlowBoundToOneCustomerAdmitsItsOwnClaim() throws Exception {
        assertEquals("DELAYED", boundTo("C-100", new CustomerOrder("C-100", "A-1002")), "the bound customer's own order is looked up");
    }

    @Test
    void theToolLooksAnOrderUpOnlyAmongTheClaimedCustomersOrders() throws Exception {
        String outcome = boundTo("C-100", new CustomerOrder("C-100", "A-1003"));
        assertEquals("not found", outcome, "A-1003 is C-200's; claiming C-100 cannot reach it");
    }

    private static JobHandle<OrderStatus> lookUp(String number) {
        OrderStatusTool tool = new OrderStatusTool(Job.workflow("test", "order-status"));
        tool.setInput(new OrderNumber(number));
        return JobDispatcher.getInstance().submit(tool);
    }

    private static String boundTo(String customerId, CustomerOrder claim) throws Exception {
        BoundDoer doer = new BoundDoer(Job.workflow("test", "scope"), customerId);
        doer.setInput(claim);
        return JobDispatcher.getInstance().submit(doer).get();
    }

    /** Plain code bound to one customer, submitting the tool with whatever claim it was handed. */
    @ToolName("bound_lookup")
    @ToolDescription(value = "Look an order up inside one customer's case", readOnly = true)
    static class BoundDoer extends AbstractDoer<CustomerOrder, String> implements CustomerScoped {
        private final String customerId;

        BoundDoer(Identifiable parent, String customerId) {
            super(parent);
            this.customerId = customerId;
        }

        @Override
        public String getCustomerId() {
            return customerId;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            CustomerOrderTool tool = new CustomerOrderTool(this);
            tool.setInput(getInput());
            try {
                return submitInStep(tool).get().getStatus();
            }
            catch (ExecutionException e) {
                if (e.getCause() instanceof GuardrailException refused) {
                    return "refused: " + refused.getMessage();
                }
                if (e.getCause() instanceof ResourceNotFoundException) {
                    return "not found";
                }
                throw LLMReadableCheckedException.unwrap(e);
            }
            catch (InterruptedException e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }
}
