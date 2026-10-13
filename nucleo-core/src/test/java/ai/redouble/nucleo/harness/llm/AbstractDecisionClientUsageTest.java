/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a decision call records as usage: the input tokens the endpoint reported and zero
 * output, since a decision generates nothing, with no cache facets; an endpoint that reports
 * no count leaves the response without a ledger, so nothing downstream bills the call at zero.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-12)
 */
class AbstractDecisionClientUsageTest {

    static class FixedDecisionClient extends AbstractDecisionClient {
        private final Integer inputTokens;

        FixedDecisionClient(Integer inputTokens) {this.inputTokens = inputTokens;}

        @Override
        protected RawDecision doDecide(DecisionRequest request) {
            return new RawDecision(new SystemOneWire.Decoded(Map.of(), inputTokens, null), "req-1");
        }

        @Override
        protected boolean is429Error(Exception e) {return false;}

        @Override
        protected RateLimitInfo extractRateLimitInfo(Exception e) {return null;}
    }

    private static StandardModelSpec spec() {
        StandardModelSpec s = new StandardModelSpec();
        s.setId("decision-test");
        s.setIdentity("decision-test");
        s.setProviderKey("fake-decision");
        s.setWireModelId("decision-test");
        s.setMaxContextTokens(16_384);
        s.setMaxOutputTokens(1);
        s.setTpm(1000);
        s.setRpm(100);
        return s;
    }

    private static DecisionRequest request() {
        return new DecisionRequest("a ticket about a failed payout", Map.of("ok", Noul.of("Is the ticket about a payout?")));
    }

    @Test
    void theUsageIsTheReportedInputAndZeroOutput() throws IOException, InterruptedException {
        FixedDecisionClient client = new FixedDecisionClient(321);
        client.setModel(spec());
        DecisionResponse response = client.decide(request());
        assertEquals(321, response.getUsage().inputTokens(), "what the endpoint billed");
        assertEquals(0, response.getUsage().outputTokens(), "a decision generates nothing");
        assertNull(response.getUsage().cacheReadTokens(), "a facet the endpoint does not report is null");
        assertEquals("req-1", response.getProviderRequestId());
    }

    @Test
    void anEndpointThatReportsNoCountLeavesTheResponseWithoutALedger() throws IOException, InterruptedException {
        FixedDecisionClient client = new FixedDecisionClient(null);
        client.setModel(spec());
        DecisionResponse response = client.decide(request());
        assertNull(response.getUsage(), "no usage record, no ledger, so nothing downstream bills the call at zero");
        assertTrue(response.isSuccessful());
    }
}
