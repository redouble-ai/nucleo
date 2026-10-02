/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import java.time.*;

/**
 * The reading tier of the pricing demo: a small model reads one document's text and lists
 * every price it states, with what the document says about each (who pays it, whether it
 * is in force, proposed, former or a cost, and the dates attached). One call per document,
 * all documents in parallel. The tool never decides what is current; it reports what the
 * document says, and {@link PriceHistory} decides across documents.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
@DisplayName(value = "Extract Prices", action = "Reading the prices a document states")
@ToolName("extract_prices")
@ToolDescription(value = "Lists every price one document states, with audience, kind, currency and the dates the document attaches.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 3, max = 3)
public class ExtractPricesTool extends AbstractModelDependentTool<DocumentText, PriceMentions> {
    private static final String INSTRUCTIONS = "List every price this document states for a product or service, one entry"
            + " per product, audience and amount. Report what the document says, never what you think the price should be:"
            + " a price the document presents as previous is FORMER, a suggestion or target is PROPOSED, what the company"
            + " pays a supplier is COST, a list or catalogue or portal or sign or deck states LIST, an invoice or order"
            + " states TRANSACTION. A statement that a product is discontinued, no longer sold, or has sold its last"
            + " units is a DISCONTINUATION entry with no amount and the date it names as effectiveFrom; list it even"
            + " though it carries no price."
            + " The audience is who pays the company: RETAIL a customer, DEALER a dealer, reseller or dealer account;"
            + " an invoice or order takes the audience of whoever it bills, and when the document does not say, UNSPECIFIED."
            + " A COST has audience UNSPECIFIED, and a supplier's previous price is a COST too, not a FORMER price."
            + " A decided rise or cut of a price by a percentage from a date is a CHANGE with percentChange and no amount,"
            + " one entry per product and audience it names; a change stated for retail and dealer prices is two"
            + " entries. Margins, discounts, quantities, counts and dimensions are not prices; skip them. Dates are the"
            + " document's own: an 'effective from' or 'from <date>' near the price is effectiveFrom, the document's date"
            + " is statedOn, a date in the file name is the document's date when the text has none, and a document"
            + " with no date anywhere has null dates and says so in dateBasis. Quote the words verbatim.";
    /** The reading tier's own seat: a SMALL model, whichever the deployment serves there. */
    public ExtractPricesTool(Identifiable parent) {
        super(parent, Grade.SMALL);
        setTimeout(Duration.ofMinutes(4));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        // a list of records: STANDARD, which is also what the reservation the cap judges is sized by
        wireConversation(req, Depth.QUICK, OutputDeclaration.of(OutputSize.STANDARD), PriceMentions.class, INSTRUCTIONS);
        return req;
    }

    @Override
    public PriceMentions execute(JobResources resources, JobContext<PriceMentions> context) throws LLMReadableCheckedException {
        try {
            return converse(resources);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
