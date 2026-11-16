/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.tools.registry.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the envelope reasoning strip in {@link ThinkingResponseHandler#formatDefinition}:
 * when a native thinking block is guaranteed for the call, the prose {@code reasoning} field
 * is omitted from the reply format's schema (the thinking block supersedes it); otherwise it
 * stays, with or without native tool calling. Also pins the format's name: the envelope's
 * class, {@code _of_}, the answer's class, which is the {@code @type} of the schema's root and
 * the name the line after a turn asks for.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-07)
 */
public class ThinkingResponseHandlerStripTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ThinkingResponseHandler<String> handler() {
        return new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<String>>((Class)ThinkingResponse.class),
                StringResponseHandler.instance);
    }

    @Test
    public void reasoningPresentWhenNotThinking() {
        String schema = handler().formatDefinition(new ResponseRequirements(false, false));
        assertTrue(schema.contains("reasoning"),
                "reasoning field must remain in the schema when thinking is inactive");
    }

    @Test
    public void reasoningStrippedWhenThinkingActiveWithoutNativeTools() {
        String schema = handler().formatDefinition(new ResponseRequirements(false, true));
        assertFalse(schema.contains("reasoning"),
                "reasoning field must be stripped when thinking is active even on the non-native-tools path");
    }

    @Test
    public void reasoningStrippedWhenThinkingActiveWithNativeTools() {
        String schema = handler().formatDefinition(new ResponseRequirements(true, true));
        assertFalse(schema.contains("reasoning"),
                "reasoning field must be stripped when thinking is active with native tools");
    }

    /** An answer type that carries its own reasoning, the way a thinker's typed output does. */
    public static class Verdict extends ThinkerOutput<ai.redouble.nucleo.harness.schema.SimpleReasoning> {
        private String call;

        public String getCall() {return call;}

        public void setCall(String call) {this.call = call;}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ThinkingResponseHandler<Verdict> verdictHandler() {
        return new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<Verdict>>((Class)ThinkingResponse.class),
                new PojoResponseHandler<>(Verdict.class));
    }

    @Test
    public void theAnswerTypesOwnReasoningIsStrippedUnderNativeThinkingAndKeptOtherwise() {
        assertTrue(verdictHandler().formatDefinition(new ResponseRequirements(true, false)).contains("\"reasoning\""),
                "without native thinking the answer type's reasoning is asked for, it is the reasoning callers receive");
        String thinking = verdictHandler().formatDefinition(new ResponseRequirements(true, true));
        assertFalse(thinking.contains("\"reasoning\""),
                "under native thinking the answer type's nested reasoning goes too: the thinking block carries it, so the"
                        + " field would be output tokens spent on a value the handler discards");
        assertTrue(thinking.contains("\"call\""), "the rest of the answer type stays");
    }

    @Test
    public void theFormatIsNamedAfterTheEnvelopeAndTheAnswerClass_inTheDefinitionAndInTheLine() {
        assertEquals("ThinkingResponse_of_Verdict", verdictHandler().formatName());
        assertEquals("ThinkingResponse_of_String", handler().formatName(),
                "a thinker that answers in plain text has an answer class and so a format name");
        for (ResponseRequirements requirements : new ResponseRequirements[]{new ResponseRequirements(false, false), new ResponseRequirements(true, true)}) {
            assertTrue(verdictHandler().formatDefinition(requirements).contains("\"@type\" : \"ThinkingResponse_of_Verdict\""),
                    "the schema's root is typed by the format's name: " + verdictHandler().formatDefinition(requirements));
        }
        assertEquals("\n\nReply in the JSON format ThinkingResponse_of_Verdict, defined above.", verdictHandler().replyInstructions(new ResponseRequirements(false, false)),
                "the line after a turn of a thinker without tools names the format");
    }

    @Test
    public void aClassWithNoSimpleNameCannotNameAFormat() {
        Object anonymous = new Object() {};
        @SuppressWarnings({"unchecked", "rawtypes"})
        PojoResponseHandler<?> unnamed = new PojoResponseHandler(anonymous.getClass());
        IllegalStateException refused = assertThrows(IllegalStateException.class, unnamed::formatName);
        assertTrue(refused.getMessage().contains(anonymous.getClass().getName()), "the refusal names the class: " + refused.getMessage());
    }

    @Test
    public void noSchemaAsksAModelForItsThoughtProcess() {
        for (String schema : new String[]{verdictHandler().formatDefinition(new ResponseRequirements(false, false)), verdictHandler().formatDefinition(new ResponseRequirements(true, false))}) {
            assertFalse(schema.toLowerCase().contains("thought process"),
                    "a reasoning field asks for a justification a reader can check, never for the model's thought process: " + schema);
        }
    }
}
