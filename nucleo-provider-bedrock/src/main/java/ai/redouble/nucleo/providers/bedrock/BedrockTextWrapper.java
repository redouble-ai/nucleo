/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.llm.encode.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

/**
 * The one Bedrock Converse text wrap: a string becomes a {@code ContentBlock.text}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class BedrockTextWrapper implements TextWrapper<ContentBlock> {
    @Override
    public ContentBlock wrap(String text) {
        return ContentBlock.builder().text(text).build();
    }
}
