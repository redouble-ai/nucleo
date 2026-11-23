/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tool's input and output are the types bound to {@code I} and {@code O} of
 * {@code Tool<I, O>}, whatever the classes between the tool and {@code Tool} declare: a base
 * class with its type parameters in another order, with more parameters than two, one that
 * fixes the input and leaves the output to the subclass, a base whose own variable is named
 * like {@code Tool}'s and means something else, and a tool that extends a base which fixed
 * both. The schema published and the input parsed are those of the input so resolved. A tool
 * whose hierarchy leaves the input open is refused when its provider is built.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
class ClassToolProviderTypeResolutionTest {

    public static class Order {
        private String number;

        public String getNumber() {return number;}

        public void setNumber(String number) {this.number = number;}
    }

    public static class LookupInput {
        @LLMDescription("How many to return")
        private Integer limit;

        public Integer getLimit() {return limit;}

        public void setLimit(Integer limit) {this.limit = limit;}
    }

    public static class OrderLookupInput extends LookupInput {
        @LLMDescription("The customer whose orders to find")
        private String customer;

        public String getCustomer() {return customer;}

        public void setCustomer(String customer) {this.customer = customer;}
    }

    public static class LookupResult<E> {
        private List<E> found;

        public List<E> getFound() {return found;}

        public void setFound(List<E> found) {this.found = found;}
    }

    /** The row first, the input last, the output built from the row: nothing is where {@code Tool} has it. */
    public abstract static class AbstractLookupTool<E, I extends LookupInput> extends AbstractTool<I, LookupResult<E>> {
        protected AbstractLookupTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public LookupResult<E> execute(JobResources resources, JobContext<LookupResult<E>> context) {
            return new LookupResult<>();
        }
    }

    @ToolName("order_lookup")
    @LLMDescription("Finds orders.")
    @ReadOnly
    public static class OrderLookupTool extends AbstractLookupTool<Order, OrderLookupInput> {
        public OrderLookupTool(Identifiable parent) {
            super(parent);
        }
    }

    /** Three parameters, and the one named I is the row, not the input. */
    public abstract static class AbstractTriple<I, X, Q extends LookupInput> extends AbstractLookupTool<I, Q> {
        protected AbstractTriple(Identifiable parent) {
            super(parent);
        }
    }

    @ToolName("order_triple")
    @LLMDescription("Finds orders, the long way.")
    @ReadOnly
    public static class OrderTripleTool extends AbstractTriple<Order, String, OrderLookupInput> {
        public OrderTripleTool(Identifiable parent) {
            super(parent);
        }
    }

    /** Fixes the input, leaves the output to the subclass. */
    public abstract static class AbstractOrderTool<R> extends AbstractTool<OrderLookupInput, R> {
        protected AbstractOrderTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public R execute(JobResources resources, JobContext<R> context) {
            return null;
        }
    }

    @ToolName("order_first")
    @LLMDescription("Finds one order.")
    @ReadOnly
    public static class FirstOrderTool extends AbstractOrderTool<Order> {
        public FirstOrderTool(Identifiable parent) {
            super(parent);
        }
    }

    /** Extends a tool that fixed both types, declaring nothing itself. */
    @ToolName("order_lookup_again")
    @LLMDescription("Finds orders, again.")
    @ReadOnly
    public static class DerivedOrderLookupTool extends OrderLookupTool {
        public DerivedOrderLookupTool(Identifiable parent) {
            super(parent);
        }
    }

    /** Extends the generic base raw, so nothing binds its input. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @ToolName("order_raw")
    @LLMDescription("Finds nothing in particular.")
    @ReadOnly
    public static class RawLookupTool extends AbstractLookupTool {
        public RawLookupTool(Identifiable parent) {
            super(parent);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a base class with its parameters in another order: the input is the one bound to Tool's I")
    void parametersInAnotherOrder() {
        ClassToolProvider provider = ClassToolProvider.of(OrderLookupTool.class);
        assertEquals(OrderLookupInput.class, provider.inputType(), "the row class stands first and is not the input");
        assertEquals(LookupResult.class, provider.outputType(), "the output is what the base hands to Tool, not its second parameter");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the schema published and the input parsed are those of the resolved input")
    void schemaAndParsingFollowTheResolvedInput() throws Exception {
        ClassToolProvider provider = ClassToolProvider.of(OrderLookupTool.class);
        JsonNode properties = NucleoJsonSerializer.readTree(provider.schemaJson()).path("properties");
        assertTrue(properties.has("customer") && properties.has("limit"), "the input's own and inherited fields are published: " + properties);
        assertFalse(properties.has("number"), "the row's field is no part of the input: " + properties);
        Object parsed = provider.parseInput(NucleoJsonSerializer.readTree("{\"customer\":\"C-100\",\"limit\":5}"));
        OrderLookupInput input = assertInstanceOf(OrderLookupInput.class, parsed);
        assertEquals("C-100", input.getCustomer());
        assertEquals(5, input.getLimit());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("more parameters than two, and a variable named like Tool's that means the row")
    void moreParametersAndACollidingName() {
        ClassToolProvider provider = ClassToolProvider.of(OrderTripleTool.class);
        assertEquals(OrderLookupInput.class, provider.inputType());
        assertEquals(LookupResult.class, provider.outputType());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a base that fixes the input and leaves the output to the subclass")
    void inputFixedByTheBaseOutputByTheSubclass() {
        ClassToolProvider provider = ClassToolProvider.of(FirstOrderTool.class);
        assertEquals(OrderLookupInput.class, provider.inputType());
        assertEquals(Order.class, provider.outputType());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a tool that extends one which fixed both types")
    void derivedFromAFixedTool() {
        ClassToolProvider provider = ClassToolProvider.of(DerivedOrderLookupTool.class);
        assertEquals(OrderLookupInput.class, provider.inputType());
        assertEquals(LookupResult.class, provider.outputType());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a tool whose hierarchy leaves the input open is refused, by name")
    void openInputIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ClassToolProvider.of(RawLookupTool.class));
        assertTrue(refused.getMessage().contains("RawLookupTool"), refused.getMessage());
    }
}
