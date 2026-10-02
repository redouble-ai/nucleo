/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

/**
 * One mention placed in a product's history: the amount, the date it ranks by, where it
 * came from, and the status the reconciliation gave it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class PricePoint {
    public enum Status {
        /** The latest price in force for this product, audience and currency, as of the run's date. */
        CURRENT,
        /** In force from a date after the run's date: decided, not yet current. */
        SCHEDULED,
        /** The same amount as the current price, stated by another document or earlier: the price did not change. */
        CONFIRMED,
        /** In force once, and a later, different price replaced it. */
        SUPERSEDED,
        /** Two prices in force on the same latest date disagree; a person decides. */
        CONFLICT,
        /** The document itself said this was a previous price. */
        FORMER,
        /** Suggested, planned or targeted; never in force. */
        PROPOSED,
        /** Stated as in force by a document that carries no date, so it cannot be ranked. */
        UNDATED,
        /** What the company pays; kept apart from selling prices. */
        COST,
        /** The product is no longer sold from this date. */
        DISCONTINUED,
        /** A decided change with no price in force before its date to apply it to; the note says so. */
        UNAPPLIED,
        /** A mention the reconciliation could not use, with the reason in the note. */
        IGNORED
    }

    private Status status;
    private Double amount;
    /** For a change: the percentage it decided; the amount is then what it was applied to, changed by this. */
    private Double percentChange;
    /** For a change: the price it was applied to, as "amount from source (date)". */
    private String appliedTo;
    private String currency;
    private String variant;
    private PriceMention.Audience audience;
    private PriceMention.Kind kind;
    /** The date this point ranks by: the price's effective date, else the statement's, else the document's. */
    private String date;
    private String dateBasis;
    private String source;
    private String quote;
    private String note;

    public Status getStatus() {return status;}

    public void setStatus(Status status) {this.status = status;}

    public Double getAmount() {return amount;}

    public void setAmount(Double amount) {this.amount = amount;}

    public Double getPercentChange() {return percentChange;}

    public void setPercentChange(Double percentChange) {this.percentChange = percentChange;}

    public String getAppliedTo() {return appliedTo;}

    public void setAppliedTo(String appliedTo) {this.appliedTo = appliedTo;}

    public String getCurrency() {return currency;}

    public void setCurrency(String currency) {this.currency = currency;}

    public String getVariant() {return variant;}

    public void setVariant(String variant) {this.variant = variant;}

    public PriceMention.Audience getAudience() {return audience;}

    public void setAudience(PriceMention.Audience audience) {this.audience = audience;}

    public PriceMention.Kind getKind() {return kind;}

    public void setKind(PriceMention.Kind kind) {this.kind = kind;}

    public String getDate() {return date;}

    public void setDate(String date) {this.date = date;}

    public String getDateBasis() {return dateBasis;}

    public void setDateBasis(String dateBasis) {this.dateBasis = dateBasis;}

    public String getSource() {return source;}

    public void setSource(String source) {this.source = source;}

    public String getQuote() {return quote;}

    public void setQuote(String quote) {this.quote = quote;}

    public String getNote() {return note;}

    public void setNote(String note) {this.note = note;}
}
