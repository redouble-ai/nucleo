/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A conversation rendered for a reader other than its model: every message under its role in
 * order, the text as said, a tool call with its name and arguments, a tool result against its
 * call, an error result marked; the model's private thinking left out.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class TranscriptTest {

    @Test
    void everyMessageCallAndResultInOrderWithoutTheThinking() {
        ConversationContext conversation = new ConversationContext();
        OutgoingMessage<String> ask = new OutgoingMessage<>(StringResponseHandler.instance);
        ask.setRole("user");
        ask.addText("How many days until the end of the year?");
        conversation.getMessages().add(ask);
        IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
        call.setContentBlocks(List.of(new ThinkingBlock("let me check the date", "sig"),
                new TextBlock("I need today's date."),
                new ToolUseBlock("call-1", "get_current_time", "{\"timezone\":\"UTC\"}")));
        conversation.getMessages().add(call);
        OutgoingMessage<String> results = new OutgoingMessage<>(StringResponseHandler.instance);
        results.setRole("user");
        results.addToolResult("call-1", "{\"now\":\"2026-09-22\"}", false);
        results.addToolResult("call-2", "no such tool", true);
        conversation.getMessages().add(results);
        IncomingMessage<String> answer = new IncomingMessage<>(StringResponseHandler.instance);
        answer.setContentBlocks(List.of(new TextBlock("100 days.")));
        conversation.getMessages().add(answer);

        String transcript = Transcript.render(conversation);

        assertEquals("""
                [user]
                How many days until the end of the year?
                [assistant]
                I need today's date.
                <tool call get_current_time id=call-1> {"timezone":"UTC"}
                [user]
                <tool result id=call-1> {"now":"2026-09-22"}
                <tool result id=call-2 error> no such tool
                [assistant]
                100 days.
                """, transcript);
        assertFalse(transcript.contains("let me check"), "the model's private thinking is not part of the transcript");
    }
}
