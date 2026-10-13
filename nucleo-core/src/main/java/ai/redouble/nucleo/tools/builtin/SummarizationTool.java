/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.harness.tokens.*;
import ai.redouble.nucleo.tools.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Summarizes text of any size to a target {@link SummarySize}. A doer: every exchange with
 * the model is a {@link SummaryCall} it submits and waits on, so the tool itself holds no
 * reservation and each call carries its own hung-call guard. Text that fits the resolved
 * model is one call; text that does not is chunked on paragraph, then sentence, then word
 * boundaries, and a word past the share into characters, every piece measured by that
 * model's tokenizer, each chunk summarized at {@link SummarySize#SHORT}, and the combined
 * summaries summarized again at the requested size, on the same model.
 *
 * <p>The size reaches the prompt as a prose phrase rather than a precise number, so the model
 * is steered structurally and does not spend a thinking budget verifying length
 * character-by-character.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@DisplayName(value = "Summarize Text", action = "Summarizing text")
@ToolName("summarize_text")
@ToolDescription(value = "Summarize text to a target size. Handles arbitrarily large text via hierarchical chunking.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 5, max = 5)
public class SummarizationTool extends AbstractDoer<SummarizationInput, SummarizationOutput> implements ModelDependentTool<SummarizationInput, SummarizationOutput> {
    private static final Logger log = LoggerFactory.getLogger(SummarizationTool.class);
    static final String PROMPT_TEMPLATE = """
            Summarize the following text as %s.
            Focus on key findings, conclusions, and important facts. Be concise but informative.

            %sText to summarize:
            ---
            %s
            ---

            Provide ONLY the summary, no preamble or explanation.""";
    private Grade grade = Grade.SMALL;
    private ModelSpec pinned;

    public SummarizationTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobType getJobType() {
        return JobType.UTILITY;
    }

    @Override
    public Grade getGrade() {
        return grade;
    }

    @Override
    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    @Override
    public void pinModel(ModelSpec model) {
        this.pinned = model;
    }

    @Override
    public ModelSpec pinnedModel() {
        return pinned;
    }

    @Override
    public SummarizationOutput execute(JobContext<SummarizationOutput> context) throws LLMReadableCheckedException {
        try {
            SummarySize size = requireSize();
            String text = input.getText();
            String textContext = input.getContext();
            context.publish("Starting summarization", 5);
            int[] levels = {0};  // Track recursion depth
            String summary = summarize(text, size, textContext, context, levels, 0, pinned);
            SummarizationOutput output = new SummarizationOutput();
            output.setSummary(summary);
            output.setOriginalLength(text.length());
            output.setCompressionRatio(text.length() / (double) Math.max(1, summary.length()));
            output.setChunkingLevels(levels[0]);
            context.publish("Complete", 100);
            return output;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    /**
     * Validates that {@link SummarizationInput#getSize()} is set. The field is
     * declared {@code @LLMRequired}; this enforces the same invariant for code-level
     * callers (e.g. {@link LLMSummarizer}) that don't go through
     * the LLM input-validation path. Null here is a programming error at submit
     * time, not an LLM-correctable mistake.
     */
    private SummarySize requireSize() {
        SummarySize size = input.getSize();
        if (size == null) {
            throw new IllegalStateException(
                    "SummarizationInput.size is required - set via setSize(...) before submitting");
        }
        return size;
    }

    /**
     * Summarizes the text in one call, or chunks it when the call failed and the text is over
     * the share of the model the call resolved: the call refuses such text itself before
     * sending, and the binding's pricing refuses text past the window before the call runs,
     * so a failed call over an oversized text is the text's doing. The chunks are cut by that
     * model's tokenizer to its share, each summarized at {@link SummarySize#SHORT} on that
     * model, and the combined summaries go through the same path at the requested size. A
     * failed call over text within the share is the failure it is, rethrown.
     *
     * <p>Termination: the chunking branch is entered only when the text exceeded the model's
     * share. {@link #splitIntoChunks} guarantees two or more chunks, each within the share by
     * the model's own tokenizer, for any text that exceeded it: a paragraph -> sentence -> word
     * -> character cascade, every piece measured by that tokenizer, so no chunk is refused
     * again. Each leaf summary is bounded by {@link SummarySize#SHORT}'s backstop,
     * so the combined intermediate output is N times that backstop; reaching the share a
     * second time would require N above 300, multi-megabyte input, which the LLMSummarizer's
     * {@code maxLlmChars} ceiling also bounds.
     *
     * @param model the entry the calls run on: the tool's pin, or null to resolve the grade
     *              until a call has resolved one
     */
    private String summarize(String text, SummarySize size, String textContext, JobContext<?> jobContext,
                             int[] maxLevel, int currentLevel, ModelSpec model) throws Exception {
        maxLevel[0] = Math.max(maxLevel[0], currentLevel + 1);
        SummaryCall call = newCall(jobContext, model, text, size, textContext);
        ModelSpec resolved;
        int textTokens;
        int share;
        try {
            return submitInStep(call).get();
        }
        catch (ExecutionException e) {
            resolved = call.model();
            textTokens = resolved != null ? resolved.tokenCounter().countTokens(text) : 0;
            share = resolved != null ? SummaryCall.maxTextTokens(resolved) : 0;
            if (resolved == null || textTokens <= share) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        log.info("Text too large ({} tokens against a share of {}), chunking at level {}", textTokens, share, (currentLevel + 1));
        List<String> chunks = splitIntoChunks(text, share, resolved.tokenCounter());
        jobContext.publish("Chunked into " + chunks.size() + " parts at level " + (currentLevel + 1), 10 + currentLevel * 20);
        // Each chunk at SHORT (the fixed intermediate size), on the model whose measure cut it,
        // so every chunk fits by construction; the final combine re-summarizes at the caller's size
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            chunkSummaries.add(summarize(chunks.get(i), SummarySize.SHORT, textContext, jobContext, maxLevel, currentLevel + 1, resolved));
            jobContext.publish("Summarized chunk " + (i + 1) + "/" + chunks.size(), 20 + (70 * i / chunks.size()));
        }
        String combined = String.join("\n\n", chunkSummaries);
        return summarize(combined, size, textContext, jobContext, maxLevel, currentLevel + 1, resolved);
    }

    /** The one call this tool makes, at its grade or on the given entry. Package-private so tests can supply calls that answer without a transport. */
    SummaryCall newCall(Identifiable parent, ModelSpec model, String text, SummarySize size, String textContext) {
        return new SummaryCall(parent, grade, model, text, size, textContext);
    }

    /**
     * Builds the summarization prompt. The size becomes a prose phrase; no digit
     * reaches the model (a precise count in the prompt is what triggers small-model
     * scratchpad spirals on length verification).
     */
    static String buildPrompt(String text, SummarySize size, String textContext) {
        String contextLine = (textContext != null && !textContext.isEmpty())
            ? "Context: This is a " + textContext + ".\n\n"
            : "";
        return String.format(PROMPT_TEMPLATE, sizeToPhrase(size), contextLine, text);
    }

    /**
     * Renders a {@link SummarySize} as the prose phrase that goes into the prompt.
     * No digits - the model is steered by structure ("a paragraph") rather than a
     * count it would feel compelled to verify.
     */
    static String sizeToPhrase(SummarySize size) {
        return switch (size) {
            case BRIEF -> "a one or two sentence overview";
            case SHORT -> "a short summary of about a paragraph";
            case PARAGRAPHS -> "a summary of a couple of short paragraphs";
        };
    }

    /**
     * Hard {@code max_tokens} backstop per size. It exists only to bound runaway
     * output, so it must sit well above what a correctly-steered summary actually
     * produces - otherwise it stops being a backstop and becomes a truncation gate.
     *
     * <p>The sizes come from measured traffic, not from an estimate of the prose: a
     * summary of a couple of paragraphs reads as 250-350 tokens, but the models emit
     * structure and analysis the prose count never includes. On gpt-oss-120b the
     * PARAGRAPHS output ran p50 1148, p90 1399, p99 1778 tokens, and a backstop of
     * 1024 truncated 19.4% of PARAGRAPHS calls, each paying a full second call at the
     * model max. Each backstop clears the measured p99 with headroom, so the truncation
     * retry serves only the genuine tail.
     *
     * <ul>
     *   <li>{@code BRIEF}: one or two sentences. Backstop 768.</li>
     *   <li>{@code SHORT}: about a paragraph. Backstop 1536.</li>
     *   <li>{@code PARAGRAPHS}: a couple of short paragraphs, measured p99 ~1778.
     *       Backstop 3072 (~1.7x p99).</li>
     * </ul>
     */
    static int sizeToBackstopTokens(SummarySize size) {
        return switch (size) {
            case BRIEF -> 768;
            case SHORT -> 1536;
            case PARAGRAPHS -> 3072;
        };
    }

    /**
     * Splits text into chunks that fit within the token limit, every piece counted by the
     * tokenizer of the model that will read them: on paragraph boundaries, a paragraph too
     * large on sentence boundaries, a sentence too large on word boundaries, and a word too
     * large into characters, so any text over the limit yields two or more chunks, each within
     * it. Package-private so the cascade can be pinned without a model.
     */
    static List<String> splitIntoChunks(String text, int maxTokens, TokenCounter counter) {
        List<String> chunks = new ArrayList<>();

        // First try splitting by paragraphs
        String[] paragraphs = text.split("\n\n+");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        for (String para : paragraphs) {
            int paraTokens = counter.countTokens(para);
            if (paraTokens > maxTokens) {
                // Single paragraph too large - split by sentences
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                chunks.addAll(splitBySentences(para, maxTokens, counter));
            }
            else if (currentTokens + paraTokens > maxTokens) {
                // Adding this paragraph would exceed limit
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(para);
                currentTokens = paraTokens;
            }
            else {
                // Add paragraph to current chunk
                if (!currentChunk.isEmpty()) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(para);
                currentTokens += paraTokens;
            }
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }

    /** Splits a paragraph by sentences when it is too large; a sentence too large goes to the word step. */
    private static List<String> splitBySentences(String text, int maxTokens, TokenCounter counter) {
        return packUnits(text.split("(?<=[.!?])\\s+"), " ", maxTokens, counter,
                sentence -> splitByWords(sentence, maxTokens, counter));
    }

    /** Splits a sentence by words when it is too large; a word too large is cut into characters. */
    private static List<String> splitByWords(String text, int maxTokens, TokenCounter counter) {
        return packUnits(text.split("\\s+"), " ", maxTokens, counter,
                word -> splitIntoCharacters(word, maxTokens, counter));
    }

    /**
     * Cuts a run with no boundary inside it into pieces that fit, halving by characters until
     * the tokenizer says each half fits: the one step that needs no boundary, so the cascade
     * ends for any text.
     */
    private static List<String> splitIntoCharacters(String run, int maxTokens, TokenCounter counter) {
        if (run.length() <= 1 || counter.countTokens(run) <= maxTokens) {
            return List.of(run);
        }
        int half = run.length() / 2;
        List<String> pieces = new ArrayList<>(splitIntoCharacters(run.substring(0, half), maxTokens, counter));
        pieces.addAll(splitIntoCharacters(run.substring(half), maxTokens, counter));
        return pieces;
    }

    /**
     * Packs units into chunks under the limit, joined by the separator, each unit counted by
     * the tokenizer; a unit over the limit by itself is handed to the finer split.
     */
    private static List<String> packUnits(String[] units, String separator, int maxTokens, TokenCounter counter,
                                          java.util.function.Function<String, List<String>> finer) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        for (String unit : units) {
            if (unit.isEmpty()) {
                continue;
            }
            int unitTokens = counter.countTokens(unit);
            if (unitTokens > maxTokens) {
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                chunks.addAll(finer.apply(unit));
            }
            else if (currentTokens + unitTokens > maxTokens) {
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(unit);
                currentTokens = unitTokens;
            }
            else {
                if (!currentChunk.isEmpty()) {
                    currentChunk.append(separator);
                }
                currentChunk.append(unit);
                currentTokens += unitTokens;
            }
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }
}
