/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.*;

/**
 * A named, identified, guardrailed unit of instructional content destined for an LLM.
 *
 * <p>Every Prompt is produced through the {@link Prompts} facade, which is the only
 * public entry point for obtaining Prompt instances. Direct construction is not supported.
 *
 * <p>A Prompt has three invariants:
 * <ul>
 *   <li>{@link #key()} - semantic identifier; first-class. Two Prompts with identical content
 *       under different keys are distinct Prompts.</li>
 *   <li>{@link #content()} - canonical content as a {@link JsonNode}. Text prompts produce
 *       {@code TextNode}; structured prompts (e.g., skills) produce {@code ObjectNode}.</li>
 *   <li>{@link #context()} - lineage metadata (content hash, produced-at, optional version/variant).
 *       Not serialized over the wire; marked {@code @JsonIgnore}. Represents identity, not content.</li>
 * </ul>
 *
 * <p>Polymorphism of content shape lives inside {@link JsonNode}, not in the Prompt type.
 * The single shipped implementation is {@link TextPrompt}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public interface Prompt {
    /** Semantic identifier. Shape: {@code <domain>.<feature>.<role>}, e.g. {@code pharma.drugbank.system-msg}. */
    String key();

    /** Canonical content: {@code TextNode} for text, {@code ObjectNode} for structured prompts. */
    JsonNode content();

    /** Lineage metadata. Never serialized into the wire or persisted within the Prompt itself. */
    @JsonIgnore
    PromptContext context();
}