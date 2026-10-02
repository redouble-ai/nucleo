/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import java.util.*;

/**
 * One product's prices for one audience in one currency, ranked by date: the current
 * price when the documents settle one, and every point that competed for it or was set
 * aside, newest first.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class PriceSeries {
    public enum Status {
        /** One price is in force and dated. */
        CURRENT,
        /** Nothing in force as of the run's date, and a price is dated after it. */
        SCHEDULED,
        /** The latest dated prices disagree. */
        CONFLICT,
        /** Every price in force is undated, so none can be ranked; the amounts are listed. */
        UNDATED,
        /** Only proposals, former prices or a discontinuation: nothing in force. */
        NOT_IN_FORCE
    }

    private PriceMention.Audience audience;
    private String currency;
    private Status status;
    private PricePoint current;
    private List<PricePoint> points = new ArrayList<>();

    public PriceMention.Audience getAudience() {return audience;}

    public void setAudience(PriceMention.Audience audience) {this.audience = audience;}

    public String getCurrency() {return currency;}

    public void setCurrency(String currency) {this.currency = currency;}

    public Status getStatus() {return status;}

    public void setStatus(Status status) {this.status = status;}

    public PricePoint getCurrent() {return current;}

    public void setCurrent(PricePoint current) {this.current = current;}

    public List<PricePoint> getPoints() {return points;}

    public void setPoints(List<PricePoint> points) {this.points = points;}
}
