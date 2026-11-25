/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.concurrent.*;

/**
 * Asks a model for the summary of one text, as a {@link SummarizationTool} job that is a
 * child of the thinker or doer this summarizer is made for.
 *
 * <p>This is the model step of the serializer's one decision and nothing more: whether a
 * field is summarized by a fixed phrase, a named class, a cut or a model is decided there,
 * and a text reaches this class only when the answer is a model. The annotation's
 * {@code value} is handed to the tool as the content's description and its {@code size} as
 * the target length.
 *
 * <p>It makes no fallback of its own. A job that fails, is refused or is cancelled is
 * thrown to the serializer, which cuts the text for that write and keeps nothing.
 *
 * <p><b>Only use from orchestrators (Thinkers/Doers).</b> This summarizer blocks on
 * {@code handle.get()} while waiting for the summarization job. The job system's deadlock
 * detection will throw {@link JobDeadlockException} if called from a resource-bearing Tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-03)
 */
public class LLMSummarizer implements Summarizer {
    private final Identifiable parent;

    /**
     * Creates the summarizer of one thinker or doer.
     *
     * @param parent the parent job/thinker for spawning SummarizationTool child jobs
     */
    public LLMSummarizer(Identifiable parent) {
        this.parent = parent;
    }

    /**
     * The model's summary of the text, marked with the length of the whole.
     *
     * @throws SummarizationFailedException when the job fails, is refused or is cancelled,
     *         or the waiting thread is interrupted; the cause is what the job reported
     */
    @Override
    public String summarize(String text, LLMSummarizable annotation) {
        try {
            return String.format("[SUMMARY: %d chars] %s", text.length(), summarizeViaJob(text, annotation.value(), annotation.size()));
        }
        catch (InterruptedException | ExecutionException e) {
            throw new SummarizationFailedException(e);
        }
    }

    /** The job seam; package-private so tests can pin the decision without dispatching. */
    String summarizeViaJob(String text, String hint, SummarySize size) throws InterruptedException, ExecutionException {
        JobHandle<SummarizationOutput> handle = JobDispatcher.getInstance().submit(job(text, hint, size));
        SummarizationOutput output = handle.get();
        return output.getSummary();
    }

    /** The job that makes one summary: a child of the job this summarizer was made for. */
    SummarizationTool job(String text, String hint, SummarySize size) {
        SummarizationTool tool = new SummarizationTool(parent);
        SummarizationInput input = new SummarizationInput();
        input.setText(text);
        input.setSize(size);
        if (!hint.isEmpty()) {
            input.setContext(hint);
        }
        tool.setInput(input);
        return tool;
    }

    /** A summarization job that produced no summary; the cause says why. */
    public static class SummarizationFailedException extends RuntimeException {
        public SummarizationFailedException(Throwable cause) {
            super("The summarization job produced no summary", cause);
        }
    }
}
