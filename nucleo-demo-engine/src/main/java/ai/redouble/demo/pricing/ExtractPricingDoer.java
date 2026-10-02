/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.slf4j.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The second demo, over the first one's result: every document the extractor indexed goes
 * to a small model that lists the prices it states, all documents in parallel under the
 * same admission and the same caps; the distinct product names then go once to a model a
 * grade up, which says which names are one product; and code does the rest, ranking every
 * price in force by date into current and superseded, and setting proposals, former prices
 * and costs aside as what they are. The report carries the products with their history,
 * one row per document, and what the run spent.
 *
 * <p>A doer, because it coordinates and holds nothing: the documents are the index's, the
 * children are their own jobs, and a child refused at the cap is a row that says so while
 * the run finishes on what it has.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
@ToolName("extract_pricing")
@ToolDescription(value = "Reads every indexed document for the prices it states and reconciles them into current and superseded prices per product.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class ExtractPricingDoer extends AbstractDoer<PricingRequest, PricingReport> {
    private static final Logger log = LoggerFactory.getLogger(ExtractPricingDoer.class);
    private final CostLedger ledger;
    private final FileIndex index;
    private final Function<Identifiable, ? extends Tool<DocumentText, PriceMentions>> extractor;
    private final Function<Identifiable, ? extends Tool<ProductNames, ProductGroups>> grouper;

    /** The run on the LLM tiers: {@link ExtractPricesTool} per document, {@link CanonicalizeProductsTool} once. */
    public ExtractPricingDoer(Identifiable parent, CostLedger ledger, FileIndex index) {
        this(parent, ledger, index, ExtractPricesTool::new, CanonicalizeProductsTool::new);
    }

    /**
     * The run on the tiers given: the same documents in, the same mentions and groups out,
     * the same reconciliation and report, whichever model kind each tool puts its judgment to
     * ({@link DecideProductGroupsTool} puts the grouping to a decision model).
     */
    public ExtractPricingDoer(Identifiable parent, CostLedger ledger, FileIndex index,
                              Function<Identifiable, ? extends Tool<DocumentText, PriceMentions>> extractor,
                              Function<Identifiable, ? extends Tool<ProductNames, ProductGroups>> grouper) {
        super(parent);
        this.ledger = ledger;
        this.index = index;
        this.extractor = extractor;
        this.grouper = grouper;
    }

    private record Submitted(String path, JobHandle<PriceMentions> handle) {}

    @Override
    public PricingReport execute(JobContext<PricingReport> context) throws LLMReadableCheckedException {
        Instant started = Instant.now();
        Map<String, String> texts = index.texts();
        if (texts.isEmpty()) {
            throw new InvalidInputException("index", "empty", "an extraction to read: run the extractor first");
        }
        // the day the run answers for: a price dated after it is scheduled, not current
        LocalDate asOf;
        if (input.getAsOf() == null || input.getAsOf().isBlank()) {
            asOf = LocalDate.now();
        }
        else {
            try {
                asOf = LocalDate.parse(input.getAsOf().trim());
            }
            catch (DateTimeParseException e) {
                throw new InvalidInputException("asOf", input.getAsOf(), "an ISO date, YYYY-MM-DD");
            }
        }
        if (input.getBudgets() != null) {
            for (ExtractRequest.Budget budget : input.getBudgets()) {
                ledger.cap(context.getWorkflowId(), new Cost(budget.getAmount(), budget.getCurrency()));
            }
        }
        Map<String, PricingReport.DocumentOutcome> rows = new LinkedHashMap<>();
        Map<String, String> jobsByPath = new HashMap<>();
        context.publish("Reading " + texts.size() + " documents for prices", 5);
        nextStep();
        List<Submitted> submitted = new ArrayList<>();
        for (Map.Entry<String, String> document : texts.entrySet()) {
            context.checkCancellation();
            PricingReport.DocumentOutcome row = new PricingReport.DocumentOutcome();
            row.setPath(document.getKey());
            rows.put(document.getKey(), row);
            DocumentText text = new DocumentText();
            text.setPath(document.getKey());
            text.setText(document.getValue());
            Tool<DocumentText, PriceMentions> tool = extractor.apply(this);
            tool.setInput(text);
            JobHandle<PriceMentions> handle = submitInCurrentStep(tool);
            jobsByPath.put(handle.getJobId(), document.getKey());
            submitted.add(new Submitted(document.getKey(), handle));
        }
        List<PriceHistory.Sourced> mentions = new ArrayList<>();
        int read = 0;
        for (Submitted job : submitted) {
            PricingReport.DocumentOutcome row = rows.get(job.path());
            try {
                PriceMentions found = job.handle().get();
                row.setStatus(PricingReport.DocumentStatus.READ);
                row.setDocumentDate(found.getDocumentDate());
                row.setNote(found.getDocumentDateBasis());
                read++;
                if (found.getMentions() != null) {
                    row.setMentions(found.getMentions().size());
                    for (PriceMention mention : found.getMentions()) {
                        mentions.add(new PriceHistory.Sourced(job.path(), found.getDocumentDate(), mention));
                    }
                }
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                row.setStatus(isSpendCap(cause) ? PricingReport.DocumentStatus.REFUSED : PricingReport.DocumentStatus.FAILED);
                row.setNote(reason(cause));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("extract_pricing", "interrupted while waiting for " + job.path(), e);
            }
        }
        context.publish("Found " + mentions.size() + " price mentions in " + read + " documents", 60);
        // one call, a grade up: which names are one product
        ProductGroups groups = null;
        String canonicalizationNote = null;
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PriceHistory.Sourced sourced : mentions) {
            if (sourced.mention().getProduct() != null && !sourced.mention().getProduct().isBlank()) {
                names.add(sourced.mention().getProduct().trim());
            }
        }
        if (!names.isEmpty()) {
            nextStep();
            ProductNames input = new ProductNames();
            input.setNames(new ArrayList<>(names));
            Tool<ProductNames, ProductGroups> tool = grouper.apply(this);
            tool.setInput(input);
            JobHandle<ProductGroups> handle = submitInCurrentStep(tool);
            jobsByPath.put(handle.getJobId(), null);
            try {
                groups = handle.get();
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                canonicalizationNote = (isSpendCap(cause) ? "refused at the cap: " : "failed: ") + reason(cause)
                        + "; every name the documents used stands as its own product";
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("extract_pricing", "interrupted while grouping product names", e);
            }
        }
        context.publish("Reconciling " + names.size() + " product names", 85);
        PriceHistory.Result result = PriceHistory.reconcile(mentions, groups, asOf);
        PricingReport report = new PricingReport();
        report.setWorkflowId(context.getWorkflowId());
        report.setGeneratedAt(Instant.now().toString());
        report.setAsOf(asOf.toString());
        report.setElapsedMs(Duration.between(started, Instant.now()).toMillis());
        report.setDocumentsRead(read);
        report.setMentionsFound(mentions.size());
        report.setCanonicalizationNote(canonicalizationNote);
        report.setProducts(result.products());
        report.setIgnored(result.ignored());
        report.setSpend(RunSpend.of(ledger, context.getWorkflowId()));
        CostLedger.Workflow spend = ledger.workflow(context.getWorkflowId());
        if (spend != null) {
            for (CostLedger.Call call : spend.getCalls()) {
                String path = jobsByPath.get(call.jobId());
                PricingReport.DocumentOutcome row = path != null ? rows.get(path) : null;
                if (row == null) {
                    continue;
                }
                row.setModelId(call.modelId());
                row.setServedModelId(call.servedModelId());
                row.setInputTokens((row.getInputTokens() != null ? row.getInputTokens() : 0) + call.inputTokens());
                row.setOutputTokens((row.getOutputTokens() != null ? row.getOutputTokens() : 0) + call.outputTokens());
                row.setLatencyMs((row.getLatencyMs() != null ? row.getLatencyMs() : 0) + call.latencyMs());
                if (call.cost() != null) {
                    row.setCurrency(call.cost().currency());
                    row.setCost(new Cost(row.getCost() != null ? row.getCost() : 0.0, call.cost().currency()).plus(call.cost()).amount());
                }
            }
        }
        report.getDocuments().addAll(rows.values());
        log.info("Priced {} products from {} mentions in {} documents, {} ms, spent {}", result.products().size(),
                mentions.size(), read, report.getElapsedMs(), report.getSpend().getSpent());
        return report;
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
}
