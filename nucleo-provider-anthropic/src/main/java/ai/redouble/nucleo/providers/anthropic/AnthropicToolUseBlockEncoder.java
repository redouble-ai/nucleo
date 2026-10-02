/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.schema.*;
import com.anthropic.core.*;
import com.anthropic.models.messages.*;
import org.slf4j.*;

import java.util.*;

/**
 * Anthropic native tool-use: a {@code ToolUseBlockParam} carrying the parsed input object, so the
 * model sees a real assistant tool call rather than text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicToolUseBlockEncoder extends ToolUseBlockEncoder<ContentBlockParam> {
    private static final Logger log = LoggerFactory.getLogger(AnthropicToolUseBlockEncoder.class);
    @Override
    @SuppressWarnings("unchecked")
    public ContentBlockParam encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.ToolUseBlock tu = (ContentBlocks.ToolUseBlock) block;
        try {
            Map<String, Object> input = NucleoJsonSerializer.parse(tu.inputJson(), Map.class);
            return ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(tu.toolUseId()).name(tu.toolName()).input(JsonValue.from(input)).build());
        }
        catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new UncorrectableRuntimeLLMException("Failed to convert tool use input for " + tu.toolName() + ": " + e.getMessage(), e);
        }
    }
}
