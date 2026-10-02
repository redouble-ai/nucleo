/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import java.util.function.*;

/**
 * Test-only decision provider whose client answers from a policy the test installs (see
 * {@link FakeDecisionClient}), so a decision loop runs through the dispatcher end to end
 * against a model that decides the way the test says, and whose {@link #serves} answer is
 * the test's too, the way a real decision provider answers from its endpoint's listing.
 * Discovered by the classpath scan like any provider.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class FakeDecisionProvider extends AbstractClientProvider<FakeDecisionClient> {
    /** Which wire ids the fake endpoint serves; everything unless a test says otherwise. */
    public static volatile Predicate<String> served = wireModelId -> true;

    @Override
    public boolean serves(String wireModelId) {return served.test(wireModelId);}

    @Override
    public String key() {return "fake-decision";}

    @Override
    public String platform() {return "fake";}

    @Override
    public String credentialId() {return "fake-decision-key";}

    @Override
    public boolean configured() {return true;}

    @Override
    protected FakeDecisionClient newClient() {return new FakeDecisionClient();}
}
