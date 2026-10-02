/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import org.slf4j.*;

/**
 * Summarizer that spawns {@link SummarizationTool} jobs for high-quality LLM-generated summaries.
 *
 * <p>Respects the {@link LLMSummarizable} annotation:
 * <ul>
 *   <li>{@code staticSummary} - if set, uses the static label (no LLM call)</li>
 *   <li>{@code llmSafe} - if false, falls back to truncation (content would be damaged by LLM)</li>
 *   <li>{@code value} (hint) - passed to SummarizationTool as context</li>
 *   <li>{@code size} - target summary size, passed to SummarizationTool</li>
 * </ul>
 *
 * <p>Falls back to truncation if the LLM job fails or text exceeds the max char ceiling.
 *
 * <p><b>Only use from orchestrators (Thinkers/Doers).</b> This summarizer blocks on
 * {@code handle.get()} while waiting for the summarization job. The job system's deadlock
 * detection will throw {@link JobDeadlockException} if called from a resource-bearing Tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-03)
 */
public class LLMSummarizer implements Summarizer {
    private static final Logger log = LoggerFactory.getLogger(LLMSummarizer.class);
    private static final int DEFAULT_MAX_LLM_CHARS = 200_000;
    private final Identifiable parent;
    private final int maxLlmChars;
    private final TruncatingSummarizer fallback;

    /**
     * Creates an LLM summarizer with default max char ceiling (200K).
     *
     * @param parent the parent job/thinker for spawning SummarizationTool child jobs
     */
    public LLMSummarizer(Identifiable parent) {
        this.parent = parent;
        this.maxLlmChars = DEFAULT_MAX_LLM_CHARS;
        this.fallback = new TruncatingSummarizer();
    }

    @Override
    public String summarize(String text, LLMSummarizable annotation) {
        int length = text.length();

        // Priority 1: Static summary from annotation
        if (annotation != null && annotation.staticSummary() != null && !annotation.staticSummary().isEmpty()) {
            return String.format("[SUMMARY: %d chars] %s", length, annotation.staticSummary());
        }

        // Priority 2: LLM summarization (if content is LLM-safe and within size limit)
        boolean llmSafe = annotation == null || annotation.llmSafe();
        if (llmSafe && text.length() <= maxLlmChars) {
            try {
                String hint = (annotation != null && annotation.value() != null && !annotation.value().isEmpty())
                    ? annotation.value() : "";
                SummarySize size = annotation != null ? annotation.size() : SummarySize.SHORT;
                String llmSummary = summarizeViaJob(text, hint, size);
                return String.format("[SUMMARY: %d chars] %s", length, llmSummary);
            }
            catch (Exception e) {
                log.warn("LLM summarization job failed, falling back to truncation: {}", e.getMessage());
            }
        }

        // Priority 3: Truncation fallback
        return fallback.summarize(text, annotation);
    }

    /** The job seam; package-private so tests can pin the fallback chain without dispatching. */
    String summarizeViaJob(String text, String hint, SummarySize size) throws Exception {
        SummarizationTool tool = new SummarizationTool(parent);
        SummarizationInput input = new SummarizationInput();
        input.setText(text);
        input.setSize(size);
        if (hint != null && !hint.isEmpty()) {
            input.setContext(hint);
        }
        tool.setInput(input);
        JobHandle<SummarizationOutput> handle = JobDispatcher.getInstance().submit(tool);
        SummarizationOutput output = handle.get();
        return output.getSummary();
    }
}
