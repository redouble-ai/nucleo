/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One summarization exchange is a model-dependent tool: it wires the summarization prompt at
 * the size's backstop and IMMEDIATE depth, at its grade or pinned to the entry it is given;
 * text over the resolved model's share, the window less the 20% reserve, is refused as
 * {@code TokenEstimateExceedsLimitException} before anything is sent; text that fits is
 * answered as plain text, trimmed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class SummaryCallTest {
    private static final Identifiable ROOT = Job.workflow("summary-call-test", "summary-call-test");

    /** A client that answers a fixed text and counts its calls. */
    static final class CannedClient extends AbstractLLMClient<String> {
        private final String rawContent;
        int calls;

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
            calls++;
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(LLMStopReason.END_TURN);
            response.getResponseMessage().overwriteRawContent(rawContent);
            return response;
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

    /** A call resolved by hand onto the given model and answered by the canned client. */
    static final class TestableCall extends SummaryCall {
        private final CannedClient client;
        private final ModelSpec model;

        TestableCall(ModelSpec model, CannedClient client, String text, SummarySize size) {
            super(ROOT, Grade.SMALL, null, text, size, "a test note");
            this.model = model;
            this.client = client;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = super.getRequirements();
            req.getModelBindings().getFirst().resolve(model);
            return req;
        }

        @Override
        protected LLMClient client(JobResources resources) {
            return client;
        }
    }

    private static StandardModelSpec modelWithWindow(int maxContextTokens) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("summary-fake");
        spec.setIdentity("summary-fake");
        spec.setProviderKey("openai");
        spec.setWireModelId("summary-fake");
        spec.setGrade(Grade.SMALL);
        spec.setMaxContextTokens(maxContextTokens);
        spec.setMaxOutputTokens(8_000);
        spec.setTpm(1_000_000);
        spec.setRpm(10_000);
        return spec;
    }

    @Test
    void aCallIsAModelDependentToolWiringTheSummarizationPrompt() {
        SummaryCall call = new SummaryCall(ROOT, Grade.SMALL, null, "the text", SummarySize.SHORT, "a test note");
        assertTrue(call instanceof AbstractModelDependentTool<?, ?>, "every exchange with a model is a model-dependent tool");
        assertEquals(JobType.UTILITY, call.getJobType(), "a summary is the runtime's own utility work");
        assertEquals(java.time.Duration.ofMinutes(6), call.getTimeout(), "a hung-call guard for one LLM call");
        assertNull(call.pinnedModel(), "without an entry the call resolves its grade");
        ModelBinding binding = call.getRequirements().getModelBindings().getFirst();
        assertNull(call.model(), "no model before the seat resolves: the summarizer reads null when the call never ran");
        assertEquals(Grade.SMALL, binding.getGrade(), "the call resolves at the summarizer's grade");
        assertEquals(Depth.IMMEDIATE, binding.getDepth(), "a summary is an IMMEDIATE answer");
        ConversationContext conversation = binding.getConversation();
        assertEquals(OutputDeclaration.of(SummarizationTool.sizeToBackstopTokens(SummarySize.SHORT)), conversation.getOutputDeclaration(),
                "the size's backstop is the answer's max_tokens");
        assertEquals(SummarizationTool.buildPrompt("the text", SummarySize.SHORT, "a test note"),
                conversation.getLastOutgoingMessage().getRawContent(), "the message is the summarization prompt at the size");
        assertEquals(String.class, conversation.getLastOutgoingMessage().getResponseHandler().getResponseClass(),
                "the answer is plain text");
    }

    @Test
    void aCallGivenAnEntryIsPinnedToIt() {
        StandardModelSpec entry = modelWithWindow(10_000);
        SummaryCall call = new SummaryCall(ROOT, Grade.SMALL, entry, "the text", SummarySize.BRIEF, null);
        assertSame(entry, call.pinnedModel(), "a chunk runs on the model whose measure cut it");
        ModelBinding binding = call.getRequirements().getModelBindings().getFirst();
        assertTrue(binding.isPinned(), "the binding is the pin, never a pick");
    }

    @Test
    void textOverTheModelsShareIsRefusedBeforeAnythingIsSent() throws Exception {
        StandardModelSpec model = modelWithWindow(1_000);
        int share = SummaryCall.maxTextTokens(model);
        assertEquals(800, share, "the share is the window less the 20% reserve");
        String text = "word ".repeat(2_000);
        CannedClient client = new CannedClient("never", model);
        TestableCall call = new TestableCall(model, client, text, SummarySize.SHORT);
        call.getRequirements();
        TokenEstimateExceedsLimitException refused = assertThrows(TokenEstimateExceedsLimitException.class, () -> call.execute(null, null),
                "text the model cannot take in one call fails the call as a pre-flight estimate");
        assertEquals(share, refused.getModelLimit(), "the refusal names the share the summarizer chunks to");
        assertEquals(model.tokenCounter().countTokens(text), refused.getEstimatedTokens(), "and the text's size under the model's tokenizer");
        assertEquals(0, client.calls, "nothing is sent for text that does not fit");
        assertSame(model, call.model(), "the model that refused is the one the summarizer chunks by");
    }

    @Test
    void textThatFitsIsSummarizedAndTrimmed() throws Exception {
        StandardModelSpec model = modelWithWindow(100_000);
        CannedClient client = new CannedClient("  the summary \n", model);
        TestableCall call = new TestableCall(model, client, "a short text", SummarySize.BRIEF);
        call.getRequirements();
        assertEquals("the summary", call.execute(null, null), "the answer is the model's text, trimmed");
        assertEquals(1, client.calls);
    }
}
