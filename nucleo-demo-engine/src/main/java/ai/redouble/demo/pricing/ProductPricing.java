/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import java.util.*;

/**
 * Everything the documents say about one product's money: its series per audience and
 * currency, what it costs the company, and whether it is still sold.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class ProductPricing {
    private String product;
    private List<String> aliases = new ArrayList<>();
    private List<PriceSeries> series = new ArrayList<>();
    private List<PricePoint> costs = new ArrayList<>();
    private String discontinuedFrom;
    private PricePoint discontinuation;

    public String getProduct() {return product;}

    public void setProduct(String product) {this.product = product;}

    public List<String> getAliases() {return aliases;}

    public void setAliases(List<String> aliases) {this.aliases = aliases;}

    public List<PriceSeries> getSeries() {return series;}

    public void setSeries(List<PriceSeries> series) {this.series = series;}

    public List<PricePoint> getCosts() {return costs;}

    public void setCosts(List<PricePoint> costs) {this.costs = costs;}

    public String getDiscontinuedFrom() {return discontinuedFrom;}

    public void setDiscontinuedFrom(String discontinuedFrom) {this.discontinuedFrom = discontinuedFrom;}

    public PricePoint getDiscontinuation() {return discontinuation;}

    public void setDiscontinuation(PricePoint discontinuation) {this.discontinuation = discontinuation;}
}
