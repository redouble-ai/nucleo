/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ModelText}: a block as the model reads it. Text and JSON as they are; a tool
 * definition, call or result in the one text form the generic encoders send; an image and a
 * file by their description; thinking as nothing. A message is its blocks in order joined by
 * blank lines with the empty renderings left out, and an incoming message without blocks
 * reads as its text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-10)
 */
class ModelTextTest {

    @Test
    void textAndJsonRenderAsTheyAre() {
        assertEquals("plain words", ModelText.render(new TextBlock("plain words")));
        assertEquals("{\"a\":1}", ModelText.render(new JsonBlock("{\"a\":1}")));
    }

    @Test
    void toolBlocksRenderInTheEncodersTextForm_theOneTheWireSendsAndTheEstimateCounts() {
        ToolDefinitionBlock definition = new ToolDefinitionBlock("search", "Finds things", "{\"type\":\"object\"}");
        ToolUseBlock call = new ToolUseBlock("toolu_1", "search", "{\"q\":\"ports\"}");
        ToolResultBlock result = new ToolResultBlock("toolu_1", "{\"hits\":3}", false);
        assertEquals(ToolDefinitionBlockEncoder.renderText(definition), ModelText.render(definition));
        assertEquals(ToolUseBlockEncoder.renderText(call), ModelText.render(call));
        assertEquals(ToolResultBlockEncoder.renderText(result), ModelText.render(result));
        assertTrue(ModelText.render(call).contains("search") && ModelText.render(call).contains("{\"q\":\"ports\"}"),
                "a call reads as its name and its input");
        assertTrue(ModelText.render(result).contains("{\"hits\":3}"), "a result reads as its payload, never as a placeholder");
    }

    public static class Probe {
        private String name = "ringport";
        private int count = 7;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    @Test
    void aPojoRendersAsItsSummarizedJson() {
        Probe probe = new Probe();
        assertEquals(NucleoJsonSerializer.writeSummarized(probe), ModelText.render(new PojoBlock(probe)),
                "the same serialization the objective and the turns are lowered through");
        assertTrue(ModelText.render(new PojoBlock(probe)).contains("ringport"));
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
    void aSkillRendersAsItsBody() {
        Skill skill = new Skill() {
            public String name() {
                return "doc.search";
            }

            public Prompt description() {
                return prompt("Finds docs.");
            }

            public Prompt body() {
                return prompt("Do the search.");
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
        assertEquals(Skill.renderBody(skill), ModelText.render(new SkillBlock(skill)), "the rendered body, header and text, is what the model reads");
        assertTrue(ModelText.render(new SkillBlock(skill)).contains("Do the search."));
    }

    @Test
    void imagesAndFilesRenderByDescription_thinkingAsNothing() {
        assertEquals("[Image: a probe]", ModelText.render(new ImageBlock("aWRhdGE=", "image/png", "a probe")));
        assertEquals("[Image]", ModelText.render(new ImageBlock("aWRhdGE=", "image/png", null)));
        assertEquals("[File: doc.pdf]", ModelText.render(new FileBlock("ZmRhdGE=", "application/pdf", "doc.pdf")));
        assertEquals("", ModelText.render(new ThinkingBlock("reasoning", "sig")), "a text-rendering client sends no thinking");
        assertEquals("", ModelText.render(new RedactedThinkingBlock("opaque")));
    }

    @Test
    void aMessageIsItsBlocksJoinedByBlankLines_emptyRenderingsLeftOut() {
        IncomingMessage<String> assistant = new IncomingMessage<>(StringResponseHandler.instance);
        assistant.setContentBlocks(List.of(new ThinkingBlock("hmm", "sig"), new TextBlock("Let me look."), new ToolUseBlock("t1", "search", "{}")));
        assistant.overwriteRawContent("Let me look.");
        assertEquals("Let me look.\n\n" + ToolUseBlockEncoder.renderText(new ToolUseBlock("t1", "search", "{}")), ModelText.render(assistant),
                "the thinking block renders to nothing and leaves no blank line behind");
        OutgoingMessage<String> user = new OutgoingMessage<>(StringResponseHandler.instance);
        user.setRole("user");
        user.addText("first");
        user.addText("second");
        assertEquals("first\n\nsecond", ModelText.render(user));
    }

    @Test
    void anIncomingMessageWithoutBlocksReadsAsItsText() {
        IncomingMessage<String> plain = new IncomingMessage<>(StringResponseHandler.instance);
        plain.overwriteRawContent("just the answer");
        assertEquals("just the answer", ModelText.render(plain));
    }
}
