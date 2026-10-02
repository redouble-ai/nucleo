/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.demo.pricing.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The arithmetic of "which price is current", on mentions written by hand: a chain of
 * three lists over two years with an invoice in between, a proposal that never takes,
 * a sign that says what the price was, a supplier's cost, a discontinued model, and the
 * things a model might get wrong (a name spelled two ways, a date that is not one, a
 * mention with no amount).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
class PriceHistoryTest {
    /** The day the runs below answer for: after every date the fixtures carry, so nothing is scheduled unless a test says so. */
    private static final LocalDate AS_OF = LocalDate.parse("2027-09-01");

    private static PriceMention mention(String product, PriceMention.Audience audience, PriceMention.Kind kind, Double amount,
                                        String effectiveFrom, String statedOn) {
        PriceMention m = new PriceMention();
        m.setProduct(product);
        m.setAudience(audience);
        m.setKind(kind);
        m.setAmount(amount);
        m.setCurrency(amount != null ? "eur" : null);
        m.setEffectiveFrom(effectiveFrom);
        m.setStatedOn(statedOn);
        m.setDateBasis("test");
        m.setQuote(product + " " + amount);
        return m;
    }

    private static PriceHistory.Sourced from(String path, String documentDate, PriceMention mention) {
        return new PriceHistory.Sourced(path, documentDate, mention);
    }

    private static ProductGroups groups(String canonical, String... aliases) {
        ProductGroups.Group group = new ProductGroups.Group();
        group.setCanonical(canonical);
        group.setAliases(List.of(aliases));
        ProductGroups groups = new ProductGroups();
        groups.setGroups(List.of(group));
        return groups;
    }

    private static ProductPricing product(PriceHistory.Result result, String name) {
        for (ProductPricing p : result.products()) {
            if (p.getProduct().equals(name)) {
                return p;
            }
        }
        throw new AssertionError("no product " + name + " among " + result.products().stream().map(ProductPricing::getProduct).toList());
    }

    private static PriceSeries series(ProductPricing product, PriceMention.Audience audience) {
        for (PriceSeries s : product.getSeries()) {
            if (s.getAudience() == audience) {
                return s;
            }
        }
        throw new AssertionError("no " + audience + " series on " + product.getProduct());
    }

