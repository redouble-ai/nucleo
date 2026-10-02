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
 * The judgment tier of the pricing demo: one call, one grade up, that decides which of the
 * names the documents used mean the same product ("Kestrel 1", "Kestrel 1 gravel", "the
 * Kestrel") so that the arithmetic over dates runs on products and not on spellings. The
 * names are all it sees; the prices never reach it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
@DisplayName(value = "Canonicalize Products", action = "Grouping product names")
@ToolName("canonicalize_products")
@ToolDescription(value = "Groups the product names documents used by the product each one means.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 2, max = 2)
public class CanonicalizeProductsTool extends AbstractModelDependentTool<ProductNames, ProductGroups> {
    private static final String INSTRUCTIONS = "Group these names by the product or service each one means. Names that"
            + " differ only in spelling, capitalization, a brand name in front ('Halcyon Meridian 3' is 'Meridian 3'),"
            + " a descriptor ('gravel', 'city', 'trail'), an article or a model-family shorthand are one product. Different model numbers or generations are different products:"
            + " a '2' is never a '3'. A part, a service or a component is its own product, and the same service or"
            + " part described in different words ('bearing swap', 'headset bearing replacement') is one product."
            + " Every name goes in exactly one group.";
    public CanonicalizeProductsTool(Identifiable parent) {
        super(parent, Grade.MEDIUM);
        setTimeout(Duration.ofMinutes(3));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        wireConversation(req, Depth.QUICK, OutputDeclaration.of(OutputSize.STANDARD), ProductGroups.class, INSTRUCTIONS);
        return req;
    }

    @Override
    public ProductGroups execute(JobResources resources, JobContext<ProductGroups> context) throws LLMReadableCheckedException {
        try {
            return converse(resources);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
