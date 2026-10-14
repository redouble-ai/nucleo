/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic's counting as the provider module states it: the usage ledger sums the three
 * additive input counters into the total the model read; the estimator counts a thinking block,
 * redacted or not, on the newest assistant turn and on no earlier one; text counts at
 * {@code cl100k_base} times 1.10 and an image by Anthropic's rule, its pixels over 750 after
 * scaling to 1568 on the long side, capped at 1600, with 1600 or four tokens per KB, whichever
 * is more, when its size cannot be read, and a PDF at 450 per KB; a tool call and a tool result
 * count as their text form; what it counts is the rendering the Anthropic client sends,
 * formatter included; and a spec on the provider's key reaches that estimator and counter.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
class AnthropicTokenAccountingTest {

    private static StandardModelSpec claude() {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("claude-test-direct");
        spec.setIdentity("claude-test");
        spec.setProviderKey("anthropic-direct");
        spec.setWireModelId("claude-test");
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

    private static IncomingMessage<String> assistant(String text, String thinking) {
        IncomingMessage<String> message = new IncomingMessage<>(StringResponseHandler.instance);
        List<ContentBlock> blocks = new ArrayList<>();
        if (thinking != null) {
            blocks.add(new ThinkingBlock(thinking, "sig"));
        }
        blocks.add(new TextBlock(text));
        message.setContentBlocks(blocks);
        message.overwriteRawContent(text);
        return message;
    }

    private static OutgoingMessage<String> user(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(text);
        return message;
    }

    @Test
    void theLedgerSumsTheAdditiveCountersIntoTheTotalTheModelRead() {
        TokenLedger usage = new AnthropicTokenLedger(claude(), 100, 20, 30, 5);
        assertEquals(150, usage.inputTokens(), "input plus cache creation plus cache read");
        assertEquals(30, usage.cacheReadTokens());
        assertEquals(20, usage.cacheWriteTokens());
        assertEquals(100, usage.uncachedInputTokens());
        assertEquals(5, usage.outputTokens());
        TokenLedger uncached = new AnthropicTokenLedger(claude(), 100, null, null, 5);
        assertEquals(100, uncached.inputTokens(), "counters the response did not carry add nothing");
        assertNull(uncached.cacheReadTokens(), "and stay null as facets");
    }

    @Test
    void aThinkingBlockCountsOnTheNewestAssistantTurnOnly() {
        ModelSpec claude = claude();
        AnthropicContextEstimator estimator = AnthropicContextEstimator.INSTANCE;
        String thought = "weighing the options ".repeat(300);
        ConversationContext earlierThinking = conversation(claude);
        earlierThinking.getMessages().add(assistant("step one", thought));
        earlierThinking.getMessages().add(user("go on"));
        earlierThinking.getMessages().add(assistant("step two", null));
        earlierThinking.getMessages().add(user("and then"));
        ConversationContext noThinking = conversation(claude);
        noThinking.getMessages().add(assistant("step one", null));
        noThinking.getMessages().add(user("go on"));
        noThinking.getMessages().add(assistant("step two", null));
        noThinking.getMessages().add(user("and then"));
        assertEquals(estimator.estimate(noThinking, claude).inputTokens(), estimator.estimate(earlierThinking, claude).inputTokens(),
                "Anthropic strips an earlier turn's thinking from billing, so it costs nothing");
        ConversationContext newestThinking = conversation(claude);
        newestThinking.getMessages().add(assistant("step one", null));
        newestThinking.getMessages().add(user("go on"));
        newestThinking.getMessages().add(assistant("step two", thought));
        newestThinking.getMessages().add(user("and then"));
        int difference = estimator.estimate(newestThinking, claude).inputTokens() - estimator.estimate(noThinking, claude).inputTokens();
        assertTrue(difference > 500, "the newest assistant turn's thinking is replayed and billed: " + difference);
    }

    @Test
    void aRedactedThinkingBlockCountsLikeThinking_onTheNewestAssistantTurnOnly() {
        ModelSpec claude = claude();
        AnthropicContextEstimator estimator = AnthropicContextEstimator.INSTANCE;
        String opaque = "redacted-payload-".repeat(300);
        ConversationContext noThinking = conversation(claude);
        noThinking.getMessages().add(assistant("step one", null));
        noThinking.getMessages().add(user("go on"));
        noThinking.getMessages().add(assistant("step two", null));
        noThinking.getMessages().add(user("and then"));
        ConversationContext earlierRedacted = conversation(claude);
        earlierRedacted.getMessages().add(redacted("step one", opaque));
        earlierRedacted.getMessages().add(user("go on"));
        earlierRedacted.getMessages().add(assistant("step two", null));
        earlierRedacted.getMessages().add(user("and then"));
        assertEquals(estimator.estimate(noThinking, claude).inputTokens(), estimator.estimate(earlierRedacted, claude).inputTokens(),
                "an earlier turn's redacted thinking is stripped from billing");
        ConversationContext newestRedacted = conversation(claude);
        newestRedacted.getMessages().add(assistant("step one", null));
        newestRedacted.getMessages().add(user("go on"));
        newestRedacted.getMessages().add(redacted("step two", opaque));
        newestRedacted.getMessages().add(user("and then"));
        assertTrue(estimator.estimate(newestRedacted, claude).inputTokens() - estimator.estimate(noThinking, claude).inputTokens() > 500,
                "the newest turn's redacted thinking is replayed and billed");
    }

    private static String png(int width, int height) {
        byte[] header = new byte[]{(byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte)(width >>> 24), (byte)(width >>> 16), (byte)(width >>> 8), (byte)width,
                (byte)(height >>> 24), (byte)(height >>> 16), (byte)(height >>> 8), (byte)height, 8, 6, 0, 0, 0};
        return Base64.getEncoder().encodeToString(header);
    }

    private static IncomingMessage<String> redacted(String text, String data) {
        IncomingMessage<String> message = new IncomingMessage<>(StringResponseHandler.instance);
        message.setContentBlocks(List.of(new RedactedThinkingBlock(data), new TextBlock(text)));
        message.overwriteRawContent(text);
        return message;
    }

    @Test
    void theDensityAndTheImageFloorAreAsStated_andTheSpecReachesThemByKey() {
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        assertEquals((int)(TiktokenCounter.DEFAULT.countTokens(text) * 1.10), AnthropicContextEstimator.COUNTER.countTokens(text),
                "cl100k_base at 1.10");
        String noHeader = Base64.getEncoder().encodeToString(new byte[2 * 1024]);
        assertEquals(1600, AnthropicContextEstimator.COUNTER.countImageTokens(new ImageBlock(noHeader, "image/png", null)),
                "an image whose size cannot be read is estimated at Anthropic's ceiling as a floor");
        assertEquals(2000, AnthropicContextEstimator.COUNTER.countImageTokens(new ImageBlock(Base64.getEncoder().encodeToString(new byte[500 * 1024]), "image/png", null)),
                "or at four tokens per KB of its bytes when that is more, erring high");
        assertEquals(4500, AnthropicContextEstimator.COUNTER.countFileTokens(new FileBlock(Base64.getEncoder().encodeToString(new byte[10 * 1024]), "application/pdf", "brief.pdf")),
                "a PDF costs 450 tokens per KB");
        assertEquals(14, AnthropicContextEstimator.COUNTER.countImageTokens(new ImageBlock(png(100, 100), "image/png", null)), "pixels over 750, rounded up");
        assertEquals(1334, AnthropicContextEstimator.COUNTER.countImageTokens(new ImageBlock(png(1000, 1000), "image/png", null)));
        assertEquals(1600, AnthropicContextEstimator.COUNTER.countImageTokens(new ImageBlock(png(2000, 2000), "image/png", null)),
                "scaled to 1568 on the long side and capped at 1600, as Anthropic scales");
        assertEquals(820, AnthropicContextEstimator.COUNTER.countImageTokens(new ImageBlock(png(4000, 1000), "image/png", null)),
                "a wide image is scaled to 1568 x 392 first, then priced by its pixels");
        ModelSpec claude = claude();
        assertSame(AnthropicContextEstimator.INSTANCE, claude.contextEstimator(), "the spec finds the anthropic-direct provider by key");
        assertSame(AnthropicContextEstimator.COUNTER, claude.tokenCounter());
    }

    @Test
    void theEstimateRendersWhatTheAnthropicClientSends() {
        ModelSpec claude = claude();
        ConversationContext conversation = conversation(claude);
        conversation.putMainObjective("task", "answer briefly");
        AnthropicContentFormatter formatter = new AnthropicContentFormatter();
        PreparedConversation estimated = conversation.render(formatter, ThinkingMode.thinkingActive(claude, conversation.resolveDepth()));
        PreparedConversation sent = conversation.prepareMessagesForLLM(formatter, ThinkingMode.thinkingActive(claude, conversation.resolveDepth()));
        assertEquals(sent, estimated, "the provider's estimator counts the payload the provider's client carries");
    }

    @Test
    void aToolCallAndAToolResultCountAsTheirTextForm() {
        ModelSpec claude = claude();
        ConversationContext conversation = conversation(claude);
        IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
        call.setContentBlocks(List.of(new TextBlock("looking"), new ToolUseBlock("t1", "search", "{\"q\":\"ports\"}")));
        call.overwriteRawContent("looking");
        conversation.getMessages().add(call);
        OutgoingMessage<String> result = new OutgoingMessage<>(StringResponseHandler.instance);
        result.setRole("user");
        result.addToolResult("t1", "{\"hits\":3}", false);
        conversation.getMessages().add(result);
        AnthropicTokenCounter counter = AnthropicContextEstimator.COUNTER;
        PreparedConversation rendered = conversation.render(new AnthropicContentFormatter(), ThinkingMode.thinkingActive(claude, conversation.declaredDepth()));
        int textForms = 0;
        int toolBlocks = 0;
        for (ProcessedMessageData turn : rendered.turns()) {
            for (ContentBlock block : turn.contentBlocks()) {
                if (block instanceof ToolUseBlock || block instanceof ToolResultBlock) {
                    toolBlocks++;
                }
                textForms += counter.countTokens(ModelText.render(block));
            }
        }
        assertEquals(2, toolBlocks, "the rendered turns carry the call and its result as native blocks");
        assertEquals(textForms, AnthropicContextEstimator.INSTANCE.estimate(conversation, claude).inputByPart().get(ContextPart.MESSAGES),
                "the call, name and input, and the result, id and payload, count as the text a model reads for them");
        assertTrue(counter.countTokens(ModelText.render(new ToolUseBlock("t1", "search", "{\"q\":\"ports\"}"))) > counter.countTokens("search"),
                "the text form carries the JSON, so a call costs more than its name");
    }

    @Test
    void theProviderSuppliesItsCounterAndEstimator() {
        AnthropicProvider<?> provider = new AnthropicDirectProvider();
        assertSame(AnthropicContextEstimator.INSTANCE, provider.contextEstimator(claude()));
        assertSame(AnthropicContextEstimator.COUNTER, provider.tokenCounter(claude()));
        assertTrue(AnthropicContextEstimator.COUNTER.countTokens("The quick brown fox jumps over the lazy dog. ".repeat(20))
                        > TiktokenCounter.DEFAULT.countTokens("The quick brown fox jumps over the lazy dog. ".repeat(20)),
                "Claude's density is above the neutral encoding's");
    }
}
