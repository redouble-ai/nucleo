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
 *   <li>Listing the registry's artifacts with their fields, long ones as their summaries</li>
 * </ul>
 *
 * <p>It makes no summary that needs a model: the listing shows the summaries the artifacts
 * keep, which {@link ConversationContext#summarizeArtifacts} makes before a call is sent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public class ContentProcessor {
    /** What the registry's listing opens with: how a model reads the whole of what it is shown in part. */
    static final String REGISTRY_HEADER = "\n\n=== ARTIFACT REGISTRY ===\n"
            + "A long field shows a summary, marked [SUMMARY: N chars], in place of its value; N is the length of the whole value. "
            + "Use get_artifact_field(ref, fieldName) to read a field whole. "
            + "Use search_artifact_content(query) to search the whole values of every artifact.\n\n";
    private final ConversationContext context;

    /**
     * Creates a new ContentProcessor.
     *
     * @param context the conversation context containing the artifact registry
     */
    public ContentProcessor(ConversationContext context) {
        this.context = context;
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
            String json = NucleoJsonSerializer.writeSummarizedWithRefs(pojo, context.getArtifactRegistry());
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
     * <p>Each artifact is serialized through {@code NucleoJsonSerializer.writeSummarized},
     * so a long field appears as "[SUMMARY: X chars] Brief summary..." rather than its
     * whole value, and a field marked {@code @LLMContextIgnore} does not appear.
     *
     * @return formatted string containing all artifacts in the registry
     */
    public String buildArtifactRegistrySection() {
        Map<String, Artifact> artifacts = context.getArtifactRegistry().getAllArtifacts();
        if (artifacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(REGISTRY_HEADER);
        for (Map.Entry<String, Artifact> entry : artifacts.entrySet()) {
            sb.append("--- ").append(entry.getKey()).append(" ---\n");
            String artifactJson = NucleoJsonSerializer.writeSummarized(entry.getValue());
            sb.append(artifactJson);
            sb.append("\n\n");
        }
        return sb.toString();
    }
}
