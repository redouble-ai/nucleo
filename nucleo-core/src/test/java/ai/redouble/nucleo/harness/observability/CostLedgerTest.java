/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ledger's arithmetic and the cap's rules, over scripted specs and hand-built events:
 * a call is priced in its entry's currency from its four token counts, sums stay per
 * currency, an unpriced call is counted and reported, and the gate refuses what would
 * cross the cap, what is priced in another currency, and what is not priced at all.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
class CostLedgerTest {

    private static StandardModelSpec spec(String id, String currency, Double in, Double out) {
        StandardModelSpec s = new StandardModelSpec();
        s.setId(id);
        s.setIdentity(id);
        s.setProviderKey("test");
        s.setWireModelId(id);
        // wide enough that the round token counts the assertions use fit as reservations
        s.setMaxContextTokens(100_000_000);
        s.setMaxOutputTokens(10_000_000);
        s.setTpm(1_000_000);
        s.setRpm(1000);
        s.setGrade(Grade.SMALL);
        s.setCacheReadMultiplier(0.1);
        s.setCacheWriteMultiplier(1.25);
        s.setCurrency(currency);
        s.setInputPricePerMillion(in);
        s.setOutputPricePerMillion(out);
        return s;
    }

    private static final StandardModelSpec USD_MODEL = spec("usd-model", "USD", 2.0, 10.0);
    private static final StandardModelSpec EUR_MODEL = spec("eur-model", "EUR", 3.0, 15.0);
    private static final StandardModelSpec FREE_MODEL = spec("unpriced-model", null, null, null);
    private static final Map<String, ModelSpec> CATALOG = Map.of(
            USD_MODEL.getId(), USD_MODEL, EUR_MODEL.getId(), EUR_MODEL, FREE_MODEL.getId(), FREE_MODEL);

    private static CostLedger ledger() {
        return new CostLedger(CATALOG::get);
    }

    private static LLMResponse<String> response(String modelId, int input, int cacheWrite, int cacheRead, int output) {
        ConversationContext conversation = new ConversationContext();
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("ping");
        conversation.getMessages().add(message);
        LLMResponse<String> response = new LLMResponse<>(new LLMRequest<>(conversation));
        response.setModel(modelId);
        response.setServedModelId(modelId != null ? modelId + ":served" : null);
        response.setActualInputTokens(input);
        response.setCacheCreationInputTokens(cacheWrite);
        response.setCacheReadInputTokens(cacheRead);
        response.setActualOutputTokens(output);
        response.setStartTime(Instant.now().minusMillis(250));
        response.setEndTime(Instant.now());
        return response;
    }

    private static JobSnapshot snapshot(String jobId, String workflowId) {
        return new JobSnapshot(jobId, null, workflowId, "tester", Job.class, JobType.TOOL, jobId, null, null,
                JobState.COMPLETED, 1, Instant.now(), Instant.now(), Instant.now(), List.of(), Map.of());
    }

    private static void finish(CostLedger ledger, String workflowId, String jobId, LLMResponse<?>... responses) {
        ledger.observe(new JobCompletedEvent<>(snapshot(jobId, workflowId), "done", 1, List.of(responses), Map.of()));
    }

    @Test
    void aCallIsPricedFromItsFourCountsInTheEntrysCurrency() {
        // 10,000 input of which 2,000 written to cache and 3,000 read from it, 1,000 output:
        // uncached 5,000 x 2.0 + 2,000 x 2.0 x 1.25 + 3,000 x 2.0 x 0.1 + 1,000 x 10.0, all per million
        Cost cost = Cost.of(USD_MODEL, 10_000, 2_000, 3_000, 1_000);
        assertEquals("USD", cost.currency());
        assertEquals((5_000 * 2.0 + 2_000 * 2.5 + 3_000 * 0.2 + 1_000 * 10.0) / 1e6, cost.amount(), 1e-12);
        assertNull(Cost.of(FREE_MODEL, 10_000, 0, 0, 1_000), "no price is no cost, never zero");
    }

