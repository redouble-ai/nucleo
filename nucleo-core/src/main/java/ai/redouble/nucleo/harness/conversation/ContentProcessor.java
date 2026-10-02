/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Processes content blocks with context awareness, particularly for artifact handling.
 * This layer sits between ConversationContext and ContentFormatter, handling the
 * conversion of artifacts to references for LLM consumption.
 *
 * <p>The ContentProcessor is responsible for:
 * <ul>
 *   <li>Processing PojoBlocks through NucleoJsonSerializer.writeSummarizedWithRefs to replace artifacts with refs</li>
 *   <li>Registering artifacts in the conversation's registry</li>
 *   <li>Passing through other block types unchanged</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public class ContentProcessor {
    private final ConversationContext context;
    private final Summarizer summarizer;

    /**
     * Creates a new ContentProcessor with default truncation summarizer.
     *
     * @param context the conversation context containing the artifact registry
     */
    public ContentProcessor(ConversationContext context) {
        this.context = context;
        this.summarizer = new TruncatingSummarizer();
    }

    /**
     * Creates a new ContentProcessor with the specified summarizer.
     *
     * @param context the conversation context containing the artifact registry
     * @param summarizer the summarization strategy
     */
    public ContentProcessor(ConversationContext context, Summarizer summarizer) {
        this.context = context;
        this.summarizer = summarizer;
    }

    /**
     * Processes a single content block for LLM consumption.
     * PojoBlocks are processed through NucleoJsonSerializer.writeSummarizedWithRefs to handle artifacts,
     * while other blocks pass through unchanged.
     *
     * @param block the content block to process
     * @return the processed block ready for LLM consumption
     */
    public ContentBlock processForLLM(ContentBlock block) {
        if (block instanceof PojoBlock(Object pojo)) {
            String json = NucleoJsonSerializer.writeSummarizedWithRefs(pojo, context.getArtifactRegistry(), summarizer);
            return new JsonBlock(json);
        }
        return block;
    }

    /**
     * Processes a list of content blocks for LLM consumption.
     *
     * @param blocks the blocks to process
     * @return list of processed blocks
     */
    public List<ContentBlock> processForLLM(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return blocks;
        }

        List<ContentBlock> processed = new ArrayList<>();
        for (ContentBlock block : blocks) {
            processed.add(processForLLM(block));
        }
        return processed;
    }

    /**
     * Builds text content from processed blocks, applying the given formatter.
     * This combines processing and formatting in one step.
     *
     * @param blocks the blocks to process and format
     * @param formatter the formatter to use for text generation
     * @return formatted text content with artifacts as references
     */
    public String buildTextContent(List<ContentBlock> blocks, ContentFormatter formatter) {
        List<ContentBlock> processed = processForLLM(blocks);
        return formatter.buildTextContent(processed);
    }

    /**
     * Generates the artifact registry section for appending to LLM context.
     * This should be added at the end of the conversation for cache efficiency.
     *
     * <p>Each artifact is serialized through {@code NucleoJsonSerializer.writeSummarized}
     * with this processor's summarizer, so long text fields appear as
     * "[SUMMARY: X chars] Brief summary..." rather than full text.
     *
     * @return formatted string containing all artifacts in the registry
     */
    public String buildArtifactRegistrySection() {
        Map<String, Artifact> artifacts = context.getArtifactRegistry().getAllArtifacts();
        if (artifacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== ARTIFACT REGISTRY ===\n");
        sb.append("Long text fields show summaries with original length. ");
        sb.append("Use get_artifact_field(ref, fieldName) for full content. ");
        sb.append("Use search_artifact_content(query) to search across all artifact text.\n\n");
        for (Map.Entry<String, Artifact> entry : artifacts.entrySet()) {
            sb.append("--- ").append(entry.getKey()).append(" ---\n");
            String artifactJson = NucleoJsonSerializer.writeSummarized(entry.getValue(), summarizer);
            sb.append(artifactJson);
            sb.append("\n\n");
        }
        return sb.toString();
    }
}