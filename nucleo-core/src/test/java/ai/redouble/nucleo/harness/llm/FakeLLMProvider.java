/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

/**
 * Test-only LLM provider that is registered and declares a credential but builds no client:
 * enough for tests of what a picker does with a catalog entry whose provider is on the
 * classpath - the credential check, the refusal that names the credential - without a
 * provider artifact on the test classpath. Discovered by the classpath scan like any provider.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class FakeLLMProvider extends AbstractClientProvider<LLMClient> {
    @Override
    public String key() {return "fake-llm";}

    @Override
    public String platform() {return "fake";}
    @Override
    public String credentialId() {return "fake-llm-key";}
    @Override
    protected LLMClient newClient() {
        throw new UnsupportedOperationException("the test LLM provider builds no client");
    }
}
