/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour of the base block encoders, exercised directly with a fake {@link TextWrapper} so no
 * client (and no secret/HTTP setup) is needed. Covers: the text bases render their content through
 * the wrapper, the drop bases emit nothing, a skill block renders Skill's canonical body form,
 * PojoBlock is a loud invariant, and every permitted block type has a base encoder. The provider-native encoders are covered in their provider
 * modules, where each module's test proves its native encoder builds a provider object without
 * touching the wrapper.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
class BlockEncoderTest {

    /** Records the string handed to the wrapper, returning it verbatim so assertions can read it. */
    private static final class CapturingWrapper implements TextWrapper<String> {
        String captured;
        @Override public String wrap(String text) { captured = text; return text; }
    }

    private static <B> B encode(BlockEncoder<B> encoder, TextWrapper<B> wrapper, ContentBlock block) {
        encoder.setTextWrapper(wrapper);
        return encoder.encode(block);
    }

    @Test
    void textBaseEncodersRenderThroughTheWrapper() {
        CapturingWrapper w = new CapturingWrapper();
        assertEquals("hello", encode(new TextBlockEncoder<>(), w, new TextBlock("hello")));
        assertEquals("{\"a\":1}", encode(new JsonBlockEncoder<>(), w, new JsonBlock("{\"a\":1}")));
        assertEquals("[Tool Use search] {\"q\":1}",
                encode(new ToolUseBlockEncoder<>(), w, new ToolUseBlock("id1", "search", "{\"q\":1}")));
        assertEquals("[Tool Result id1] {\"ok\":true}",
                encode(new ToolResultBlockEncoder<>(), w, new ToolResultBlock("id1", "{\"ok\":true}", false)));
        assertEquals("[image image/png]", encode(new ImageBlockEncoder<>(), w, new ImageBlock("x", "image/png", null)));
        assertEquals("[file report.pdf]", encode(new FileBlockEncoder<>(), w, new FileBlock("x", "application/pdf", "report.pdf")));
    }

    @Test
    void emptyTextProducesNothing() {
        CapturingWrapper w = new CapturingWrapper();
        assertNull(encode(new TextBlockEncoder<>(), w, new TextBlock("")));
        assertNull(encode(new TextBlockEncoder<>(), w, new TextBlock(null)));
    }

    @Test
    void dropBasesEmitNothing() {
        CapturingWrapper w = new CapturingWrapper();
        assertNull(encode(new ThinkingBlockEncoder<>(), w, new ThinkingBlock("t", "sig")));
        assertNull(encode(new RedactedThinkingBlockEncoder<>(), w, new RedactedThinkingBlock("data")));
    }

    @Test
    void toolDefinitionRendersItsOneTextForm() {
        // The default is the readable text form - providers with a native tools API
        // override to null and collect the blocks into their tools parameter instead.
        // The encoded string is renderText verbatim: the same authority every token
        // count measures, so wire and estimate cannot diverge.
        CapturingWrapper w = new CapturingWrapper();
        ToolDefinitionBlock td = new ToolDefinitionBlock("n", "d", "{}");
        assertEquals(ToolDefinitionBlockEncoder.renderText(td),
                encode(new ToolDefinitionBlockEncoder<>(), w, td));
        assertEquals("n: d\n  Input parameters: {}", ToolDefinitionBlockEncoder.renderText(td));
        assertEquals("n: d", ToolDefinitionBlockEncoder.renderText(new ToolDefinitionBlock("n", "d", null)),
                "a schema-less definition renders without a parameters section");
    }

    @Test
    void skillBlockRendersTheCanonicalBodyFormThroughTheWrapper() {
        // The encoded string is Skill.renderBody verbatim: the one in-conversation skill
        // rendering lives on Skill, never duplicated in an encoder or a provider client.
        CapturingWrapper w = new CapturingWrapper();
        Skill skill = new Skill() {
            @Override public String name() { return "sea-legs"; }
            @Override public Prompt description() { return null; }
            @Override public Prompt body() { return null; }
            @Override public Map<String, Prompt> resources() { return null; }
            @Override public List<String> suggestedTools() { return null; }
            @Override public SkillMetadata metadata() { return null; }
        };
        assertEquals(Skill.renderBody(skill), encode(new SkillBlockEncoder<>(), w, new SkillBlock(skill)));
        assertTrue(w.captured.startsWith("[Skill: sea-legs]"),
                "the rendering carries the skill header the conversation format promises");
    }

    @Test
    void pojoBlockIsALoudInvariant() {
        PojoBlockEncoder<String> encoder = new PojoBlockEncoder<>();
        assertThrows(UncorrectableRuntimeLLMException.class, () -> encoder.encode(new PojoBlock("x")));
    }

    @Test
    void everyPermittedBlockTypeHasABaseEncoder() {
        for (Class<?> permitted : ContentBlock.class.getPermittedSubclasses()) {
            String encoderClass = "ai.redouble.nucleo.harness.llm.encode." + permitted.getSimpleName() + "Encoder";
            assertDoesNotThrow(() -> Class.forName(encoderClass),
                    "missing base encoder for new block type " + permitted.getSimpleName());
        }
    }
}
