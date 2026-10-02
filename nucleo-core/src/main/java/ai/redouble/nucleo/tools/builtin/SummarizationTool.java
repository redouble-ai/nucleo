/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.util.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;

/**
 * Smart summarization tool that handles text of any size.
 *
 * <p>For text that fits in context, directly summarizes via LLM.
 * For text too large for context, uses hierarchical chunking:
 * splits into chunks, summarizes each, then summarizes the summaries.
 *
 * <p>Target output size is supplied as a {@link SummarySize} - rendered as a prose
 * prompt phrase rather than a precise number so the model is steered structurally
 * and does not spend a thinking budget verifying length character-by-character.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@DisplayName(value = "Summarize Text", action = "Summarizing text")
@ToolName("summarize_text")
@ToolDescription(value = "Summarize text to a target size. Handles arbitrarily large text via hierarchical chunking.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 5, max = 5)
public class SummarizationTool extends AbstractModelDependentTool<SummarizationInput, SummarizationOutput> {
    private static final Logger log = LoggerFactory.getLogger(SummarizationTool.class);
    static final String PROMPT_TEMPLATE = """
            Summarize the following text as %s.
            Focus on key findings, conclusions, and important facts. Be concise but informative.

            %sText to summarize:
            ---
            %s
            ---

            Provide ONLY the summary, no preamble or explanation.""";
    private static final double CONTEXT_RESERVE = 0.2;  // Reserve 20% for output and overhead
    private ModelBinding binding;

    public SummarizationTool(Identifiable parent) {
        super(parent, Grade.SMALL);
        // 6 min: hung-call guard for one LLM call - summarizing a long input can run far past the
        // typical few seconds. A ceiling, not expected latency (matches LLMCall).
        setTimeout(Duration.ofMinutes(6));
    }

    @Override
    public JobType getJobType() {
        return JobType.UTILITY;
    }

    @Override
    public JobRequirements getRequirements() {
        SummarySize size = requireSize();
        JobRequirements req = new JobRequirements();
        // Prompt-form reservation: the input text counted under the RESOLVED spec's
        // tokenizer plus the size-derived output backstop. The backstop is also what we
        // send as max_tokens (see summarizeDirect), keeping local
        // TPM accounting and upstream pre-debit in lock-step. Recursive chunking walks the
        // call path more than once; the full-text count covers the total the recursion
        // feeds through, and additional intermediate calls draw from the TPM bucket's
        // background refill.
        OutputDeclaration output = OutputDeclaration.of(sizeToBackstopTokens(size));
        if (pinnedModel() != null) {
            // a pin names the tokenizer up front, so the prompt is counted under it here
            int inTokens = TokenizerFactory.get().forModel(pinnedModel()).countTokens(input.getText());
            binding = req.requireModel(pinnedModel(), Depth.IMMEDIATE, inTokens, output);
        }
        else {
            binding = req.requireModelForPrompt(getGrade(), Depth.IMMEDIATE, input.getText(), output);
        }
        req.setRequiresTransaction(false);
        return req;
    }

    @Override
    public SummarizationOutput execute(JobResources resources, JobContext<SummarizationOutput> context) throws LLMReadableCheckedException {
        try {
            SummarySize size = requireSize();
            String text = input.getText();
            String textContext = input.getContext();
            context.publish("Starting summarization", 5);
            LLMClient client = resources.getLLMClient(binding.getModel());
            int[] levels = {0};  // Track recursion depth
            String summary = summarizeRecursive(text, size, textContext, client, context, levels, 0);
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
     * Recursively summarizes text, chunking if necessary.
     *
     * <p>Termination: chunking branch only entered when {@code textTokens > maxInputTokens}.
     * {@link #splitIntoChunks} structurally guarantees >= 2 chunks for any text that
     * exceeded the threshold (paragraph -> sentence -> {@link Texts#splitAtWords} cascade
     * divides unconditionally). Each leaf summary is bounded by {@link SummarySize#SHORT}'s
     * backstop, so combined intermediate output is N * SHORT-backstop tokens; reaching
     * the chunking threshold a second time would require N > 300, i.e. multi-megabyte
     * input, which is also bounded by the LLMSummarizer's {@code maxLlmChars} ceiling.
     */
    private String summarizeRecursive(String text, SummarySize size, String textContext,
                                       LLMClient client, JobContext<?> jobContext,
                                       int[] maxLevel, int currentLevel) throws Exception {
        maxLevel[0] = Math.max(maxLevel[0], currentLevel + 1);
        ModelSpec model = binding.getModel();
        int textTokens = TokenizerFactory.get().forModel(model).countTokens(text);
        int maxInputTokens = (int) (model.getMaxContextTokens() * (1 - CONTEXT_RESERVE));
        if (textTokens <= maxInputTokens) {
            // Text fits in context - direct summarization
            return summarizeDirect(text, size, textContext, client);
        }
        // Text too large - chunk and recurse
        log.info("Text too large ({} tokens), chunking at level {}", textTokens, (currentLevel + 1));
        List<String> chunks = splitIntoChunks(text, maxInputTokens);
        jobContext.publish("Chunked into " + chunks.size() + " parts at level " + (currentLevel + 1), 10 + currentLevel * 20);

        // Summarize each chunk at SHORT (fixed intermediate size; the final combine
        // re-summarizes at the caller's requested size).
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkSummary = summarizeRecursive(chunk, SummarySize.SHORT, textContext, client, jobContext, maxLevel, currentLevel + 1);
            chunkSummaries.add(chunkSummary);
            jobContext.publish("Summarized chunk " + (i + 1) + "/" + chunks.size(), 20 + (70 * i / chunks.size()));
        }

        // Combine summaries and summarize again at the requested size
        String combined = String.join("\n\n", chunkSummaries);
        return summarizeRecursive(combined, size, textContext, client, jobContext, maxLevel, currentLevel + 1);
    }

    /**
     * Direct LLM summarization for text that fits in context.
     */
    private String summarizeDirect(String text, SummarySize size, String textContext, LLMClient client) throws Exception {
        // The backstop is the sole output ceiling; a conversation per call keeps a truncation
        // escalation from restoring the runaway this tool exists to prevent
        ConversationContext conv = ConversationContext.singleTurn(binding.getModel(), buildPrompt(text, size, textContext), sizeToBackstopTokens(size));
        LLMResponse<String> response = client.singleResponse(new LLMRequest<>(conv));
        return response.getResponseMessage().getRawContent().trim();
    }

    /**
     * Builds the summarization prompt. The size becomes a prose phrase; no digit
     * reaches the model (a precise count in the prompt is what triggers small-model
     * scratchpad spirals on length verification).
     */
    private String buildPrompt(String text, SummarySize size, String textContext) {
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
     * <p>The original sizes (256/512/1024) were set from an estimate of the prose
     * alone (a couple of paragraphs ~= 250-350 tokens). Measured against real
     * traffic that estimate was wrong by 4-5x: on gpt-oss-120b the PARAGRAPHS
     * output that exceeded the old 1024 ceiling ran p50 1148, p90 1399, p99 1778
     * tokens (the models emit structure and analysis the prose count never
     * included), and 19.4% of PARAGRAPHS calls truncated at 1024 and paid a full
     * second call to retry at the model max. The backstops now clear the measured
     * p99 with headroom; the truncation retry remains only for the genuine tail.
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
     * Splits text into chunks that fit within the token limit.
     * Tries to split on paragraph or sentence boundaries.
     */
    private List<String> splitIntoChunks(String text, int maxTokens) {
        List<String> chunks = new ArrayList<>();

        // First try splitting by paragraphs
        String[] paragraphs = text.split("\n\n+");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        for (String para : paragraphs) {
            int paraTokens = TokenizerFactory.get().forModel(binding.getModel()).countTokens(para);
            if (paraTokens > maxTokens) {
                // Single paragraph too large - split by sentences
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                chunks.addAll(splitBySentences(para, maxTokens));
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

    /**
     * Splits a paragraph by sentences when it's too large.
     */
    private List<String> splitBySentences(String text, int maxTokens) {
        List<String> chunks = new ArrayList<>();

        // Simple sentence splitting - split on . ! ? followed by space or end
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        for (String sentence : sentences) {
            int sentenceTokens = TokenizerFactory.get().forModel(binding.getModel()).countTokens(sentence);
            if (sentenceTokens > maxTokens) {
                // Single sentence too large - force split by character count
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                // Approximate: 1 token ~= 4 characters for English
                chunks.addAll(Texts.splitAtWords(sentence, maxTokens * 4));
            }
            else if (currentTokens + sentenceTokens > maxTokens) {
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(sentence);
                currentTokens = sentenceTokens;
            }
            else {
                if (!currentChunk.isEmpty()) {
                    currentChunk.append(" ");
                }
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
            }
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }
}