    @Test
    void theLatestDatedPriceInForceIsCurrentAndTheEarlierOnesAreSupersededByIt() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("old-prices.bak", "2026-01-01", mention("Kestrel 1 gravel", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 1999.0, null, null)),
                from("price-list.xlsx", "2027-01-01", mention("Kestrel 1 gravel", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2049.0, "2027-01-01", null)),
                from("dealer-pitch.pptx", "2027-01-10", mention("Kestrel 1", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2049.0, null, null)),
                from("meeting-notes.md", "2027-02-18", mention("Kestrel 1", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2099.0, "2027-04-01", "2027-02-18")),
                from("invoice.pdf", "2027-03-02", mention("Kestrel 1 gravel", PriceMention.Audience.DEALER, PriceMention.Kind.TRANSACTION, 1420.0, null, "2027-03-02")),
                from("screenshot.png", "2027-04-12", mention("Kestrel 1 gravel", PriceMention.Audience.DEALER, PriceMention.Kind.LIST, 1450.0, null, null)));
        PriceHistory.Result result = PriceHistory.reconcile(mentions, groups("Kestrel 1 gravel", "Kestrel 1 gravel", "Kestrel 1"), AS_OF);
        assertEquals(1, result.products().size(), "two spellings, one product");
        ProductPricing kestrel = product(result, "Kestrel 1 gravel");
        assertEquals(List.of("Kestrel 1 gravel", "Kestrel 1"), kestrel.getAliases());
        PriceSeries retail = series(kestrel, PriceMention.Audience.RETAIL);
        assertEquals(PriceSeries.Status.CURRENT, retail.getStatus());
        assertEquals("EUR", retail.getCurrency(), "the currency is upper-cased");
        assertEquals(2099.0, retail.getCurrent().getAmount());
        assertEquals("2027-04-01", retail.getCurrent().getDate(), "the effective date outranks the meeting date");
        assertEquals("meeting-notes.md", retail.getCurrent().getSource());
        assertEquals(List.of(PricePoint.Status.CURRENT, PricePoint.Status.SUPERSEDED, PricePoint.Status.SUPERSEDED, PricePoint.Status.SUPERSEDED),
                retail.getPoints().stream().map(PricePoint::getStatus).toList());
        assertEquals(List.of("2027-04-01", "2027-01-10", "2027-01-01", "2026-01-01"), retail.getPoints().stream().map(PricePoint::getDate).toList(),
                "newest first; the deck's price is dated by its document, the old list by its own");
        assertTrue(retail.getPoints().get(3).getNote().contains("replaced from 2027-04-01 by 2099.0 EUR"));
        PriceSeries dealer = series(kestrel, PriceMention.Audience.DEALER);
        assertEquals(1450.0, dealer.getCurrent().getAmount(), "the April portal outranks the March invoice");
        assertEquals(PricePoint.Status.SUPERSEDED, dealer.getPoints().get(1).getStatus());
    }

    @Test
    void proposalsFormerPricesAndCostsNeverCompete() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("price-list.xlsx", "2027-01-01", mention("Meridian 3", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2699.0, "2027-01-01", null)),
                from("meeting-notes.md", "2027-02-18", mention("Meridian 3", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2699.0, null, "2027-02-18")),
                from("meeting-notes.md", "2027-02-18", mention("Meridian 3", PriceMention.Audience.RETAIL, PriceMention.Kind.PROPOSED, 2799.0, "2028-01-01", "2027-02-18")),
                from("product-spec.docx", "2026-11-01", mention("Meridian 3", PriceMention.Audience.RETAIL, PriceMention.Kind.PROPOSED, 2599.0, null, null)),
                from("supplier-terms.notes", "2027-01-09", mention("Meridian 3", PriceMention.Audience.UNSPECIFIED, PriceMention.Kind.COST, 310.0, null, null)));
        PriceHistory.Result result = PriceHistory.reconcile(mentions, null, AS_OF);
        ProductPricing meridian = product(result, "Meridian 3");
        PriceSeries retail = series(meridian, PriceMention.Audience.RETAIL);
        assertEquals(2699.0, retail.getCurrent().getAmount(), "a proposal dated after the list does not win");
        assertEquals("meeting-notes.md", retail.getCurrent().getSource(), "the latest statement of the amount is the current point");
        assertEquals(List.of(PricePoint.Status.CURRENT, PricePoint.Status.CONFIRMED, PricePoint.Status.PROPOSED, PricePoint.Status.PROPOSED),
                retail.getPoints().stream().map(PricePoint::getStatus).toList());
        assertTrue(retail.getPoints().get(1).getNote().contains("stated earlier, on 2027-01-01"), "the same amount earlier confirms, nothing replaced it");
        assertEquals(1, meridian.getCosts().size());
        assertEquals(PricePoint.Status.COST, meridian.getCosts().get(0).getStatus());
        assertEquals(1, meridian.getSeries().size(), "a cost opens no series");
    }

    private static PriceMention change(String product, PriceMention.Audience audience, double percent, String effectiveFrom) {
        PriceMention m = mention(product, audience, PriceMention.Kind.CHANGE, null, effectiveFrom, null);
        m.setPercentChange(percent);
        m.setQuote(product + " up " + percent + " percent from " + effectiveFrom);
        return m;
    }

    @Test
    void aDecidedChangeBecomesAPriceAppliedToWhatWasInForceBeforeItsDate() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("price-list.xlsx", "2027-01-01", mention("Kestrel 1 gravel", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2049.0, "2027-01-01", null)),
                from("screenshot.png", "2027-04-12", mention("Kestrel 1 gravel", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2099.0, null, null)),
                // the minutes carry no date in their text: the file name dates them, and the text dates the change
                from("minutes-2027-05-06.md", "2027-05-06", change("Kestrel 1 gravel", PriceMention.Audience.RETAIL, 20.0, "2027-06-01")),
                from("minutes-2027-05-06.md", "2027-05-06", change("Kestrel 1 gravel", PriceMention.Audience.DEALER, 20.0, "2027-06-01")),
                // a change in a document with no date anywhere applies to nothing
                from("undated-note.md", null, change("Kestrel 1 gravel", PriceMention.Audience.RETAIL, -5.0, null)));
        PriceHistory.Result result = PriceHistory.reconcile(mentions, null, AS_OF);
        ProductPricing kestrel = product(result, "Kestrel 1 gravel");
        PriceSeries retail = series(kestrel, PriceMention.Audience.RETAIL);
        assertEquals(PriceSeries.Status.CURRENT, retail.getStatus());
        PricePoint current = retail.getCurrent();
        assertEquals(2518.8, current.getAmount(), 1e-9, "20% on the 2,099 in force before June");
        assertEquals("2027-06-01", current.getDate());
        assertEquals("EUR", current.getCurrency(), "the change took the currency of the series it applied to");
        assertEquals(PriceMention.Kind.CHANGE, current.getKind());
        assertEquals(20.0, current.getPercentChange());
        assertEquals("2099.0 from screenshot.png (2027-04-12)", current.getAppliedTo());
        assertEquals(List.of(PricePoint.Status.CURRENT, PricePoint.Status.SUPERSEDED, PricePoint.Status.SUPERSEDED, PricePoint.Status.UNAPPLIED),
                retail.getPoints().stream().map(PricePoint::getStatus).toList());
        assertTrue(retail.getPoints().get(3).getNote().contains("no date"), "an undated change applies to nothing");
        assertEquals(1, result.ignored().size(), "the dealer change had no dealer price to apply to");
        assertTrue(result.ignored().get(0).getNote().contains("no single DEALER series"));
    }

    @Test
    void aPriceDatedAfterTheRunsDayIsScheduledAndTheOneBeforeItStaysCurrent() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("screenshot.png", "2027-04-12", mention("Kestrel 1 gravel", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2099.0, null, null)),
                from("minutes-2027-05-06.md", "2027-05-06", change("Kestrel 1 gravel", PriceMention.Audience.RETAIL, 20.0, "2027-06-01")));
        // asked on 15 May: the June price is decided, not in force
        PriceSeries retail = series(product(PriceHistory.reconcile(mentions, null, LocalDate.parse("2027-05-15")), "Kestrel 1 gravel"), PriceMention.Audience.RETAIL);
        assertEquals(PriceSeries.Status.CURRENT, retail.getStatus());
        assertEquals(2099.0, retail.getCurrent().getAmount());
        assertEquals(List.of(PricePoint.Status.SCHEDULED, PricePoint.Status.CURRENT), retail.getPoints().stream().map(PricePoint::getStatus).toList(),
                "the scheduled price is listed first, being the newest, and labelled for what it is");
        assertEquals(2518.8, retail.getPoints().get(0).getAmount(), 1e-9, "scheduled, and already computed");
        assertTrue(retail.getPoints().get(0).getNote().contains("after 2027-05-15"));
        // asked on 1 June: it is in force
        retail = series(product(PriceHistory.reconcile(mentions, null, LocalDate.parse("2027-06-01")), "Kestrel 1 gravel"), PriceMention.Audience.RETAIL);
        assertEquals(2518.8, retail.getCurrent().getAmount(), 1e-9);
        // asked before anything was in force: nothing current, the series is scheduled
        retail = series(product(PriceHistory.reconcile(mentions, null, LocalDate.parse("2027-01-01")), "Kestrel 1 gravel"), PriceMention.Audience.RETAIL);
        assertEquals(PriceSeries.Status.SCHEDULED, retail.getStatus());
        assertNull(retail.getCurrent());
    }

    @Test
    void aSignThatSaysWhatThePriceWasIsFormerAndAnUndatedPriceCannotBeRanked() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("shop-sign.png", null, mention("Workshop tune-up", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 49.0, null, null)),
                from("shop-sign.png", null, mention("Workshop tune-up", PriceMention.Audience.RETAIL, PriceMention.Kind.FORMER, 45.0, null, null)));
        PriceHistory.Result result = PriceHistory.reconcile(mentions, null, AS_OF);
        PriceSeries retail = series(product(result, "Workshop tune-up"), PriceMention.Audience.RETAIL);
        assertEquals(PriceSeries.Status.UNDATED, retail.getStatus());
        assertNull(retail.getCurrent(), "nothing dated, nothing current");
        assertEquals(PricePoint.Status.UNDATED, retail.getPoints().get(0).getStatus());
        assertEquals(49.0, retail.getPoints().get(0).getAmount());
        assertEquals(PricePoint.Status.FORMER, retail.getPoints().get(1).getStatus());
    }

    @Test
    void twoPricesInForceOnTheSameLatestDateAreAConflictForAPerson() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("a.md", "2027-03-01", mention("Comet 2", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 999.0, null, null)),
                from("b.md", "2027-03-01", mention("Comet 2", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 1049.0, null, null)),
                from("c.md", "2026-06-01", mention("Comet 2", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 949.0, null, null)));
        PriceHistory.Result result = PriceHistory.reconcile(mentions, null, AS_OF);
        PriceSeries retail = series(product(result, "Comet 2"), PriceMention.Audience.RETAIL);
        assertEquals(PriceSeries.Status.CONFLICT, retail.getStatus());
        assertNull(retail.getCurrent());
        assertEquals(List.of(PricePoint.Status.CONFLICT, PricePoint.Status.CONFLICT, PricePoint.Status.SUPERSEDED),
                retail.getPoints().stream().map(PricePoint::getStatus).toList());
        assertTrue(retail.getPoints().get(0).getNote().contains("a person decides"));
    }

    @Test
    void aDiscontinuationDatesTheProductsEndAndTheModelsMistakesAreNotedNotTrusted() {
        List<PriceHistory.Sourced> mentions = List.of(
                from("old-prices.bak", "2026-01-01", mention("Meridian 2", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2549.0, null, null)),
                from("meeting-notes.md", "2027-02-18", mention("Meridian 2", PriceMention.Audience.UNSPECIFIED, PriceMention.Kind.DISCONTINUATION, null, "2026-12-31", null)),
                from("notes.txt", null, mention("Meridian 2", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 2500.0, "March 2027", null)),
                from("junk.md", null, mention("Meridian 2", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, null, null, null)),
                from("junk.md", null, mention("", PriceMention.Audience.RETAIL, PriceMention.Kind.LIST, 1.0, null, null)));
        PriceHistory.Result result = PriceHistory.reconcile(mentions, null, AS_OF);
        ProductPricing meridian2 = product(result, "Meridian 2");
        assertEquals("2026-12-31", meridian2.getDiscontinuedFrom());
        assertEquals(PricePoint.Status.DISCONTINUED, meridian2.getDiscontinuation().getStatus());
        PriceSeries retail = series(meridian2, PriceMention.Audience.RETAIL);
        assertEquals(2549.0, retail.getCurrent().getAmount());
        assertTrue(retail.getCurrent().getNote().contains("discontinued from 2026-12-31"));
        PricePoint badDate = retail.getPoints().get(1);
        assertEquals(PricePoint.Status.UNDATED, badDate.getStatus());
        assertTrue(badDate.getNote().contains("'March 2027' is not an ISO date"));
        assertEquals(2, result.ignored().size());
        assertTrue(result.ignored().get(0).getNote().contains("no amount"));
        assertTrue(result.ignored().get(1).getNote().contains("no product"));
    }
}
