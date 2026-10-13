/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link TokenLedger} contract: counts by kind, a facet the provider did not report as
 * null, a facet a ledger does not implement refused by name, derived facets null when an
 * operand is null and the hit rate zero while a cache is being built, the metered amount null
 * when either operand is, an estimate's cost the ceiling and a reported usage's cost priced on
 * what was reported, the input by part summing to the input and handed out read-only, and an
 * estimate refusing to exist without its parts or a way to resolve its reserve.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
class TokenLedgerTest {

    private static StandardModelSpec priced() {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("priced");
        spec.setIdentity("priced");
        spec.setProviderKey("test-provider");
        spec.setWireModelId("priced");
        spec.setMaxContextTokens(200_000);
        spec.setMaxOutputTokens(8_000);
        spec.setCurrency("USD");
        spec.setInputPricePerMillion(1.0);
        spec.setOutputPricePerMillion(10.0);
        spec.setCacheReadMultiplier(0.1);
        spec.setCacheWriteMultiplier(1.25);
        return spec;
    }

    @Test
    void aReportedUsageCarriesTheCountsAsGiven_andDerivesTheRest() {
        TokenLedger usage = new DefaultTokenLedger(priced(), 1_000, 300, 200, 50);
        assertEquals(1_000, usage.inputTokens());
        assertEquals(300, usage.cacheReadTokens());
        assertEquals(200, usage.cacheWriteTokens());
        assertEquals(50, usage.outputTokens());
        assertEquals(500, usage.uncachedInputTokens(), "input less reads less writes");
        assertEquals(0.6, usage.cacheHitRate(), 1e-9, "reads over reads plus writes");
        // 500 uncached at 1.0, 200 writes at 1.25, 300 reads at 0.1, 50 output at 10.0, all per million
        assertEquals((500 * 1.0 + 200 * 1.25 + 300 * 0.1 + 50 * 10.0) / 1_000_000, usage.cost().amount(), 1e-12,
                "priced per kind at the entry's rates");
    }

    @Test
    void aFacetTheProviderDoesNotReportIsNull_andSoIsWhatDependsOnIt() {
        TokenLedger usage = new DefaultTokenLedger(priced(), 1_000, null, null, 50);
        assertNull(usage.cacheReadTokens(), "not reported");
        assertNull(usage.uncachedInputTokens(), "unknown cache facets leave the uncached count unknown");
        assertNull(usage.cacheHitRate());
        assertEquals(1.0 / 1_000 + 50 * 10.0 / 1_000_000, usage.cost().amount(), 1e-12,
                "priced on what was reported: every input token at the full rate, a floor");
    }

    @Test
    void anEstimateSumsItsPartsIntoTheInput_andCostsTheCeiling() {
        EnumMap<ContextPart, Integer> parts = new EnumMap<>(ContextPart.class);
        parts.put(ContextPart.OBJECTIVE, 400);
        parts.put(ContextPart.TOOL_DEFINITIONS, 100);
        parts.put(ContextPart.MESSAGES, 2_000);
        parts.put(ContextPart.ARTIFACT_REGISTRY, 500);
        TokenLedger estimate = new DefaultTokenLedger(priced(), parts, () -> 1_000);
        assertEquals(3_000, estimate.inputTokens(), "the parts sum to the input");
        assertEquals(1_000, estimate.outputTokens(), "the reserve the call books");
        assertEquals(4_000, estimate.meteredTokens(), "by default a limit meters input and output together");
        assertEquals(parts, estimate.inputByPart());
        assertNull(estimate.cacheReadTokens(), "cache facets are not known before the call");
        assertEquals(3_000 * 1.0 / 1_000_000 + 1_000 * 10.0 / 1_000_000, estimate.cost().amount(), 1e-12,
                "every token at its full rate: the ceiling the call can reach");
    }

