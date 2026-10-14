/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.harness.tokens.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The summarizer holds no reservation and makes no call of its own: every exchange is a
 * {@link SummaryCall} it submits and waits on. Text that fits the resolved model is one call;
 * text that does not is chunked by that model's tokenizer to the share the call accepts, on
 * paragraph, sentence, word and finally character boundaries so any text yields pieces that
 * fit, each chunk summarized at SHORT on that same model, and the combined summaries
 * summarized again at the requested size, which the output's chunking levels count. A call
 * that fails over text within the share fails the summarizer with that failure, unchunked.
 * Driven through the dispatcher, with the calls answering from a canned client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class SummarizationToolChunkingTest {
    private static final Identifiable ROOT = Job.workflow("summarization-chunking-test", "summarization-chunking-test");

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    /** Answers with the size phrase it was asked for, so the test can tell a chunk summary from the final one. */
    static class EchoingClient extends AbstractLLMClient<String> {
        EchoingClient(ModelSpec model) {
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
            // the instruction line names the size; the text below it may quote earlier answers
            String instruction = request.getContext().getLastOutgoingMessage().getRawContent().lines().findFirst().orElseThrow();
            String phrase = Arrays.stream(SummarySize.values())
                    .map(SummarizationTool::sizeToPhrase)
                    .filter(instruction::contains)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("a summarization prompt names its size as a phrase"));
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(LLMStopReason.END_TURN);
            response.getResponseMessage().overwriteRawContent("SUMMARY(" + phrase + ")");
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

    /** Fails every exchange the way a provider that is down would, whatever the text. */
    static final class FailingClient extends EchoingClient {
        static final String FAILURE = "the provider is down for this test";

        FailingClient(ModelSpec model) {
            super(model);
        }

        @Override
        protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
            throw new IllegalStateException(FAILURE);
        }
    }

    /** The summarizer with its calls answering from the canned client, every call kept for the assertions. */
    static final class TestableSummarizer extends SummarizationTool {
        final List<SummaryCall> calls = Collections.synchronizedList(new ArrayList<>());
        Function<ModelSpec, LLMClient> clients = EchoingClient::new;

        TestableSummarizer() {
            super(ROOT);
        }

        @Override
        SummaryCall newCall(Identifiable parent, ModelSpec model, String text, SummarySize size, String textContext) {
            SummaryCall call = new SummaryCall(parent, getGrade(), model, text, size, textContext) {
                @Override
                protected LLMClient client(JobResources resources) {
                    return clients.apply(model());
                }
            };
            calls.add(call);
            return call;
        }
    }

    private static StandardModelSpec modelWithWindow(int maxContextTokens) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("chunking-fake");
        spec.setIdentity("chunking-fake");
        spec.setProviderKey("openai");
        spec.setWireModelId("chunking-fake");
        spec.setGrade(Grade.SMALL);
        spec.setMaxContextTokens(maxContextTokens);
        spec.setMaxOutputTokens(8_000);
        spec.setTpm(1_000_000);
        spec.setRpm(10_000);
        return spec;
    }

    private static SummarizationInput input(String text, SummarySize size) {
        SummarizationInput input = new SummarizationInput();
        input.setText(text);
        input.setSize(size);
        input.setContext("test corpus");
        return input;
    }

    @Test
    void theSummarizerIsADoerHoldingNoReservation_itsCallsDo() {
        TestableSummarizer tool = new TestableSummarizer();
        tool.setInput(input("short", SummarySize.BRIEF));
        assertTrue(tool instanceof ModelDependentTool<?, ?>, "the summarizer keeps its seat: a grade and a pin for its calls");
        assertTrue(tool instanceof AbstractDoer<?, ?>, "a doer, so it may submit its calls and wait on them");
        assertNull(tool.getRequirements(), "the tool reserves nothing");
        assertNull(tool.getTimeout(), "the tool has no guard of its own");
        SummaryCall call = tool.newCall(ROOT, null, "short", SummarySize.BRIEF, "test corpus");
        assertEquals(1, call.getRequirements().getModelBindings().size(), "its calls reserve, each for exactly the text it sends");
        assertEquals(java.time.Duration.ofMinutes(6), call.getTimeout(), "and each call carries the 6-minute hung-call guard");
    }

    @Test
    void theCascadeCutsAnyTextOverTheShareIntoPiecesThatFit_byTheModelsTokenizer() {
        StandardModelSpec model = modelWithWindow(1_000);
        int share = SummaryCall.maxTextTokens(model);
        TokenCounter counter = model.tokenCounter();
        String paragraphs = ("one paragraph about the matter at hand. ".repeat(40) + "\n\n").repeat(10);
        String sentences = "a sentence about the matter at hand with no blank line anywhere. ".repeat(100);
        String words = "word ".repeat(1_600);
        String run = "一二三四".repeat(600);
        for (String text : List.of(paragraphs, sentences, words, run)) {
            assertTrue(counter.countTokens(text) > share, "the fixture exceeds the share");
            List<String> chunks = SummarizationTool.splitIntoChunks(text, share, counter);
            assertTrue(chunks.size() >= 2, "a text over the share yields two or more chunks: " + chunks.size());
            for (String chunk : chunks) {
                assertTrue(counter.countTokens(chunk) <= share, "every chunk fits the share by the model's tokenizer: " + counter.countTokens(chunk));
            }
            assertEquals(text.replaceAll("\\s+", ""), String.join("", chunks).replaceAll("\\s+", ""), "nothing but whitespace is lost in the cut");
        }
    }

    @Test
    void textThatFitsIsOneCall() throws Exception {
        StandardModelSpec model = modelWithWindow(100_000);
        TestableSummarizer tool = new TestableSummarizer();
        tool.pinModel(model);
        tool.setInput(input("A short text about nothing in particular.", SummarySize.BRIEF));
        SummarizationOutput output = JobDispatcher.getInstance().submit(tool).get();
        assertEquals("SUMMARY(" + SummarizationTool.sizeToPhrase(SummarySize.BRIEF) + ")", output.getSummary(), "the answer at the requested size");
        assertEquals(1, output.getChunkingLevels(), "one level: the text fit in one call");
        assertEquals(1, tool.calls.size(), "one call for text that fits");
        assertSame(model, tool.calls.getFirst().pinnedModel(), "the tool's pin reaches its call");
    }

    @Test
    void aFailedCallOverTextWithinTheShareIsRethrownAsItCame_neverChunked() {
        StandardModelSpec model = modelWithWindow(100_000);
        TestableSummarizer tool = new TestableSummarizer();
        tool.clients = FailingClient::new;
        tool.pinModel(model);
        tool.setInput(input("A short text about nothing in particular.", SummarySize.BRIEF));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(tool).get(),
                "the summarizer fails as its call did");
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(cause.getMessage());
        }
        assertTrue(messages.contains(FailingClient.FAILURE), "the call's own failure is what surfaces: " + messages);
        assertEquals(1, tool.calls.size(), "text within the share is never chunked over a failure that is not the text's");
    }

    @Test
    void textOverTheModelsShareIsChunkedAtShort_thenCombinedAtTheRequestedSize() throws Exception {
        // a window wide enough that a chunk at the share plus the SHORT backstop still prices
        // under it; the text is cut to the share, so the test needs text past two shares
        StandardModelSpec model = modelWithWindow(20_000);
        int share = SummaryCall.maxTextTokens(model);
        StringBuilder text = new StringBuilder();
        for (int paragraph = 0; paragraph < 450; paragraph++) {
            text.append("paragraph ").append(paragraph).append(" says something about the matter at hand. ".repeat(8)).append("\n\n");
        }
        int textTokens = model.tokenCounter().countTokens(text.toString());
        assertTrue(textTokens > 2 * share, "the fixture needs at least three chunks: " + textTokens + " tokens against a share of " + share);
        TestableSummarizer tool = new TestableSummarizer();
        tool.pinModel(model);
        tool.setInput(input(text.toString(), SummarySize.PARAGRAPHS));
        SummarizationOutput output = JobDispatcher.getInstance().submit(tool).get();
        assertEquals("SUMMARY(" + SummarizationTool.sizeToPhrase(SummarySize.PARAGRAPHS) + ")", output.getSummary(),
                "the final summary is at the requested size");
        assertEquals(2, output.getChunkingLevels(), "two levels: the chunks, then their combination");
        List<SummaryCall> calls = tool.calls;
        SummaryCall refused = calls.getFirst();
        assertEquals(SummarizationTool.buildPrompt(text.toString(), SummarySize.PARAGRAPHS, "test corpus"),
                refused.getRequirements().getModelBindings().getFirst().getConversation().getLastOutgoingMessage().getRawContent(),
                "the first call carries the whole text, and its refusal is what decides to chunk");
        List<SummaryCall> chunks = calls.subList(1, calls.size() - 1);
        assertTrue(chunks.size() >= 3, "the text was cut into chunks: " + chunks.size());
        for (SummaryCall chunk : chunks) {
            // the call ran, so the answer follows the prompt: the prompt is the first message
            String prompt = chunk.getRequirements().getModelBindings().getFirst().getConversation().getMessages().getFirst().getRawContent();
            assertTrue(prompt.contains(SummarizationTool.sizeToPhrase(SummarySize.SHORT)), "every chunk is summarized at SHORT");
            assertSame(model, chunk.pinnedModel(), "on the model whose measure cut it");
        }
        SummaryCall combine = calls.getLast();
        String combinePrompt = combine.getRequirements().getModelBindings().getFirst().getConversation().getMessages().getFirst().getRawContent();
        assertTrue(combinePrompt.contains(SummarizationTool.sizeToPhrase(SummarySize.PARAGRAPHS)), "the combined summaries are summarized at the requested size");
        assertTrue(combinePrompt.contains("SUMMARY(" + SummarizationTool.sizeToPhrase(SummarySize.SHORT) + ")"), "over the chunk summaries");
    }
}