    @Test
    void aWorkflowsSpendSumsPerCurrencyAndCountsTheUnpriced() {
        CostLedger ledger = ledger();
        finish(ledger, "wf", "job-1", response("usd-model", 1_000_000, 0, 0, 100_000));
        finish(ledger, "wf", "job-2", response("usd-model", 1_000_000, 0, 0, 0), response("eur-model", 1_000_000, 0, 0, 0));
        ledger.observe(new JobFailedEvent(snapshot("job-3", "wf"), new RuntimeException("boom"), 1,
                List.of(response("unpriced-model", 500, 0, 0, 50)), Map.of()));
        CostLedger.Workflow spend = ledger.workflow("wf");
        assertEquals(2.0 + 1.0 + 2.0, spend.getTotal("USD").amount(), 1e-9, "two USD calls: 2+1 input, 1 output");
        assertEquals(3.0, spend.getTotal("EUR").amount(), 1e-9, "the euro call is its own figure");
        assertEquals(0.0, spend.getTotal("GBP").amount(), 1e-9, "nothing in a currency is zero of it");
        assertEquals(4, spend.getCalls().size(), "a failed job's call spent its tokens too");
        assertEquals(1, spend.getUnpricedCalls());
        assertEquals(2, spend.getByModel().get("usd-model").getCalls());
        assertEquals(2_000_000, spend.getByModel().get("usd-model").getInputTokens());
        assertNull(spend.getByModel().get("unpriced-model").getCost());
        assertNull(ledger.workflow("other"), "a workflow that finished nothing has no spend");
    }

    /** A job whose context names the workflow, for the gate; the ledger reads nothing else from it. */
    private static final class CappedJob extends AbstractJob<String> {
        CappedJob(Identifiable parent) {
            super(parent, "capped");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "done";
        }
    }

    private static JobContext<String> contextIn(String workflowPrefix) {
        return contextUnder(Job.workflow("tester", workflowPrefix));
    }

    private static JobContext<String> contextUnder(Identifiable workflow) {
        return new JobContext<>(new CappedJob(workflow), "tester", Duration.ofMinutes(1), null);
    }

