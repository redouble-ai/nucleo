/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A probe call is a model-dependent tool pinned to the entry under probe, with what is a
 * probe's own: no transparent retries, the cap it is given as its timeout, no transaction, a
 * client built straight from the entry's provider for the one call and closed after it, and the
 * provider's response kept for the harvest.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class ProbeCallTest {
    private static final Identifiable ROOT = Job.workflow("probe-call-test", "probe-call-test");

    /** A client that answers one text and records whether it was closed. */
    static final class CannedClient extends AbstractLLMClient<String> {
        private final String rawContent;
        boolean closed;

        CannedClient(String rawContent, ModelSpec model) {
            this.rawContent = rawContent;
            setModel(model);
        }

        @Override
        public APIDialect getDialect() {
            return APIDialect.BEDROCK_CONVERSE;
        }

        @Override
        protected TextWrapper<String> textWrapper() {
            return text -> text;
        }

        @Override
        protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(LLMStopReason.END_TURN);
            response.setServedModelId("served-" + getModel().getId());
            response.getResponseMessage().overwriteRawContent(rawContent);
            return response;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        protected boolean is429Error(Exception e) {return false;}

        @Override
        protected boolean isServerError(Exception e) {return false;}

        @Override
        protected boolean isOverloadError(Exception e) {return false;}

        @Override
        protected RateLimitInfo extractRateLimitInfo(Exception e) {return null;}
    }

    /** A provider that builds the given client for its one entry. */
    static final class StubProvider implements ClientProvider<Client> {
        private final Client client;

        StubProvider(Client client) {
            this.client = client;
        }

        @Override
        public String key() {
            return "stub";
        }

        @Override
        public Client createClient(ModelSpec spec) {
            return client;
        }

        @Override
        public String credentialId() {
            return "stub-key";
        }

        @Override
        public String platform() {
            return "stub";
        }
    }

    private static StandardModelSpec entry() {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("probe-fake");
        spec.setIdentity("probe-fake");
        spec.setProviderKey("stub");
        spec.setWireModelId("probe-fake");
        spec.setGrade(Grade.SMALL);
        spec.setMaxContextTokens(100_000);
        spec.setMaxOutputTokens(8_000);
        spec.setTpm(1_000_000);
        spec.setRpm(10_000);
        return spec;
    }

    @Test
    void aProbeIsAModelDependentToolPinnedToItsEntry_withNoRetriesAndItsCap() {
        StandardModelSpec spec = entry();
        ProbeCall call = new ProbeCall(ROOT, new StubProvider(new CannedClient("pong", spec)), spec, Depth.IMMEDIATE, 2048, "Reply with the single word: pong", Duration.ofMinutes(2));
        assertTrue(call instanceof AbstractModelDependentTool<?, ?>, "a probe converses through the one door every exchange goes through");
        assertSame(spec, call.pinnedModel(), "the probe runs on the entry under probe, never on a pick");
        assertEquals(0, call.getUpstreamRetries(), "a probe asks whether the call succeeds NOW: an upstream signal fails it at once");
        assertEquals(Duration.ofMinutes(2), call.getTimeout(), "the cap is the probe's timeout");
        assertEquals(JobType.UTILITY, call.getJobType());
        assertFalse(call.getRequirements().requiresTransaction(), "a probe touches no database");
        ModelBinding binding = call.getRequirements().getModelBindings().getFirst();
        assertTrue(binding.isPinned());
        assertEquals(Depth.IMMEDIATE, binding.getDepth(), "the depth the probe was given");
        ConversationContext conversation = binding.getConversation();
        assertEquals(OutputDeclaration.of(2048), conversation.getOutputDeclaration(), "the output budget the probe was given");
        assertEquals("Reply with the single word: pong", conversation.getLastOutgoingMessage().getRawContent(), "the one message the probe sends");
        assertEquals(String.class, conversation.getLastOutgoingMessage().getResponseHandler().getResponseClass(), "answered as plain text");
    }

    @Test
    void theClientComesStraightFromTheProvider_andIsClosedAfterTheCall() throws Exception {
        StandardModelSpec spec = entry();
        CannedClient client = new CannedClient("pong", spec);
        ProbeCall call = new ProbeCall(ROOT, new StubProvider(client), spec, Depth.IMMEDIATE, 2048, "Reply with the single word: pong", Duration.ofMinutes(2));
        call.getRequirements().getModelBindings().getFirst().resolve(spec);
        assertEquals("pong", call.execute(null, null), "the probe's answer is the model's text");
        assertTrue(client.closed, "the client built for the call is closed after it");
        assertEquals("served-probe-fake", call.response().getServedModelId(), "the provider's envelope is kept for the harvest");
    }

    @Test
    void aProviderThatBuildsNoChatClientForTheEntryIsRefusedByName() {
        StandardModelSpec spec = entry();
        Client notAChatClient = new Client() {
            @Override
            public ModelSpec getModel() {
                return spec;
            }

            @Override
            public void setModel(ModelSpec model) {
            }
        };
        ProbeCall call = new ProbeCall(ROOT, new StubProvider(notAChatClient), spec, Depth.IMMEDIATE, 2048, "ping", Duration.ofMinutes(2));
        call.getRequirements().getModelBindings().getFirst().resolve(spec);
        UncorrectableRuntimeLLMException refused = assertThrows(UncorrectableRuntimeLLMException.class, () -> call.execute(null, null));
        assertTrue(refused.getMessage().contains("probe-fake"), refused.getMessage());
    }
}
