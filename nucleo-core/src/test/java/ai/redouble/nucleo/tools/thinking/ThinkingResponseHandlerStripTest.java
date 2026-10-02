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
 * Guards the envelope reasoning strip in
 * {@link ThinkingResponseHandler#responseInstructions(boolean, boolean)}: when a native thinking
 * block is guaranteed for the call, the prose {@code reasoning} field is omitted from the response
 * schema (the thinking block supersedes it); otherwise it stays. The strip must fire on the
 * non-native-tools path too - that path returns before any schema manipulation when only
 * {@code tool_calls} stripping is considered, so the reasoning strip has to be reachable there.
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
        String schema = handler().responseInstructions(false, false);
        assertTrue(schema.contains("reasoning"),
                "reasoning field must remain in the schema when thinking is inactive");
    }

    @Test
    public void reasoningStrippedWhenThinkingActiveWithoutNativeTools() {
        String schema = handler().responseInstructions(false, true);
        assertFalse(schema.contains("reasoning"),
                "reasoning field must be stripped when thinking is active even on the non-native-tools path");
    }

    @Test
    public void reasoningStrippedWhenThinkingActiveWithNativeTools() {
        String schema = handler().responseInstructions(true, true);
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
        assertTrue(verdictHandler().responseInstructions(true, false).contains("\"reasoning\""),
                "without native thinking the answer type's reasoning is asked for, it is the reasoning callers receive");
        String thinking = verdictHandler().responseInstructions(true, true);
        assertFalse(thinking.contains("\"reasoning\""),
                "under native thinking the answer type's nested reasoning goes too: the thinking block carries it, so the"
                        + " field would be output tokens spent on a value the handler discards");
        assertTrue(thinking.contains("\"call\""), "the rest of the answer type stays");
    }

    @Test
    public void noSchemaAsksAModelForItsThoughtProcess() {
        for (String schema : new String[]{verdictHandler().responseInstructions(false, false), verdictHandler().responseInstructions(true, false)}) {
            assertFalse(schema.toLowerCase().contains("thought process"),
                    "a reasoning field asks for a justification a reader can check, never for the model's thought process: " + schema);
        }
    }
}
