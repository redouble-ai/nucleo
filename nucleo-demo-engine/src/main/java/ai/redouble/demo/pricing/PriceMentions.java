/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * What the model answers for one document: the date the document carries as a whole, and
 * every price it states.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class PriceMentions {
    @LLMDescription("The ISO date (YYYY-MM-DD) of the document as a whole: an invoice date, a meeting date, a list's 'effective from', a screenshot's 'as of', a letter's date, or a date in the file name when the text carries none; null when there is none anywhere. A month without a day is its first day")
    private String documentDate;
    @LLMRequired
    @LLMDescription("Where the document's date came from, or why there is none")
    private String documentDateBasis;
    @LLMRequired
    @LLMDescription("Every price the document states, one entry per product, audience and amount; an empty list when it states none")
    private List<PriceMention> mentions;

    public String getDocumentDate() {return documentDate;}

    public void setDocumentDate(String documentDate) {this.documentDate = documentDate;}

    public String getDocumentDateBasis() {return documentDateBasis;}

    public void setDocumentDateBasis(String documentDateBasis) {this.documentDateBasis = documentDateBasis;}

    public List<PriceMention> getMentions() {return mentions;}

    public void setMentions(List<PriceMention> mentions) {this.mentions = mentions;}
}
