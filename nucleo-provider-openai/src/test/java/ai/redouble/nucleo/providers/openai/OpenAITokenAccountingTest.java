/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI's counting as the provider module states it: the usage ledger takes the prompt count
 * as the total and the cached count as its subset, with cache writes at zero; the estimator
 * counts a replayed reasoning item and nothing for a thinking block; OpenAI's own endpoints
 * count on {@code o200k_base} while a compatible endpoint counts on the neutral encoding; and
 * what is counted is the rendering the OpenAI client sends.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
class OpenAITokenAccountingTest {

    private static StandardModelSpec gpt(String providerKey) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("gpt-test-" + providerKey);
        spec.setIdentity("gpt-test");
        spec.setProviderKey(providerKey);
        spec.setWireModelId("gpt-test");
        spec.setMaxContextTokens(200_000);
        spec.setMaxOutputTokens(8_000);
        spec.setGrade(Grade.SMALL);
        return spec;
    }

    private static ConversationContext conversation(ModelSpec spec) {
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(spec));
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> ask = new OutgoingMessage<>(StringResponseHandler.instance);
        ask.setRole("user");
        ask.addText("plan the migration");
        conversation.getMessages().add(ask);
        return conversation;
    }

    @Test
    void theLedgerTakesThePromptAsTheTotalAndTheCachedCountAsItsSubset() {
        TokenLedger usage = new OpenAITokenLedger(gpt("openai"), 120, 100, 30);
        assertEquals(120, usage.inputTokens(), "prompt_tokens is the whole input");
        assertEquals(100, usage.cacheReadTokens());
        assertEquals(0, usage.cacheWriteTokens(), "OpenAI bills no cache writes");
        assertEquals(20, usage.uncachedInputTokens());
        assertEquals(30, usage.outputTokens());
        TokenLedger uncached = new OpenAITokenLedger(gpt("openai"), 120, null, 30);
        assertNull(uncached.cacheReadTokens(), "a response without the cached detail leaves the facet null");
        assertNull(uncached.uncachedInputTokens());
    }

    @Test
    void aReplayedReasoningItemCounts_aThinkingBlockDoesNot() {
        ModelSpec gpt = gpt("openai");
        ConversationContext plain = conversation(gpt);
        IncomingMessage<String> answer = new IncomingMessage<>(StringResponseHandler.instance);
        answer.setContentBlocks(List.of(new TextBlock("done")));
        answer.overwriteRawContent("done");
        plain.getMessages().add(answer);
        int base = OpenAIContextEstimator.OPENAI.estimate(plain, gpt).inputTokens();
        ConversationContext reasoning = conversation(gpt);
        IncomingMessage<String> reasoned = new IncomingMessage<>(StringResponseHandler.instance);
        reasoned.setContentBlocks(List.of(new RedactedThinkingBlock("{\"type\":\"reasoning\",\"encrypted_content\":\"" + "x".repeat(4_000) + "\"}"), new TextBlock("done")));
        reasoned.overwriteRawContent("done");
        reasoning.getMessages().add(reasoned);
        assertTrue(OpenAIContextEstimator.OPENAI.estimate(reasoning, gpt).inputTokens() > base + 100,
                "the reasoning item is replayed on the next turn, so it is counted");
        ConversationContext thinking = conversation(gpt);
        IncomingMessage<String> thought = new IncomingMessage<>(StringResponseHandler.instance);
        thought.setContentBlocks(List.of(new ThinkingBlock("x".repeat(4_000), "sig"), new TextBlock("done")));
        thought.overwriteRawContent("done");
        thinking.getMessages().add(thought);
        assertEquals(base, OpenAIContextEstimator.OPENAI.estimate(thinking, gpt).inputTokens(),
                "a thinking block is never sent to OpenAI, so it costs nothing");
    }

    @Test
    void openAIsOwnEndpointsCountOnO200k_aCompatibleEndpointOnTheNeutralEncoding() {
        assertSame(OpenAIContextEstimator.OPENAI, new OpenAIProvider().contextEstimator(gpt("openai")));
        assertSame(OpenAIContextEstimator.OPENAI, new OpenAIChatCompletionsProvider().contextEstimator(gpt("openai-chat-completions")));
        assertSame(OpenAIContextEstimator.OPENAI, new AzureFoundryOpenAIProvider().contextEstimator(gpt("azure-foundry-openai")));
        assertSame(OpenAIContextEstimator.COMPATIBLE, new OpenAICompatibleProvider().contextEstimator(gpt("openai-compatible")));
        assertSame(OpenAIContextEstimator.O200K_COUNTER, new OpenAIProvider().tokenCounter(gpt("openai")));
        assertSame(TiktokenCounter.DEFAULT, new OpenAICompatibleProvider().tokenCounter(gpt("openai-compatible")));
    }

    @Test
    void anImageCostsTheNeutralFloor_andTheSpecReachesTheEstimatorByKey() {
        String smallImage = java.util.Base64.getEncoder().encodeToString(new byte[2 * 1024]);
        assertEquals(85, OpenAIContextEstimator.O200K_COUNTER.countImageTokens(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ImageBlock(smallImage, "image/png", null)),
                "an image costs at least 85 on OpenAI's own counter");
        StandardModelSpec openai = new StandardModelSpec();
        openai.setId("gpt-test");
        openai.setIdentity("gpt-test");
        openai.setProviderKey("openai");
        openai.setWireModelId("gpt-test");
        openai.setMaxContextTokens(200_000);
        openai.setMaxOutputTokens(8_000);
        assertSame(OpenAIContextEstimator.OPENAI, openai.contextEstimator(), "the spec finds the openai provider by key");
        assertSame(OpenAIContextEstimator.O200K_COUNTER, openai.tokenCounter());
        assertSame(OpenAIContextEstimator.O200K_COUNTER, new OpenAIChatCompletionsProvider().tokenCounter(openai), "the Chat Completions dialect counts on o200k too");
        assertSame(OpenAIContextEstimator.O200K_COUNTER, new AzureFoundryOpenAIProvider().tokenCounter(openai), "and so does Azure Foundry");
        assertSame(TiktokenCounter.DEFAULT, new OpenAIEmbeddingsProvider().tokenCounter(openai), "the embeddings providers count on the neutral encoding");
        assertSame(TiktokenCounter.DEFAULT, new AzureOpenAIEmbeddingsProvider().tokenCounter(openai));
        assertSame(TiktokenCounter.DEFAULT, new OpenAICompatibleEmbeddingsProvider().tokenCounter(openai));
    }

    @Test
    void theEstimateRendersWhatTheOpenAIClientSends() {
        ModelSpec gpt = gpt("openai");
        ConversationContext conversation = conversation(gpt);
        conversation.putMainObjective("task", "answer briefly");
        OpenAIContentFormatter formatter = new OpenAIContentFormatter();
        boolean thinkingActive = ThinkingMode.thinkingActive(gpt, conversation.resolveDepth());
        assertEquals(conversation.prepareMessagesForLLM(formatter, thinkingActive), conversation.render(formatter, thinkingActive),
                "the provider's estimator counts the payload the provider's client carries");
    }
}