    @Test
    void anEstimateResolvesItsOutputReserveOnlyWhenRead() {
        EnumMap<ContextPart, Integer> parts = new EnumMap<>(ContextPart.class);
        parts.put(ContextPart.MESSAGES, 2_000);
        int[] resolutions = new int[1];
        TokenLedger estimate = new DefaultTokenLedger(priced(), parts, () -> {
            resolutions[0]++;
            return 500;
        });
        assertEquals(2_000, estimate.inputTokens());
        assertEquals(0, resolutions[0], "a reader of the input alone never resolves the reserve");
        assertEquals(500, estimate.outputTokens());
        assertEquals(500, estimate.outputTokens());
        assertEquals(1, resolutions[0], "resolved once, then remembered");
        TokenLedger undeclared = new DefaultTokenLedger(priced(), parts, () -> {
            throw new IllegalStateException("the seat declares no depth");
        });
        assertEquals(2_000, undeclared.inputTokens(), "the input of a seat that declared no answer still counts");
        assertThrows(IllegalStateException.class, undeclared::outputTokens, "its reserve meets the seat's own refusal");
    }

    @Test
    void aReportedUsageHasNoPartsAndSaysSoByName() {
        TokenLedger usage = new DefaultTokenLedger(priced(), 10, null, null, 1);
        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class, usage::inputByPart);
        assertTrue(refusal.getMessage().contains("test-provider"), "the refusal names the provider: " + refusal.getMessage());
    }

    @Test
    void anUnimplementedFacetRefusesNamingTheProviderAndTheFacet() {
        TokenLedger bare = () -> priced();
        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class, bare::cacheHitRate);
        assertTrue(refusal.getMessage().contains("test-provider") && refusal.getMessage().contains("cacheHitRate"),
                "unimplemented is a defect named by provider and facet, never a silent null: " + refusal.getMessage());
    }

    @Test
    void anUnpricedEntryHasNoCost() {
        StandardModelSpec free = priced();
        free.setInputPricePerMillion(null);
        free.setCurrency(null);
        assertNull(new DefaultTokenLedger(free, 1_000, null, null, 50).cost(), "no price is no cost, never zero");
        assertNull(new DefaultTokenLedger(priced(), null, null, null, 50).cost(), "no input count, no cost");
    }

    @Test
    void theMeteredAmountIsNullWhenEitherOperandIs() {
        assertNull(new DefaultTokenLedger(priced(), null, null, null, 50).meteredTokens(), "no input count, nothing to meter");
        assertNull(new DefaultTokenLedger(priced(), 1_000, null, null, null).meteredTokens(), "no output count, nothing to meter");
        assertEquals(1_050, new DefaultTokenLedger(priced(), 1_000, null, null, 50).meteredTokens());
    }

    @Test
    void aCacheBeingBuiltHasAHitRateOfZero_notNull() {
        TokenLedger building = new DefaultTokenLedger(priced(), 1_000, 0, 0, 50);
        assertEquals(0.0, building.cacheHitRate(), 1e-9, "both counters reported and both zero: nothing read yet, a rate of zero");
        TokenLedger writing = new DefaultTokenLedger(priced(), 1_000, 0, 400, 50);
        assertEquals(0.0, writing.cacheHitRate(), 1e-9, "writes with no reads is the first call of a cache: zero, a fact");
    }

    @Test
    void anEstimateStatesItsPartsAndItsReserve_andKeepsThePartsAsGiven() {
        EnumMap<ContextPart, Integer> parts = new EnumMap<>(ContextPart.class);
        parts.put(ContextPart.MESSAGES, 10);
        assertThrows(IllegalArgumentException.class, () -> new DefaultTokenLedger(priced(), null, () -> 1), "no parts, no estimate");
        assertThrows(IllegalArgumentException.class, () -> new DefaultTokenLedger(priced(), parts, null), "no way to resolve the reserve, no estimate");
        TokenLedger estimate = new DefaultTokenLedger(priced(), parts, () -> 1);
        parts.put(ContextPart.OBJECTIVE, 5);
        assertEquals(10, estimate.inputTokens(), "the ledger copied the parts; a later change to the caller's map is not its business");
        assertThrows(UnsupportedOperationException.class, () -> estimate.inputByPart().put(ContextPart.OBJECTIVE, 1), "the parts a ledger hands out cannot be edited");
    }

    @Test
    void aLedgerNamesItsModel() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultTokenLedger(null, 1, null, null, 0));
        Cost cost = new DefaultTokenLedger(priced(), 1_000_000, 0, 0, 0).cost();
        assertEquals("USD", cost.currency());
    }
}
