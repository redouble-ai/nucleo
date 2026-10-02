/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.nucleo.harness.schema.*;

/**
 * One price a document states, as the model reads it: what it is for, who pays it, what
 * kind of statement it is, and the dates the document attaches to it. Nothing here decides
 * whether the price is current; that is {@link PriceHistory}'s arithmetic over every
 * mention from every document.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class PriceMention {
    public enum Audience {
        /** The price a customer pays. */
        RETAIL,
        /** The price a dealer or reseller pays. */
        DEALER,
        /** The document does not say. */
        UNSPECIFIED
    }

    public enum Kind {
        /** A price list, catalogue, portal, sign, deck or website states this as the price in force. */
        LIST,
        /** An invoice, order or quote charged this price on its date. */
        TRANSACTION,
        /** Suggested, planned, targeted or under discussion: not in force. */
        PROPOSED,
        /** The document itself presents it as a previous price: "was", "old", "before", "replaced by". */
        FORMER,
        /** What the company pays a supplier or spends per unit: never a selling price. */
        COST,
        /** A decided change relative to the price in force, as a percentage; the amount is computed from what the change applies to. */
        CHANGE,
        /** The product is no longer sold from a date; carries no amount. */
        DISCONTINUATION
    }

    @LLMRequired
    @LLMDescription("The product or service the price is for, by its proper name as the document names it, without size, colour or SKU")
    private String product;
    @LLMDescription("Size, colour or configuration when the price is specific to one; null when it applies to the product as such")
    private String variant;
    @LLMRequired
    @LLMDescription("Who pays the company: RETAIL a customer, DEALER a dealer, reseller or dealer account (an invoice or order takes the audience of whoever it bills), UNSPECIFIED when the document does not say or the entry is a COST")
    private Audience audience;
    @LLMRequired
    @LLMDescription("LIST: a price list, catalogue, portal, sign, deck or website states it as the price in force. TRANSACTION: an invoice, order or quote charged it on its date. PROPOSED: suggested, planned, a target, under discussion, not decided. FORMER: the document itself presents it as a previous price ('was', 'old', 'before', 'replaced'). COST: what the company pays a supplier or spends per unit, never a selling price. CHANGE: a decided rise or cut by a percentage from a date, with percentChange and no amount. DISCONTINUATION: the product is no longer sold from a date, no amount")
    private Kind kind;
    @LLMDescription("The amount as a number, without thousands separators; null only for DISCONTINUATION and CHANGE")
    private Double amount;
    @LLMDescription("For CHANGE only: the decided change in percent, positive for a rise and negative for a cut ('up 20 percent' is 20); null for every other kind")
    private Double percentChange;
    @LLMDescription("The ISO 4217 code of the currency: 'euros' and the euro sign are EUR, dollars are USD")
    private String currency;
    @LLMDescription("ISO date (YYYY-MM-DD) from which the document says the price applies ('from 1 April 2027', 'effective 2027-01-01'); null when it says nothing of the kind. A month without a day is its first day")
    private String effectiveFrom;
    @LLMDescription("ISO date the document attaches to the statement itself: an invoice date, a meeting date, a letter date, a screenshot's 'as of'; null when the document carries none near it")
    private String statedOn;
    @LLMRequired
    @LLMDescription("Where the dates came from in the document, or why there is none")
    private String dateBasis;
    @LLMRequired
    @LLMDescription("The words of the document that state the price, verbatim, at most 200 characters")
    private String quote;

    public String getProduct() {return product;}

    public void setProduct(String product) {this.product = product;}

    public String getVariant() {return variant;}

    public void setVariant(String variant) {this.variant = variant;}

    public Audience getAudience() {return audience;}

    public void setAudience(Audience audience) {this.audience = audience;}

    public Kind getKind() {return kind;}

    public void setKind(Kind kind) {this.kind = kind;}

    public Double getAmount() {return amount;}

    public void setAmount(Double amount) {this.amount = amount;}

    public Double getPercentChange() {return percentChange;}

    public void setPercentChange(Double percentChange) {this.percentChange = percentChange;}

    public String getCurrency() {return currency;}

    public void setCurrency(String currency) {this.currency = currency;}

    public String getEffectiveFrom() {return effectiveFrom;}

    public void setEffectiveFrom(String effectiveFrom) {this.effectiveFrom = effectiveFrom;}

    public String getStatedOn() {return statedOn;}

    public void setStatedOn(String statedOn) {this.statedOn = statedOn;}

    public String getDateBasis() {return dateBasis;}

    public void setDateBasis(String dateBasis) {this.dateBasis = dateBasis;}

    public String getQuote() {return quote;}

    public void setQuote(String quote) {this.quote = quote;}
}
