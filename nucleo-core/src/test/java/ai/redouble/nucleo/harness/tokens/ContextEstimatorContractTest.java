/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@link ModelSpec#estimate} counts: the conversation as the client sends it. The
 * objective, every message and the registry section each count the moment they exist; a tool
 * result counts by its payload and a tool call by its input, not by a placeholder; the
 * response instructions the answer handler appends count, because the wire carries them; the
 * output side is the call's reserve. The rendering the estimate counts is the rendering the
 * next real render sends, with the head unfrozen and with it frozen, and estimating changes
 * nothing the model will see. The estimate renders with the thinking decision the client will
 * make, from the declared depth, thinking off when none is declared; every block kind counts
 * by the counter's rule for it. The registry section is null without artifacts and the last
 * user turn with them; an undeclared seat has no depth and no refusal until something needs
 * one; a spec reaches its provider's estimator and counter by key, the core defaults when the
 * provider overrides nothing or is absent, and the core default answers with a
 * {@link DefaultTokenLedger}. A declared tool counts under the palette's part in its text form
 * while the head is unfrozen and as a message once it is announced in a turn; the walk hands a
 * provider's {@code countBlock} the place of every block, the newest assistant turn apart from
 * the earlier ones, and never the registry turn; an estimator refuses to exist without its
 * formatter or its counter. A skill block and a POJO block each cost the text the model reads
 * for them, as {@link ModelText} renders it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
class ContextEstimatorContractTest {

    private static final ModelSpec MODEL = TestModels.small();

    private static ConversationContext conversation() {
        ConversationContext conversation = TestModels.conversation(MODEL);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        return conversation;
    }

    private static OutgoingMessage<String> userText(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(text);
        return message;
    }

    private static IncomingMessage<String> assistantCalling(String toolUseId, String tool, String inputJson) {
        IncomingMessage<String> message = new IncomingMessage<>(StringResponseHandler.instance);
        message.setContentBlocks(List.of(new TextBlock("Let me look."), new ToolUseBlock(toolUseId, tool, inputJson)));
        message.overwriteRawContent("Let me look.");
        return message;
    }

