/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * A conversation rendered as plain text for a reader other than the model that held it:
 * every message under its role, the text as said, each tool call with its name and
 * arguments, each tool result against the call it answers, an admitted skill by name and an
 * attachment by kind. What a run did, in order, on one page - for a judge comparing two
 * runs of the same task, for a person reading a trajectory, for a log. The model's private
 * thinking blocks are left out: they are the provider's channel, replayed verbatim to the
 * model and meant for nobody else, and tool schemas are left out as noise a reader of the
 * exchange does not need.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public final class Transcript {
    private Transcript() {}

    /** The conversation's messages, in order, as the text above. */
    public static String render(ConversationContext conversation) {
        StringBuilder sb = new StringBuilder();
        for (Message message : conversation.getMessages()) {
            sb.append('[').append(message.getRole()).append("]\n");
            List<ContentBlock> blocks = blocksOf(message);
            if (blocks == null) {
                sb.append(message.getRawContent()).append('\n');
                continue;
            }
            for (ContentBlock block : blocks) {
                switch (block) {
                    case TextBlock text -> sb.append(text.text()).append('\n');
                    case PojoBlock pojo -> sb.append(NucleoJsonSerializer.write(pojo.pojo())).append('\n');
                    case JsonBlock json -> sb.append(json.json()).append('\n');
                    case ToolUseBlock call -> sb.append("<tool call ").append(call.toolName()).append(" id=").append(call.toolUseId()).append("> ")
                            .append(call.inputJson()).append('\n');
                    case ToolResultBlock result -> sb.append("<tool result id=").append(result.toolUseId()).append(result.isError() ? " error" : "").append("> ")
                            .append(result.resultJson()).append('\n');
                    case SkillBlock skill -> sb.append("<skill ").append(skill.skill().name()).append(">\n");
                    case ImageBlock image -> sb.append("<image ").append(image.mimeType()).append(">\n");
                    case FileBlock file -> sb.append("<file ").append(file.filename()).append(">\n");
                    case ToolDefinitionBlock definition -> { }
                    case ThinkingBlock thinking -> { }
                    case RedactedThinkingBlock redacted -> { }
                    default -> sb.append('<').append(block.getClass().getSimpleName()).append(">\n");
                }
            }
        }
        return sb.toString();
    }

    private static List<ContentBlock> blocksOf(Message message) {
        if (message instanceof OutgoingMessage<?> outgoing) {
            return outgoing.getContentBlocks();
        }
        if (message instanceof IncomingMessage<?> incoming) {
            return incoming.getContentBlocks();
        }
        return null;
    }
}
