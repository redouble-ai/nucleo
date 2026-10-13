/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bedrock's counting as the provider module states it: the Converse usage ledger sums the
 * input, cache-write and cache-read counters into the total the model read and leaves a counter
 * Converse omits null; the estimator follows the family the wire id names, Claude at 1.10 with
 * an image floor of 1600, Cohere at 1.20, anything else at the neutral encoding; it counts the
 * generic rendering the Converse client sends; the Converse provider hands out that estimator
 * and counter, and the Cohere embeddings provider counts at Cohere's density through both of its
 * doors.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-12)
 */
class BedrockTokenAccountingTest {

    private static StandardModelSpec spec(String wireModelId, String providerKey) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(wireModelId + "-test");
        spec.setIdentity(wireModelId);
        spec.setProviderKey(providerKey);
        spec.setWireModelId(wireModelId);
        spec.setMaxContextTokens(200_000);
        spec.setMaxOutputTokens(8_000);
        spec.setGrade(Grade.SMALL);
        return spec;
    }

    private static final String TEXT = "The quick brown fox jumps over the lazy dog. ".repeat(20);

    @Test
    void theLedgerSumsTheConverseCountersIntoTheTotalTheModelRead() {
        ModelSpec nova = spec("amazon.nova-pro-v1:0", "bedrock-converse");
        TokenLedger usage = new BedrockTokenLedger(nova, 100, 20, 30, 5);
        assertEquals(150, usage.inputTokens(), "input plus cache writes plus cache reads");
        assertEquals(20, usage.cacheWriteTokens());
        assertEquals(30, usage.cacheReadTokens());
        assertEquals(100, usage.uncachedInputTokens());
        assertEquals(5, usage.outputTokens());
        TokenLedger uncached = new BedrockTokenLedger(nova, 100, null, null, 5);
        assertEquals(100, uncached.inputTokens(), "counters Converse left out add nothing");
        assertNull(uncached.cacheReadTokens(), "and stay null as facets");
        assertNull(new BedrockTokenLedger(nova, null, 20, 30, 5).inputTokens(), "no input count, no total");
    }

    @Test
    void theEstimatorFollowsTheFamilyTheWireIdNames() {
        assertSame(BedrockContextEstimator.CLAUDE_COUNTER, BedrockContextEstimator.counterFor(spec("anthropic.claude-sonnet-4-5-20250929-v1:0", "bedrock-converse")));
        assertSame(BedrockContextEstimator.COHERE_COUNTER, BedrockContextEstimator.counterFor(spec("cohere.embed-v4:0", "bedrock-cohere-embeddings")));
        assertSame(TiktokenCounter.DEFAULT, BedrockContextEstimator.counterFor(spec("amazon.nova-pro-v1:0", "bedrock-converse")));
        assertSame(BedrockContextEstimator.CLAUDE_COUNTER, BedrockContextEstimator.forSpec(spec("anthropic.claude-sonnet-4-5-20250929-v1:0", "bedrock-converse")).counter(),
                "the estimator for a family counts with that family's counter");
        assertSame(TiktokenCounter.DEFAULT, BedrockContextEstimator.forSpec(spec("meta.llama3-70b-instruct-v1:0", "bedrock-converse")).counter());
    }

    @Test
    void theFamilyDensitiesAndFloorsAreAsStated() {
        int neutral = TiktokenCounter.DEFAULT.countTokens(TEXT);
        assertEquals((int)(neutral * 1.10), BedrockContextEstimator.CLAUDE_COUNTER.countTokens(TEXT), "Claude at 1.10 on the same encoding");
        assertEquals((int)(neutral * 1.20), BedrockContextEstimator.COHERE_COUNTER.countTokens(TEXT), "Cohere at 1.20 on the same encoding");
        String smallImage = Base64.getEncoder().encodeToString(new byte[2 * 1024]);
        assertEquals(1600, BedrockContextEstimator.CLAUDE_COUNTER.countImageTokens(new ImageBlock(smallImage, "image/png", null)), "Claude's image floor");
        assertEquals(85, BedrockContextEstimator.COHERE_COUNTER.countImageTokens(new ImageBlock(smallImage, "image/png", null)), "the neutral image floor elsewhere");
    }

    @Test
    void theEstimateCountsTheGenericRendering_whichIsWhatTheConverseClientSends() {
        ModelSpec nova = spec("amazon.nova-pro-v1:0", "bedrock-converse");
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(nova));
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        conversation.putMainObjective("task", "answer briefly");
        OutgoingMessage<String> ask = new OutgoingMessage<>(StringResponseHandler.instance);
        ask.setRole("user");
        ask.addText("plan the migration");
        conversation.getMessages().add(ask);
        IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
        call.setContentBlocks(List.of(new TextBlock("looking"), new ToolUseBlock("t1", "search", "{\"q\":\"ports\"}")));
        call.overwriteRawContent("looking");
        conversation.getMessages().add(call);
        OutgoingMessage<String> result = new OutgoingMessage<>(StringResponseHandler.instance);
        result.setRole("user");
        result.addToolResult("t1", "{\"hits\":3}", false);
        conversation.getMessages().add(result);
        BedrockConverseClient client = new BedrockConverseClient();
        assertInstanceOf(GenericContentFormatter.class, client.getFormatter(), "the Converse client renders with the generic formatter");
        var encoders = client.buildEncoders();
        assertEquals(ToolDefinitionBlockEncoder.class, encoders.get(ToolDefinitionBlock.class), "a tool definition goes to Converse in the generic text form");
        assertEquals(ToolUseBlockEncoder.class, encoders.get(ToolUseBlock.class), "a tool call goes to Converse in the generic text form");
        assertEquals(ToolResultBlockEncoder.class, encoders.get(ToolResultBlock.class), "a tool result goes to Converse in the generic text form");
        assertEquals(ThinkingBlockEncoder.class, encoders.get(ThinkingBlock.class), "thinking goes through the generic encoder, which sends nothing");
        assertNull(new ThinkingBlockEncoder<>().encode(new ThinkingBlock("reasoning", "sig")), "and nothing is what it sends");
        GenericContentFormatter generic = new GenericContentFormatter();
        assertEquals(conversation.prepareMessagesForLLM(generic, false), conversation.render(generic, false),
                "what the shared preparation sends under that formatter is what the pure render returns");
        int toolBlocksAsText = DefaultContextEstimator.INSTANCE.estimate(conversation, nova).inputByPart().get(ContextPart.MESSAGES);
        assertEquals(toolBlocksAsText, BedrockContextEstimator.forSpec(nova).estimate(conversation, nova).inputByPart().get(ContextPart.MESSAGES),
                "and the Bedrock estimator is that generic walk for a family outside Claude and Cohere");
        assertTrue(toolBlocksAsText > TiktokenCounter.DEFAULT.countTokens("plan the migration") + TiktokenCounter.DEFAULT.countTokens("looking") + TiktokenCounter.DEFAULT.countTokens(ModelText.render(new ToolResultBlock("t1", "{\"hits\":3}", false))),
                "the call's text form, name and input, is in the count");
    }

    @Test
    void theProvidersHandOutTheirCountingThroughBothDoors() {
        BedrockConverseProvider converse = new BedrockConverseProvider();
        ModelSpec claude = spec("anthropic.claude-sonnet-4-5-20250929-v1:0", "bedrock-converse");
        assertSame(BedrockContextEstimator.forSpec(claude), converse.contextEstimator(claude));
        assertSame(BedrockContextEstimator.CLAUDE_COUNTER, converse.tokenCounter(claude));
        assertSame(converse.contextEstimator(claude), claude.contextEstimator(), "the spec reaches the registered provider by key");
        assertSame(converse.tokenCounter(claude), claude.tokenCounter());
        BedrockCohereEmbeddingsProvider cohere = new BedrockCohereEmbeddingsProvider();
        ModelSpec embed = spec("cohere.embed-v4:0", "bedrock-cohere-embeddings");
        assertSame(BedrockContextEstimator.COHERE_COUNTER, cohere.tokenCounter(embed), "an embedding request is reserved at Cohere's density");
        assertSame(cohere.tokenCounter(embed), ((AbstractContextEstimator)cohere.contextEstimator(embed)).counter(), "and the estimator counts with the same counter");
        assertSame(cohere.tokenCounter(embed), embed.tokenCounter());
    }
}