    private static OutgoingMessage<String> toolResult(String toolUseId, String payload) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addToolResult(toolUseId, payload, false);
        return message;
    }

    private static int input(ConversationContext conversation) {
        return MODEL.estimate(conversation).inputTokens();
    }

    @Test
    void objectiveMessagesAndRegistryEachCount_byPart() {
        ConversationContext conversation = conversation();
        TokenLedger empty = MODEL.estimate(conversation);
        assertEquals(0, empty.inputTokens(), "an empty conversation costs nothing");
        assertEquals(0, empty.inputByPart().get(ContextPart.OBJECTIVE));
        conversation.putMainObjective("task", "extract every port label from the diagrams");
        TokenLedger withObjective = MODEL.estimate(conversation);
        assertTrue(withObjective.inputByPart().get(ContextPart.OBJECTIVE) > 0, "the objective is always sent, so it always counts");
        conversation.addMessage(userText("The ringports changed to five and seven yesterday."));
        TokenLedger withMessage = MODEL.estimate(conversation);
        assertTrue(withMessage.inputByPart().get(ContextPart.MESSAGES) > 0, "every message counts");
        assertTrue(withMessage.inputTokens() > withObjective.inputTokens());
        WebPageArtifact artifact = new WebPageArtifact();
        artifact.setUrl("https://example.org/ports");
        artifact.setTitle("Port assignments");
        conversation.getArtifactRegistry().register(artifact);
        TokenLedger withRegistry = MODEL.estimate(conversation);
        assertTrue(withRegistry.inputByPart().get(ContextPart.ARTIFACT_REGISTRY) > 0,
                "the registry section is appended at render, so it counts the moment an artifact exists");
        int sum = withRegistry.inputByPart().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(withRegistry.inputTokens(), sum, "the parts are the whole input");
    }

    @Test
    void aToolResultCountsByItsPayload_andAToolCallByItsInput() {
        ConversationContext conversation = conversation();
        conversation.addMessage(userText("find the standards"));
        int question = input(conversation);
        conversation.addMessage(assistantCalling("toolu_1", "search", "{\"query\":\"IEC 62439 redundancy protocols and their failover budgets\"}"));
        int withCall = input(conversation);
        assertTrue(withCall - question > 10, "the call's name and input are what the model reads, counted: " + (withCall - question));
        conversation.addMessage(toolResult("toolu_1", "{\"hits\":\"" + "redundancy ".repeat(2_000) + "\"}"));
        int withResult = input(conversation);
        assertTrue(withResult - withCall > 1_000,
                "a tool result of twenty thousand characters is thousands of tokens, never a placeholder: " + (withResult - withCall));
    }

    @Test
    void everyBlockKindCountsByTheCountersRuleForIt() throws Exception {
        ConversationContext conversation = conversation();
        OutgoingMessage<String> rich = userText("look at this");
        rich.addPojo(new ModelTextTest.Probe());
        rich.addImage(new java.io.ByteArrayInputStream(new byte[3 * 1024]), "image/png", "a probe");
        rich.addFile(new java.io.ByteArrayInputStream(new byte[10 * 1024]), "application/pdf", "doc.pdf");
        conversation.addMessage(rich);
        TokenCounter counter = MODEL.tokenCounter();
        PreparedConversation rendered = conversation.render(new GenericContentFormatter(), false);
        int expected = 0;
        boolean sawJson = false;
        for (ContentBlock block : rendered.turns().getLast().contentBlocks()) {
            switch (block) {
                case TextBlock tb -> expected += counter.countTokens(tb.text());
                case JsonBlock jb -> {
                    sawJson = true;
                    expected += counter.countTokens(jb.json());
                }
                case ImageBlock ib -> expected += counter.countImageTokens(ib);
                case FileBlock fb -> expected += counter.countFileTokens(fb);
                default -> fail("the rendered turn carries a kind this fixture did not send: " + block.getClass().getSimpleName());
            }
        }
        assertTrue(sawJson, "the POJO is lowered to JSON at render and counted as that text");
        assertEquals(expected, MODEL.estimate(conversation).inputByPart().get(ContextPart.MESSAGES),
                "text and JSON by the counter's text rule, the image by its image rule, the file by its file rule");
    }

    /** A handler whose instructions depend on whether a native thinking block will carry the reasoning. */
    private static final ResponseHandler<String> THINKING_AWARE = new ResponseHandler<>() {
        @Override
        public Class<String> getResponseClass() {
            return String.class;
        }

        @Override
        public String parse(String rawContent) {
            return rawContent;
        }

        @Override
        public String write(String response) {
            return response;
        }

        @Override
        public List<String> getValidationErrors(String response) {
            return List.of();
        }

        @Override
        public PojoDefinition writeDefinition() {
            return StringResponseHandler.instance.writeDefinition();
        }

        @Override
        public boolean usesSchemaNotation() {
            return false;
        }

        @Override
        public String responseInstructions() {
            return responseInstructions(false, false);
        }

        @Override
        public String responseInstructions(boolean nativeToolsAvailable, boolean thinkingActive) {
            return thinkingActive ? "Answer." : "Reason step by step before you answer, in prose. ".repeat(20);
        }
    };

    private static ConversationContext thinkingAwareAsk(ModelSpec spec, Depth depth) {
        ConversationContext conversation = TestModels.conversation(spec);
        if (depth != null) {
            conversation.setDepth(depth);
        }
        OutgoingMessage<String> ask = new OutgoingMessage<>(THINKING_AWARE);
        ask.setRole("user");
        ask.addText("what is the failover budget?");
        conversation.addMessage(ask);
        return conversation;
    }

    @Test
    void theEstimateRendersWithTheThinkingDecisionTheClientWillMake_fromTheDeclaredDepth() {
        StandardModelSpec thinking = new StandardModelSpec();
        thinking.setId("thinks");
        thinking.setIdentity("thinks");
        thinking.setProviderKey("provider-not-on-the-classpath");
        thinking.setWireModelId("thinks");
        thinking.setMaxContextTokens(200_000);
        thinking.setMaxOutputTokens(64_000);
        thinking.setThinkingMode(ThinkingMode.EXTENDED);
        int thinkingOn = thinking.estimate(thinkingAwareAsk(thinking, Depth.STANDARD)).inputByPart().get(ContextPart.MESSAGES);
        int thinkingOff = thinking.estimate(thinkingAwareAsk(thinking, Depth.IMMEDIATE)).inputByPart().get(ContextPart.MESSAGES);
        assertTrue(thinkingOn < thinkingOff, "a depth that turns native thinking on renders the shorter instructions the client will send: " + thinkingOn + " vs " + thinkingOff);
        assertEquals(thinkingOff, thinking.estimate(thinkingAwareAsk(thinking, null)).inputByPart().get(ContextPart.MESSAGES),
                "a conversation that declares no depth renders with thinking off");
        StandardModelSpec plain = new StandardModelSpec();
        plain.setId("plain");
        plain.setIdentity("plain");
        plain.setProviderKey("provider-not-on-the-classpath");
        plain.setWireModelId("plain");
        plain.setMaxContextTokens(200_000);
        plain.setMaxOutputTokens(64_000);
        assertEquals(thinkingOff, plain.estimate(thinkingAwareAsk(plain, Depth.STANDARD)).inputByPart().get(ContextPart.MESSAGES),
                "on a model with no native thinking the same depth renders with thinking off");
    }

    @Test
    void theResponseInstructionsCount_becauseTheWireCarriesThem() {
        ConversationContext prose = conversation();
        prose.addMessage(userText("summarize the incident"));
        ConversationContext structured = conversation();
        OutgoingMessage<ConversationPreparationTest.ProbeAnswer> ask =
                new OutgoingMessage<>(new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class));
        ask.setRole("user");
        ask.addText("summarize the incident");
        structured.addMessage(ask);
        assertTrue(input(structured) > input(prose),
                "a structured answer's schema instructions are sent with the message, so they are counted");
    }

    @Test
    void theOutputSideIsTheCallsReserve_readOnDemand() {
        ConversationContext conversation = conversation();
        conversation.addMessage(userText("hello"));
        assertEquals(conversation.outputReserve(MODEL), MODEL.estimate(conversation).outputTokens());
        ConversationContext undeclared = TestModels.conversation(MODEL);
        undeclared.addMessage(userText("hello"));
        TokenLedger estimate = MODEL.estimate(undeclared);
        assertTrue(estimate.inputTokens() > 0, "a conversation that declared no depth and no answer size still has its input counted");
        assertThrows(RuntimeException.class, estimate::outputTokens, "and meets the seat's refusal only when something asks for the reserve");
    }

    @Test
    void theEstimateRendersWhatTheNextRenderSends_unfrozenAndFrozen() {
        ConversationContext conversation = conversation();
        conversation.putMainObjective("task", "answer the question");
        conversation.addMessage(userText("what is the failover budget?"));
        conversation.addTool(new ToolDefinitionBlock("lookup", "Looks a standard up", "{\"type\":\"object\"}"));
        GenericContentFormatter formatter = new GenericContentFormatter();
        PreparedConversation estimated = conversation.render(formatter, false);
        assertEquals(1, estimated.toolDefinitions().size(), "a declared, unannounced tool renders in the head of an unfrozen conversation");
        PreparedConversation sent = conversation.prepareMessagesForLLM(formatter, false);
        assertEquals(sent, estimated, "the estimate's rendering IS the first render's payload");
        conversation.addTool(new ToolDefinitionBlock("fetch", "Fetches a page", "{\"type\":\"object\"}"));
        PreparedConversation estimatedAgain = conversation.render(formatter, false);
        assertEquals(1, estimatedAgain.toolDefinitions().size(), "a frozen head does not grow");
        assertEquals(1, estimatedAgain.turns().size(), "a tool declared after the freeze is announced inside the turn about to be sent");
        assertTrue(estimatedAgain.turns().getFirst().contentBlocks().stream().anyMatch(b -> b instanceof ToolDefinitionBlock td && td.name().equals("fetch")),
                "the turn renders with the announcement among its blocks");
        assertEquals(1, conversation.getMessages().size(), "estimating announced nothing: the stream is as it was");
        assertFalse(conversation.getLastOutgoingMessage().getContentBlocks().stream().anyMatch(b -> b instanceof ToolDefinitionBlock),
                "and the live turn carries no announcement until the real render");
        PreparedConversation sentAgain = conversation.prepareMessagesForLLM(formatter, false);
        assertEquals(sentAgain, estimatedAgain, "the estimate's rendering IS the next render's payload on a frozen head too");
    }

    @Test
    void theDefaultEstimatorCountsNothingForAThinkingBlock_becauseTheGenericEncodersSendNone() {
        ConversationContext withThinking = conversation();
        withThinking.addMessage(userText("think about it"));
        IncomingMessage<String> thought = new IncomingMessage<>(StringResponseHandler.instance);
        thought.setContentBlocks(List.of(new ThinkingBlock("x".repeat(4_000), "sig"), new TextBlock("done")));
        thought.overwriteRawContent("done");
        withThinking.addMessage(thought);
        ConversationContext without = conversation();
        without.addMessage(userText("think about it"));
        IncomingMessage<String> plain = new IncomingMessage<>(StringResponseHandler.instance);
        plain.setContentBlocks(List.of(new TextBlock("done")));
        plain.overwriteRawContent("done");
        without.addMessage(plain);
        assertEquals(input(without), input(withThinking), "a block the client does not send costs nothing");
    }

    @Test
    void theRegistrySectionIsNullWithoutArtifacts_andTheLastUserTurnWithThem() {
        ConversationContext conversation = conversation();
        conversation.addMessage(userText("hello"));
        GenericContentFormatter formatter = new GenericContentFormatter();
        assertNull(conversation.render(formatter, false).registrySection(), "no artifacts, no registry section");
        WebPageArtifact artifact = new WebPageArtifact();
        artifact.setUrl("https://example.org/ports");
        conversation.getArtifactRegistry().register(artifact);
        PreparedConversation prepared = conversation.render(formatter, false);
        assertNotNull(prepared.registrySection());
        ProcessedMessageData last = prepared.turns().getLast();
        assertEquals(TurnRole.USER, last.role(), "the registry rides a user turn");
        assertTrue(last.contentBlocks().stream().anyMatch(b -> b instanceof TextBlock tb && tb.text().equals(prepared.registrySection())),
                "and that turn is the section itself, so an estimate can count it as its own part");
    }

    @Test
    void anUndeclaredSeatHasNoDepth_andTheRefusalWaitsForWhoeverNeedsOne() {
        ConversationContext undeclared = TestModels.conversation(MODEL);
        assertNull(undeclared.declaredDepth(), "no seat declared a depth yet");
        assertThrows(RuntimeException.class, undeclared::resolveDepth, "resolving one is the refusal");
        undeclared.setDepth(Depth.IMMEDIATE);
        assertEquals(Depth.IMMEDIATE, undeclared.declaredDepth());
    }

    @Test
    void aProviderThatOverridesNothingHandsOutTheCoreDefaults_andTheSpecReachesThemByKey() {
        FakeLLMProvider provider = new FakeLLMProvider();
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("served-by-the-fake");
        spec.setIdentity("fake");
        spec.setProviderKey(provider.key());
        spec.setWireModelId("fake");
        spec.setMaxContextTokens(100_000);
        spec.setMaxOutputTokens(4_000);
        assertSame(DefaultContextEstimator.INSTANCE, provider.contextEstimator(spec), "ClientProvider's default estimator");
        assertSame(TiktokenCounter.DEFAULT, provider.tokenCounter(spec), "ClientProvider's default counter");
        assertSame(DefaultContextEstimator.INSTANCE, spec.contextEstimator(), "the spec finds its registered provider by key");
        assertSame(TiktokenCounter.DEFAULT, spec.tokenCounter());
    }

    @Test
    void aSpecWithNoProviderModuleEstimatesWithTheCoreDefault() {
        StandardModelSpec orphan = new StandardModelSpec();
        orphan.setId("nobody-serves-this");
        orphan.setIdentity("nobody");
        orphan.setProviderKey("provider-not-on-the-classpath");
        orphan.setWireModelId("nobody");
        orphan.setMaxContextTokens(100_000);
        orphan.setMaxOutputTokens(4_000);
        assertSame(DefaultContextEstimator.INSTANCE, orphan.contextEstimator());
        assertSame(TiktokenCounter.DEFAULT, orphan.tokenCounter());
        assertInstanceOf(DefaultTokenLedger.class, orphan.estimate(TestModels.conversation(orphan)),
                "the core default answers with the ledger that carries no provider knowledge");
    }

    @Test
    void aDeclaredToolCountsUnderThePalette_inItsTextForm_andAnAnnouncedOneAsAMessage() {
        ConversationContext conversation = conversation();
        conversation.addMessage(userText("what is the failover budget?"));
        TokenLedger bare = MODEL.estimate(conversation);
        assertEquals(0, bare.inputByPart().get(ContextPart.TOOL_DEFINITIONS), "no tool, nothing under the palette's part");
        ToolDefinitionBlock lookup = new ToolDefinitionBlock("lookup", "Looks a standard up", "{\"type\":\"object\",\"properties\":{\"standard\":{\"type\":\"string\"}}}");
        conversation.addTool(lookup);
        TokenLedger declared = MODEL.estimate(conversation);
        assertEquals(MODEL.tokenCounter().countTokens(ToolDefinitionBlockEncoder.renderText(lookup)), declared.inputByPart().get(ContextPart.TOOL_DEFINITIONS),
                "a definition in the palette counts in the text form the generic encoders send");
        conversation.prepareMessagesForLLM(new GenericContentFormatter(), false);
        ToolDefinitionBlock fetch = new ToolDefinitionBlock("fetch", "Fetches a page", "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}");
        conversation.addTool(fetch);
        TokenLedger announced = MODEL.estimate(conversation);
        assertEquals(declared.inputByPart().get(ContextPart.TOOL_DEFINITIONS), announced.inputByPart().get(ContextPart.TOOL_DEFINITIONS),
                "a frozen head does not grow: the palette's part stays what it was");
        assertTrue(announced.inputByPart().get(ContextPart.MESSAGES) - declared.inputByPart().get(ContextPart.MESSAGES)
                        >= MODEL.tokenCounter().countTokens(ToolDefinitionBlockEncoder.renderText(fetch)),
                "a definition announced in a turn counts as a message, in the same text form");
    }

    private static IncomingMessage<String> assistantSaying(String text) {
        IncomingMessage<String> message = new IncomingMessage<>(StringResponseHandler.instance);
        message.setContentBlocks(List.of(new TextBlock(text)));
        message.overwriteRawContent(text);
        return message;
    }

    @Test
    void theWalkTellsAProviderWhereEachBlockSits_andNeverHandsItTheRegistryTurn() {
        List<String> seen = new ArrayList<>();
        AbstractContextEstimator recording = new AbstractContextEstimator(new GenericContentFormatter(), TiktokenCounter.DEFAULT) {
            @Override
            protected int countBlock(ContentBlock block, TurnPlace place) {
                if (block instanceof TextBlock tb) {
                    seen.add(place + ":" + tb.text());
                }
                return super.countBlock(block, place);
            }
        };
        ConversationContext conversation = conversation();
        conversation.addMessage(userText("first question"));
        conversation.addMessage(assistantSaying("first answer"));
        conversation.addMessage(userText("second question"));
        conversation.addMessage(assistantSaying("second answer"));
        WebPageArtifact artifact = new WebPageArtifact();
        artifact.setUrl("https://example.org/ports");
        conversation.getArtifactRegistry().register(artifact);
        TokenLedger estimate = recording.estimate(conversation, MODEL);
        assertTrue(seen.contains("USER:first question"), "a user turn is USER: " + seen);
        assertTrue(seen.contains("EARLIER_ASSISTANT:first answer"), "an assistant turn before the newest is EARLIER_ASSISTANT: " + seen);
        assertTrue(seen.contains("USER:second question"), seen.toString());
        assertTrue(seen.contains("LAST_ASSISTANT:second answer"), "the newest assistant turn is LAST_ASSISTANT: " + seen);
        String registry = conversation.render(new GenericContentFormatter(), false).registrySection();
        assertNotNull(registry);
        assertTrue(seen.stream().noneMatch(entry -> entry.endsWith(":" + registry)),
                "the registry turn is counted as its own part, never walked as a block: " + seen);
        assertTrue(estimate.inputByPart().get(ContextPart.ARTIFACT_REGISTRY) > 0);
    }

    @Test
    void aSkillBlockAndAPojoBlockCostTheTextTheModelReads() {
        AbstractContextEstimator estimator = new AbstractContextEstimator(new GenericContentFormatter(), TiktokenCounter.DEFAULT) {};
        Skill skill = new Skill() {
            public String name() {
                return "doc.search";
            }

            public Prompt description() {
                return prompt("Finds docs.");
            }

            public Prompt body() {
                return prompt("Do the search, then read the first hit.");
            }

            public Map<String, Prompt> resources() {
                return Map.of();
            }

            public List<String> suggestedTools() {
                return List.of();
            }

            public SkillMetadata metadata() {
                return null;
            }
        };
        SkillBlock skillBlock = new SkillBlock(skill);
        PojoBlock pojoBlock = new PojoBlock(new ModelTextTest.Probe());
        assertEquals(TiktokenCounter.DEFAULT.countTokens(ModelText.render(skillBlock)), estimator.countBlock(skillBlock, TurnPlace.USER),
                "a skill costs its rendered body, the text the model reads");
        assertTrue(estimator.countBlock(skillBlock, TurnPlace.USER) > 0, "a skill with a body is never free");
        assertEquals(TiktokenCounter.DEFAULT.countTokens(ModelText.render(pojoBlock)), estimator.countBlock(pojoBlock, TurnPlace.USER),
                "a POJO costs its summarized JSON, the text the model reads");
    }

    private static Prompt prompt(String text) {
        return new Prompt() {
            public String key() {
                return "k";
            }

            public JsonNode content() {
                return TextNode.valueOf(text);
            }

            public PromptContext context() {
                return null;
            }
        };
    }

    @Test
    void anEstimatorRequiresAFormatterAndACounter() {
        assertThrows(IllegalArgumentException.class, () -> new AbstractContextEstimator(null, TiktokenCounter.DEFAULT) {}, "no formatter, no estimator");
        assertThrows(IllegalArgumentException.class, () -> new AbstractContextEstimator(new GenericContentFormatter(), null) {}, "no counter, no estimator");
    }
}
