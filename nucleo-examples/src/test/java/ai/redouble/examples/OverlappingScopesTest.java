/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples;

import ai.redouble.examples.scopes.*;
import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the overlapping-scopes page promises, with no model involved: the shift files on any
 * order of its case, the dispute narrows the case to one order while the case and channel
 * bindings it inherited keep holding, and each refusal names the axis that refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
class OverlappingScopesTest {
    @BeforeAll
    static void start() {
        JobDispatcher.getInstance().start();
    }

    @AfterAll
    static void stop() {
        JobDispatcher.getInstance().shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    @Test
    void theNarrowingHoldsBesideTheInheritedBindings() throws Exception {
        EmailShiftDoer shift = new EmailShiftDoer(Job.workflow("test", "shift"), "C-100");
        shift.setInput("work the case");
        List<String> outcomes = JobDispatcher.getInstance().submit(shift).get();
        assertEquals(5, outcomes.size(), "one outcome per attempted note");
        assertEquals("filed on C-100/A-1005 via email: address confirmed with the customer", outcomes.get(0),
                "before the narrowing, any order of the case is workable");
        assertEquals("filed on C-100/A-1002 via email: customer disputes the second charge", outcomes.get(1),
                "the disputed order is inside every binding");
        assertEquals("refused: Scope mismatch: this flow is bound to OrderScope[C-100/A-1002]"
                        + " but the input names OrderScope[C-100/A-1005]", outcomes.get(2),
                "the same case's other order is refused by the narrowing");
        assertEquals("refused: Scope mismatch: this flow is bound to ChannelScope[channel=email]"
                        + " but the input names ChannelScope[channel=phone]", outcomes.get(3),
                "the channel binding inherited from the shift still holds in the dispute");
        assertEquals("refused: Scope mismatch: this flow is bound to OrderScope[C-100/A-1002]"
                        + " but the input names OrderScope[C-200/A-1003]", outcomes.get(4),
                "another customer is refused on the case axis, which the order binding carries with it");
    }
}
