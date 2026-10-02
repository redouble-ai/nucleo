/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.prompt.skill.*;

/**
 * Default {@link SkillBlock} encoder: the skill's body rendering, wrapped as a provider text block.
 * (The primary skill path is the system prefix; this handles a SkillBlock that arrives in the
 * per-message content list, e.g. snapshot replay.)
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class SkillBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        return textWrapper.wrap(Skill.renderBody(((SkillBlock) block).skill()));
    }
}
