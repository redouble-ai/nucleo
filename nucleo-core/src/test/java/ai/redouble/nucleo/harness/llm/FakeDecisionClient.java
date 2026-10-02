/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.models.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * The client of {@link FakeDecisionProvider}: answers every request from the policy the
 * test installs and keeps every request it saw, so a test reads back what the model was
 * asked, turn by turn.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class FakeDecisionClient implements DecisionClient {
    /** The policy the client answers with; installed by the test, read by every call. */
    public static volatile Function<DecisionRequest, Map<String, Answer>> policy;
    /** Every request answered, in order, for the test to read back. */
    public static final List<DecisionRequest> seen = Collections.synchronizedList(new ArrayList<>());
    private ModelSpec model;

    @Override
    public DecisionResponse decide(DecisionRequest request) throws IOException, InterruptedException {
        Function<DecisionRequest, Map<String, Answer>> current = policy;
        if (current == null) {
            throw new IOException("the fake decision client has no policy installed");
        }
        seen.add(request);
        DecisionResponse response = new DecisionResponse(model, request);
        response.setStartTime(Instant.now());
        response.setAnswers(current.apply(request));
        response.setActualInputTokens(SystemOneWire.encode(model.getWireModelId(), request).length() / 4);
        response.setEndTime(Instant.now());
        response.setSuccessful(true);
        return response;
    }

    @Override
    public ModelSpec getModel() {return model;}

    @Override
    public void setModel(ModelSpec model) {this.model = model;}
}
