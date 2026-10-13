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
import ai.redouble.nucleo.prompt.skill.*;

import java.util.*;

/**
 * A block or a message as text, as a model would read it: a tool call with its name and
 * input, a tool result with its payload, an image or a file by its description. This is the
 * form the text-rendering encoders send to a provider without a native shape for a block,
 * so the default estimator counts it, and the compactor shows it to the summary model.
 *
 * <p>The switch over {@link ContentBlock} is exhaustive on the sealed type: a block kind added
 * to the framework does not compile until this says how a model reads it.
 *
 * <p>Distinct from {@link Message#getRawContent()}, which is a short placeholder for logs and
 * persistence and names a tool result by its id alone.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public final class ModelText {
    private ModelText() {}

    /** The text a model reads for one block; empty for a block no text-rendering client sends. */
    public static String render(ContentBlock block) {
        return switch (block) {
            case TextBlock tb -> tb.text();
            case JsonBlock jb -> jb.json();
            case PojoBlock pb -> NucleoJsonSerializer.writeSummarized(pb.pojo());
            case ToolDefinitionBlock td -> ToolDefinitionBlockEncoder.renderText(td);
            case ToolUseBlock tu -> ToolUseBlockEncoder.renderText(tu);
            case ToolResultBlock tr -> ToolResultBlockEncoder.renderText(tr);
            case ImageBlock ib -> ib.description() != null ? "[Image: " + ib.description() + "]" : "[Image]";
            case FileBlock fb -> "[File: " + fb.filename() + "]";
            case ThinkingBlock th -> "";
            case RedactedThinkingBlock rt -> "";
            case SkillBlock sb -> Skill.renderBody(sb.skill());
        };
    }

    /**
     * The text a model reads for one message: its blocks in order, separated by blank lines,
     * blocks that render to nothing omitted. An incoming message that carries no blocks reads
     * as its text.
     */
    public static String render(Message message) {
        List<ContentBlock> blocks;
        if (message instanceof OutgoingMessage<?> outgoing) {
            blocks = outgoing.getContentBlocks();
        }
        else if (message instanceof IncomingMessage<?> incoming && incoming.hasContentBlocks()) {
            blocks = incoming.getContentBlocks();
        }
        else {
            return message.getRawContent();
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            String text = render(block);
            if (text.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        return sb.toString();
    }
}