    private static ModelBinding priced(ModelSpec model, int inTokens, int outTokens) {
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE, inTokens, OutputDeclaration.of(outTokens));
        binding.resolve(model);
        binding.price();
        return binding;
    }

    @Test
    void theGateAdmitsUnderTheCapAndRefusesWhatWouldCrossIt() {
        CostLedger ledger = ledger();
        JobContext<String> context = contextIn("capped-flow");
        String workflowId = context.getWorkflowId();
        ledger.cap(workflowId, new Cost(5.0, "USD"));
        // 1,000,000 input at 2.0 + 100,000 output at 10.0 = 3.0 reserved: admitted, and committed until it finishes
        ledger.admit(context, List.of(priced(USD_MODEL, 1_000_000, 100_000)));
        assertEquals(3.0, ledger.committed(workflowId, "USD").amount(), 1e-9);
        finish(ledger, workflowId, context.getJobId(), response("usd-model", 1_000_000, 0, 0, 100_000));
        assertEquals(0.0, ledger.committed(workflowId, "USD").amount(), 1e-9, "finished: released");
        assertEquals(3.0, ledger.spent(workflowId, "USD").amount(), 1e-9, "finished: spent");
        // the same again would commit 6.0 against a cap of 5.0
        SpendCapExceededException refusal = assertThrows(SpendCapExceededException.class,
                () -> ledger.admit(context, List.of(priced(USD_MODEL, 1_000_000, 100_000))));
        assertTrue(refusal.getMessage().contains("has spent 3.000000 USD"), "the spend so far is named: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("committed 0.000000 USD"), "and the commitments in flight: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("cap of 5.000000 USD"), "and the cap: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("reserve up to 3.000000 USD"), "and the reservation: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("nothing running was stopped"), refusal.getMessage());
        // a small job still fits: 100,000 input at 2.0 plus 1,000 output at 10.0 = 0.21 -> 3.21
        ledger.admit(context, List.of(priced(USD_MODEL, 100_000, 1_000)));
        ledger.uncap(workflowId);
        assertDoesNotThrow(() -> ledger.admit(context, List.of(priced(USD_MODEL, 10_000_000, 1_000_000))), "no cap admits anything");
    }

    @Test
    void aFanOutIsJudgedAgainstWhatItsSiblingsHaveCommittedAndNotYetSpent() {
        // three siblings admitted before any finishes: 2.0 each under a cap of 5.0; the third
        // sees 4.0 committed in flight and nothing spent yet, and is refused on the commitment
        CostLedger ledger = ledger();
        Identifiable workflow = Job.workflow("tester", "fan-out");
        JobContext<String> first = contextUnder(workflow);
        JobContext<String> second = contextUnder(workflow);
        JobContext<String> third = contextUnder(workflow);
        ledger.cap(first.getWorkflowId(), new Cost(5.0, "USD"));
        ledger.admit(first, List.of(priced(USD_MODEL, 1_000_000, 1)));
        ledger.admit(second, List.of(priced(USD_MODEL, 1_000_000, 1)));
        assertEquals(4.0, ledger.committed(first.getWorkflowId(), "USD").amount(), 1e-4, "two reservations of 2.0 (plus one output token each)");
        SpendCapExceededException refusal = assertThrows(SpendCapExceededException.class,
                () -> ledger.admit(third, List.of(priced(USD_MODEL, 1_000_000, 1))));
        assertTrue(refusal.getMessage().contains("committed 4.0"), refusal.getMessage());
        // the first finishes, having spent less than it reserved: its commitment is released
        // and its spend recorded, and the third now fits
        finish(ledger, first.getWorkflowId(), first.getJobId(), response("usd-model", 200_000, 0, 0, 0));
        assertEquals(2.0, ledger.committed(first.getWorkflowId(), "USD").amount(), 1e-4);
        assertEquals(0.4, ledger.spent(first.getWorkflowId(), "USD").amount(), 1e-9);
        assertDoesNotThrow(() -> ledger.admit(third, List.of(priced(USD_MODEL, 1_000_000, 1))));
    }

    @Test
    void theGateRefusesACurrencyWithoutACapAndAnUnpricedModel() {
        CostLedger ledger = ledger();
        JobContext<String> context = contextIn("euro-flow");
        ledger.cap(context.getWorkflowId(), new Cost(100.0, "USD"));
        SpendCapExceededException euro = assertThrows(SpendCapExceededException.class,
                () -> ledger.admit(context, List.of(priced(EUR_MODEL, 1_000, 100))));
        assertTrue(euro.getMessage().contains("priced in EUR"), euro.getMessage());
        assertTrue(euro.getMessage().contains("add a cap in EUR"), euro.getMessage());
        SpendCapExceededException free = assertThrows(SpendCapExceededException.class,
                () -> ledger.admit(context, List.of(priced(FREE_MODEL, 1_000, 100))));
        assertTrue(free.getMessage().contains("does not price"), free.getMessage());
        assertDoesNotThrow(() -> ledger.admit(contextIn("uncapped-flow"), List.of(priced(EUR_MODEL, 1_000, 100))),
                "a workflow with no cap is not gated");
    }

    @Test
    void aWorkflowPayingInTwoCurrenciesHoldsACapInEachAndEachIsJudgedOnItsOwn() {
        CostLedger ledger = ledger();
        Identifiable workflow = Job.workflow("tester", "two-currencies");
        JobContext<String> dollars = contextUnder(workflow);
        JobContext<String> euros = contextUnder(workflow);
        ledger.cap(dollars.getWorkflowId(), new Cost(5.0, "USD"));
        ledger.cap(dollars.getWorkflowId(), new Cost(1.0, "EUR"));
        assertEquals(Set.of("USD", "EUR"), ledger.caps(dollars.getWorkflowId()).keySet());
        // 1,000,000 input at 2.0 USD = 2.0 fits the dollar cap; 1,000,000 at 3.0 EUR does not fit the euro cap
        assertDoesNotThrow(() -> ledger.admit(dollars, List.of(priced(USD_MODEL, 1_000_000, 1))));
        SpendCapExceededException euro = assertThrows(SpendCapExceededException.class,
                () -> ledger.admit(euros, List.of(priced(EUR_MODEL, 1_000_000, 1))));
        assertTrue(euro.getMessage().contains("1.0") && euro.getMessage().contains("EUR"), euro.getMessage());
        // the euro refusal changed nothing in dollars: 2.0 is committed there and nothing in euros
        assertEquals(2.0, ledger.committed(dollars.getWorkflowId(), "USD").amount(), 1e-4);
        assertEquals(0.0, ledger.committed(dollars.getWorkflowId(), "EUR").amount(), 1e-9);
        // a smaller euro job fits its own cap regardless of the dollars committed
        assertDoesNotThrow(() -> ledger.admit(euros, List.of(priced(EUR_MODEL, 100_000, 1))));
        ledger.uncap(dollars.getWorkflowId());
        assertTrue(ledger.caps(dollars.getWorkflowId()).isEmpty());
    }

    @Test
    void anEmbeddingsCallIsPricedFromItsInputAloneUnderTheSameCap() {
        StandardModelSpec embeddings = spec("embed-model", "USD", 0.12, null);
        embeddings.setProviderKey("test-embeddings");
        embeddings.setGrade(null);
        embeddings.setEmbeddingDimensions(1024);
        CostLedger ledger = new CostLedger(id -> id.equals("embed-model") ? embeddings : CATALOG.get(id));
        JobContext<String> context = contextIn("vector-flow");
        String workflowId = context.getWorkflowId();
        ledger.cap(workflowId, new Cost(1.0, "USD"));
        // 1,000,000 tokens at 0.12 per million reserve 0.12 against the dollar cap, no output priced
        ModelBinding vectors = ModelBinding.embeddings(1_000_000);
        vectors.resolve(embeddings);
        vectors.price();
        ledger.admit(context, List.of(vectors));
        assertEquals(0.12, ledger.committed(workflowId, "USD").amount(), 1e-9, "the reservation is the input at its price");
        ModelBinding tooMany = ModelBinding.embeddings(8_000_000);
        tooMany.resolve(embeddings);
        tooMany.price();
        assertThrows(SpendCapExceededException.class, () -> ledger.admit(context, List.of(tooMany)),
                "0.12 committed plus 0.96 reserved crosses the cap of 1.0");
        EmbeddingsResponse call = new EmbeddingsResponse(embeddings, EmbeddingPurpose.DOCUMENT);
        call.setActualInputTokens(1_000_000);
        call.setEndTime(Instant.now());
        call.setSuccessful(true);
        finish(ledger, workflowId, context.getJobId(), call, response("usd-model", 1_000_000, 0, 0, 0));
        CostLedger.Workflow spend = ledger.workflow(workflowId);
        assertEquals(0.12 + 2.0, spend.getTotal("USD").amount(), 1e-9, "the vector cost its input price, nothing else");
        assertEquals(1, spend.getByModel().get("embed-model").getCalls());
        assertEquals(0, spend.getByModel().get("embed-model").getOutputTokens());
        assertEquals(0, spend.getUnpricedCalls(), "an embeddings entry with no output price is priced");
        assertEquals(0.0, ledger.committed(workflowId, "USD").amount(), 1e-9, "finished: the vector reservation is released");
    }

    @Test
    void moneyIsExactToTheNanoUnit() {
        // token prices are quoted per million to a few decimals, so binary noise from summing
        // thousands of calls never reaches a figure a person or a cap reads
        Cost sum = new Cost(0.0, "USD");
        for (int i = 0; i < 1000; i++) {
            sum = sum.plus(new Cost(0.001, "USD"));
        }
        assertEquals(1.0, sum.amount(), "exactly one dollar");
        assertEquals(0.002727, new Cost(0.001189, "USD").plus(new Cost(0.001538, "USD")).amount());
    }

    @Test
    void costsNeverMixCurrencies() {
        assertThrows(IllegalArgumentException.class, () -> new Cost(1.0, "USD").plus(new Cost(1.0, "EUR")));
        assertThrows(IllegalArgumentException.class, () -> new Cost(1.0, "USD").exceeds(new Cost(1.0, "EUR")));
        assertThrows(IllegalArgumentException.class, () -> new Cost(1.0, "dollars"), "a currency is three letters");
        assertThrows(IllegalArgumentException.class, () -> new Cost(1.0, "usd"), "and upper-case ones");
    }

    @Test
    void aReservationIsPricedWithNothingCachedAndRendersSixDecimals() {
        // 1,000,000 input at 2.0 plus 100,000 output at 10.0, no cache rate applied to any of it
        Cost reserved = Cost.reserved(USD_MODEL, 1_000_000, 100_000);
        assertEquals(3.0, reserved.amount(), 1e-12);
        assertEquals("USD", reserved.currency());
        assertNull(Cost.reserved(FREE_MODEL, 1_000_000, 100_000), "no price is no reservation cost, never zero");
        assertEquals("3.000000 USD", reserved.toString(), "six decimals and the currency");
        assertEquals("0.002727 USD", new Cost(0.0027274, "USD").toString());
        assertTrue(new Cost(5.0, "USD").exceeds(new Cost(4.999999, "USD")));
        assertFalse(new Cost(5.0, "USD").exceeds(new Cost(5.0, "USD")), "at the cap is not past it");
    }

    @Test
    void theLedgerReadsTerminalEventsOnlyAndCountsWhatTheCatalogDoesNotKnow() {
        CostLedger ledger = ledger();
        JobSnapshot snapshot = snapshot("job-u", "wf");
        assertTrue(ledger.getPredicate().test(new JobCompletedEvent<>(snapshot, "ok", 1, List.of(), Map.of())));
        assertTrue(ledger.getPredicate().test(new JobFailedEvent(snapshot, new RuntimeException("x"), 1, List.of(), Map.of())));
        assertFalse(ledger.getPredicate().test(new JobStartedEvent(snapshot, 1)), "only a finished job has calls to price");
        assertEquals(0.0, ledger.spent("wf", "USD").amount(), 1e-12, "nothing finished: zero of the currency, never null");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings = ObservabilityFixtures.capture(CostLedger.class.getName());
        try {
            finish(ledger, "wf", "job-u", response("model-nobody-knows", 500, 0, 0, 50), response(null, 5, 0, 0, 1));
        }
        finally {
            ObservabilityFixtures.release(CostLedger.class.getName(), warnings);
        }
        assertEquals(2, warnings.list.size(), "one warning per call the catalog cannot price");
        assertEquals(ch.qos.logback.classic.Level.WARN, warnings.list.get(0).getLevel());
        assertTrue(warnings.list.get(0).getFormattedMessage().contains("model-nobody-knows"), "the warning names the model: " + warnings.list.get(0).getFormattedMessage());
        CostLedger.Workflow spend = ledger.workflow("wf");
        assertEquals(2, spend.getUnpricedCalls(), "an unknown model and a missing one are both counted unpriced");
        assertEquals(1, spend.getByModel().get("model-nobody-knows").getCalls(), "an unknown model is counted under the id the response named");
        assertEquals(1, spend.getByModel().get("(unknown)").getCalls(), "a response with no model is counted under (unknown)");
        assertEquals(Map.of(), spend.getTotals(), "nothing priced, no total in any currency");
    }

    @Test
    void aModelsShareCarriesEveryTokenKindAndTheLatency() {
        CostLedger ledger = ledger();
        finish(ledger, "wf", "job-a", response("usd-model", 10_000, 2_000, 3_000, 1_000));
        finish(ledger, "wf", "job-b", response("usd-model", 10_000, 0, 0, 0));
        CostLedger.ModelSpend usd = ledger.workflow("wf").getByModel().get("usd-model");
        assertEquals("usd-model", usd.getModelId());
        assertEquals(2, usd.getCalls());
        assertEquals(20_000, usd.getInputTokens());
        assertEquals(2_000, usd.getCacheWriteTokens());
        assertEquals(3_000, usd.getCacheReadTokens());
        assertEquals(1_000, usd.getOutputTokens());
        assertTrue(usd.getLatencyMs() >= 500, "both calls' latencies sum: " + usd.getLatencyMs());
        assertEquals(0, usd.getUnpricedCalls());
        CostLedger.Call first = ledger.workflow("wf").getCalls().get(0);
        assertEquals("job-a", first.jobId());
        assertEquals("job-a", first.jobName(), "the snapshot's display name");
        assertEquals("usd-model", first.modelId());
        assertEquals("usd-model:served", first.servedModelId(), "the model the provider said it served");
        assertTrue(first.latencyMs() >= 250, "the response's own latency: " + first.latencyMs());
        assertEquals(1_000, first.outputTokens());
        assertEquals(Cost.of(USD_MODEL, 10_000, 2_000, 3_000, 1_000), first.cost());
        assertTrue(ledger.caps("wf").isEmpty(), "a workflow under no cap reports none");
    }
}
