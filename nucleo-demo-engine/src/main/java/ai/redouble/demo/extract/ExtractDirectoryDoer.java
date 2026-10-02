/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.slf4j.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * The demo's workflow: every file under a directory, read-only, each routed to the cheapest
 * tier that can read it, all of them in parallel under admission, and a report of what each
 * one cost. Code reads what code can read; a model that can see reads scans and images; a
 * small model judges what nothing else could place; and what came out is embedded into the
 * index so a person can ask the directory a question afterwards.
 *
 * <p>A doer, because it coordinates and holds nothing: every file is a child job with its
 * own admission, and the dispatcher drains them at the rate the account's limits allow. The
 * request's budgets become the workflow's spend caps before the first child is submitted;
 * a child refused at the cap is a row in the report and the run continues to its end,
 * refusing the rest cheaply.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
@ToolName("extract_directory")
@ToolDescription(value = "Extracts the text of every file under a directory through the cheapest tier that reads it, in parallel, and indexes the result.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class ExtractDirectoryDoer extends AbstractDoer<ExtractRequest, ExtractReport> {
    private static final Logger log = LoggerFactory.getLogger(ExtractDirectoryDoer.class);
    /** Bytes the classifier sees. */
    static final int HEAD_BYTES = 2048;
    private final CostLedger ledger;
    private final FileIndex index;
    /** The directory the run reads: the host's choice, never the request's. */
    private final Path directory;
    /** The reader for text/Office/PDF, chosen by which implementation is on the classpath (POI when present, else toolkit-free). */
    private final ExtractionTools tools = ExtractionTools.resolve();

    /**
     * @param directory the directory to read, an existing, readable one; the host names it,
     *                  which is why a bad one is a programming error here rather than an
     *                  input the caller can correct
     */
    public ExtractDirectoryDoer(Identifiable parent, CostLedger ledger, FileIndex index, Path directory) {
        super(parent);
        if (!Files.isDirectory(directory) || !Files.isReadable(directory)) {
            throw new IllegalArgumentException(directory + " is not an existing, readable directory");
        }
        this.ledger = ledger;
        this.index = index;
        this.directory = directory;
    }

    /** A child job to build once its round comes; building reads the file, so it waits for the round. */
    private interface Planned {
        AbstractTool<?, ?> build() throws LLMReadableCheckedException;
    }

    /** One child job and what it was for: submitted with a handle, or planned for the next round with a builder. */
    private record Submitted(String path, Tier tier, JobHandle<?> handle, Planned planned) {}

    @Override
    public ExtractReport execute(JobContext<ExtractReport> context) throws LLMReadableCheckedException {
        Instant started = Instant.now();
        if (input.getBudgets() != null) {
            for (ExtractRequest.Budget budget : input.getBudgets()) {
                ledger.cap(context.getWorkflowId(), new Cost(budget.getAmount(), budget.getCurrency()));
            }
        }
        List<Path> files = list(directory);
        Map<String, ExtractReport.FileOutcome> rows = new LinkedHashMap<>();
        Map<String, String> jobsByPath = new HashMap<>();
        for (Path file : files) {
            ExtractReport.FileOutcome row = new ExtractReport.FileOutcome();
            row.setPath(directory.relativize(file).toString());
            rows.put(file.toString(), row);
        }
        context.publish("Routing " + files.size() + " files", 5);
        // round one: every file to the tier its bytes say, all at once; what a round learns
        // (a PDF with pictures for pages, a text extension over a binary, a classifier's
        // verdict) is the next round's work, until nothing is left to learn
        Map<String, Extraction> extracted = new LinkedHashMap<>();
        // a PDF the deterministic reader partly read: the text of its pages, held while the model reads its scans
        Map<String, Extraction> partial = new HashMap<>();
        List<Submitted> round = new ArrayList<>();
        nextStep();
        for (Path file : files) {
            context.checkCancellation();
            ExtractReport.FileOutcome row = rows.get(file.toString());
            try {
                Sniff.Kind kind = Sniff.of(file);
                if (Sniff.isImage(kind)) {
                    round.add(submit(file, Tier.VISION, vision(file, null), jobsByPath));
                }
                else if (kind == Sniff.Kind.PDF && !tools.readsPdfLocally()) {
                    // the reader on the classpath does not open PDFs: the file goes whole to the model
                    round.add(submit(file, Tier.VISION, vision(file, null), jobsByPath));
                }
                else if (kind == Sniff.Kind.PDF || kind == Sniff.Kind.OFFICE
                        || DeterministicExtractTool.TEXT.contains(DeterministicExtractTool.extension(file.toString()))) {
                    round.add(submit(file, Tier.DETERMINISTIC, deterministic(file, false), jobsByPath));
                }
                else {
                    round.add(submit(file, Tier.CLASSIFIER, classifier(file), jobsByPath));
                }
            }
            catch (IOException e) {
                row.setTier(Tier.FAILED);
                row.setNote("cannot read the first bytes: " + e.getMessage());
            }
        }
        context.publish("Submitted " + round.size() + " jobs", 15);
        int roundNumber = 1;
        while (!round.isEmpty()) {
            List<Submitted> next = new ArrayList<>();
            for (Submitted submitted : round) {
                ExtractReport.FileOutcome row = rows.get(submitted.path());
                Path file = Path.of(submitted.path());
                Object result = await(submitted, row);
                if (result instanceof Extraction extraction) {
                    if (extraction.getScanPages() != null && submitted.tier() == Tier.DETERMINISTIC) {
                        // pages of pictures go to the vision tier; the text of the other pages waits here
                        partial.put(submitted.path(), extraction);
                        next.add(new Submitted(submitted.path(), Tier.VISION, null, () -> vision(file, extraction.getScanPages())));
                    }
                    else if (!extraction.isTextLayer() && submitted.tier() == Tier.DETERMINISTIC) {
                        // a text extension over bytes that do not decode: the classifier's question
                        next.add(new Submitted(submitted.path(), Tier.CLASSIFIER, null, () -> classifier(file)));
                    }
                    else {
                        Extraction earlier = partial.remove(submitted.path());
                        if (earlier != null) {
                            merge(earlier, extraction);
                        }
                        accept(row, submitted.tier(), extraction, extracted, submitted.path());
                    }
                }
                else if (result instanceof Classification classification) {
                    switch (classification.getKind()) {
                        case TEXT -> next.add(new Submitted(submitted.path(), Tier.CLASSIFIER, null, () -> deterministic(file, true)));
                        case BINARY -> {
                            row.setTier(Tier.SKIPPED);
                            row.setNote(classification.getReason());
                        }
                        case NEEDS_PERSON -> {
                            row.setTier(Tier.NEEDS_PERSON);
                            row.setNote(classification.getReason());
                        }
                    }
                }
            }
            round = new ArrayList<>();
            if (!next.isEmpty()) {
                roundNumber++;
                context.publish("Round " + roundNumber + ": " + next.size() + " files the last round learned about", 20 + Math.min(roundNumber * 10, 40));
                nextStep();
                for (Submitted planned : next) {
                    round.add(submit(Path.of(planned.path()), planned.tier(), planned.planned().build(), jobsByPath));
                }
            }
        }
        // last: every text into the index
        context.publish("Embedding " + extracted.size() + " texts", 75);
        nextStep();
        List<Submitted> embeds = new ArrayList<>();
        for (Map.Entry<String, Extraction> e : extracted.entrySet()) {
            TextToEmbed toEmbed = new TextToEmbed();
            toEmbed.setKey(e.getKey());
            String text = e.getValue().getText();
            toEmbed.setText(text.length() > FileIndex.INDEXED_CHARS ? text.substring(0, FileIndex.INDEXED_CHARS) : text);
            toEmbed.setPurpose(EmbeddingPurpose.DOCUMENT);
            EmbedTextTool tool = new EmbedTextTool(this);
            tool.setInput(toEmbed);
            embeds.add(submit(Path.of(e.getKey()), null, tool, jobsByPath));
        }
        int indexed = 0;
        for (Submitted submitted : embeds) {
            ExtractReport.FileOutcome row = rows.get(submitted.path());
            try {
                Embedding embedding = (Embedding) submitted.handle().get();
                // the whole text is kept: the vector is the head's, the text is what a later workflow reads
                index.put(row.getPath(), extracted.get(submitted.path()).getText(), embedding);
                indexed++;
            }
            catch (ExecutionException e) {
                row.setNote(join(row.getNote(), "not indexed: " + reason(e.getCause())));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("extract_directory", "interrupted while indexing", e);
            }
        }
        context.publish("Indexed " + indexed, 95);
        return report(context, directory, files.size(), started, rows, jobsByPath, indexed);
    }

    private static List<Path> list(Path directory) throws LLMReadableCheckedException {
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .collect(Collectors.toList());
            return files;
        }
        catch (IOException e) {
            throw new SystemException("extract_directory", "Cannot walk " + directory, e);
        }
    }

    private Submitted submit(Path file, Tier tier, AbstractTool<?, ?> tool, Map<String, String> jobsByPath) {
        JobHandle<?> handle = submitInCurrentStep(tool);
        jobsByPath.put(handle.getJobId(), file.toString());
        return new Submitted(file.toString(), tier, handle, null);
    }

    private AbstractTool<FileRef, Extraction> deterministic(Path file, boolean asText) {
        FileRef ref = new FileRef();
        ref.setPath(file.toString());
        ref.setAsText(asText);
        return tools.deterministic(this, ref);
    }

    private AbstractTool<FileRef, Extraction> vision(Path file, List<Integer> pages) {
        FileRef ref = new FileRef();
        ref.setPath(file.toString());
        ref.setPages(pages);
        return tools.vision(this, ref);
    }

    /** A mixed PDF: the text of the pages the code read, then what the model read on the others. */
    private static void merge(Extraction fromCode, Extraction fromModel) {
        StringBuilder text = new StringBuilder();
        if (fromCode.getText() != null) {
            text.append(fromCode.getText());
        }
        if (fromModel.getText() != null) {
            if (text.length() > 0) {
                text.append("\n\n[pages ").append(fromCode.getScanPages()).append(", transcribed]\n");
            }
            text.append(fromModel.getText());
        }
        fromModel.setText(text.length() > 0 ? text.toString() : null);
        fromModel.setTextLayer(fromCode.getText() != null || fromModel.isTextLayer());
        fromModel.setPages(fromCode.getPages());
        fromModel.setNote(fromCode.getNote() + (fromModel.getNote() != null ? "; " + fromModel.getNote() : ""));
    }

    private ClassifyFileTool classifier(Path file) throws LLMReadableCheckedException {
        FileHead head = new FileHead();
        head.setName(file.getFileName().toString());
        try {
            head.setSizeBytes(Files.size(file));
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(HEAD_BYTES);
            }
            head.setSampled(bytes.length);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            String text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            int undecodable = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '�' || (text.charAt(i) < 0x20 && text.charAt(i) != '\n' && text.charAt(i) != '\r' && text.charAt(i) != '\t')) {
                    undecodable++;
                }
            }
            head.setUndecodable(undecodable);
            head.setHead(text);
        }
        catch (IOException e) {
            throw new SystemException("extract_directory", "Cannot read the head of " + file, e);
        }
        ClassifyFileTool tool = new ClassifyFileTool(this);
        tool.setInput(head);
        return tool;
    }

    /** The child's result, or null with the row marked when it failed or was refused. */
    private Object await(Submitted submitted, ExtractReport.FileOutcome row) throws LLMReadableCheckedException {
        try {
            return submitted.handle().get();
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isSpendCap(cause)) {
                row.setTier(Tier.REFUSED);
                row.setNote(reason(cause));
            }
            else {
                row.setTier(Tier.FAILED);
                row.setNote(submitted.tier() + ": " + reason(cause));
            }
            return null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemException("extract_directory", "interrupted while waiting for " + submitted.path(), e);
        }
    }

    private static boolean isSpendCap(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SpendCapExceededException) {
                return true;
            }
        }
        return false;
    }

    private static String reason(Throwable t) {
        Throwable deepest = t;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (deepest != t && deepest.getMessage() != null && !message.contains(deepest.getMessage())) {
            message += " (" + deepest.getMessage() + ")";
        }
        return message;
    }

    private static void accept(ExtractReport.FileOutcome row, Tier tier, Extraction extraction, Map<String, Extraction> extracted, String path) {
        row.setTier(tier);
        row.setNote(extraction.getNote());
        if (extraction.getText() != null && !extraction.getText().isBlank()) {
            row.setChars(extraction.getText().length());
            extracted.put(path, extraction);
        }
        else if (!extraction.isTextLayer()) {
            row.setTier(Tier.SKIPPED);
        }
    }

    /**
     * Each call to the file it served, so a row carries what its own tiers cost. The row's model
     * is the one that read the file: the embeddings call that indexed the text adds to the row's
     * tokens and cost and never names its reader, so a file code read carries no model. Costs sum
     * per currency and never across: a row priced in more than one currency carries no cost and a
     * note saying so, and the run's totals hold the per-currency truth.
     */
    static void attribute(List<CostLedger.Call> calls, Map<String, String> jobsByPath, Map<String, ExtractReport.FileOutcome> rows,
                          String embeddingsModelId) {
        for (CostLedger.Call call : calls) {
            String path = jobsByPath.get(call.jobId());
            ExtractReport.FileOutcome row = path != null ? rows.get(path) : null;
            if (row == null) {
                continue;
            }
            if (embeddingsModelId == null || !embeddingsModelId.equals(call.modelId())) {
                row.setModelId(call.modelId());
                row.setServedModelId(call.servedModelId());
            }
            row.setInputTokens((row.getInputTokens() != null ? row.getInputTokens() : 0) + call.inputTokens());
            row.setOutputTokens((row.getOutputTokens() != null ? row.getOutputTokens() : 0) + call.outputTokens());
            row.setLatencyMs((row.getLatencyMs() != null ? row.getLatencyMs() : 0) + call.latencyMs());
            if (call.cost() != null) {
                if (row.getCurrency() != null && !row.getCurrency().equals(call.cost().currency())) {
                    row.setCost(null);
                    row.setNote(join(row.getNote(), "priced in more than one currency; see the run's totals"));
                }
                else {
                    row.setCurrency(call.cost().currency());
                    row.setCost(new Cost(row.getCost() != null ? row.getCost() : 0.0, call.cost().currency()).plus(call.cost()).amount());
                }
            }
        }
    }

    private static String join(String a, String b) {
        return a == null || a.isEmpty() ? b : a + "; " + b;
    }

    private ExtractReport report(JobContext<ExtractReport> context, Path directory, int seen, Instant started,
                                 Map<String, ExtractReport.FileOutcome> rows, Map<String, String> jobsByPath, int indexed) {
        ExtractReport report = new ExtractReport();
        report.setWorkflowId(context.getWorkflowId());
        report.setDirectory(directory.toString());
        report.setFilesSeen(seen);
        report.setElapsedMs(Duration.between(started, Instant.now()).toMillis());
        report.setIndexed(indexed);
        report.setEmbeddingsModel(index.getModelId());
        RunSpend run = RunSpend.of(ledger, context.getWorkflowId());
        report.setCaps(run.getCaps());
        report.setSpent(run.getSpent());
        report.setLlmCalls(run.getLlmCalls());
        report.setUnpricedCalls(run.getUnpricedCalls());
        report.setByModel(run.getByModel());
        CostLedger.Workflow spend = ledger.workflow(context.getWorkflowId());
        if (spend != null) {
            attribute(spend.getCalls(), jobsByPath, rows, index.getModelId());
        }
        for (ExtractReport.FileOutcome row : rows.values()) {
            if (row.getTier() == null) {
                row.setTier(Tier.FAILED);
                row.setNote(join(row.getNote(), "no outcome recorded"));
            }
            report.getByTier().merge(row.getTier(), 1, Integer::sum);
            report.getFiles().add(row);
        }
        log.info("Extracted {} files in {} ms: {} spent {}", seen, report.getElapsedMs(), report.getByTier(), report.getSpent());
        return report;
    }
}
