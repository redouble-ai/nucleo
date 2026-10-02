/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.time.*;

/**
 * Fetches the claims text of a patent from EPO OPS.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo_claims")
@DisplayName(value = "EPO Claims", action = "Fetching Patent Claims")
@ToolDescription(value = "Fetch the full claims text of a patent from EPO OPS by patent number.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPOClaimsTool extends AbstractEPOTool<EPOClaimsInput, EPOClaimsOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOClaimsTool.class);
    public EPOClaimsTool(Identifiable parent) {
        super(parent, EPOService.RETRIEVAL, Duration.ofSeconds(60));
    }
    @Override
    public EPOClaimsOutput execute(JobResources resources, JobContext<EPOClaimsOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        try {
            context.publish("Fetching claims from EPO", 30);
            connect(resources);
            String path = "/rest-services/published-data/publication/" + publication + "/claims";
            String xmlResponse;
            try {
                xmlResponse = client.getAsXml(path);
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.wrapWithContext(e, "EPO OPS", "patentNumber", input.getPatentNumber(), "fetching the claims");
            }
            EPOClaimsOutput output = new EPOClaimsOutput();
            JsonNode response = EPOClient.xmlToJson(xmlResponse);
            String claimsText = EPOText.sectionText(response, "claims", "claim-text", "\n");
            if (claimsText == null) {
                claimsText = EPOText.rawText(xmlResponse, "claims");
            }
            claimsText = EPOClient.stripHtml(claimsText);
            output.setClaims(claimsText);
            log.info("EPO claims fetched for: {} ({} chars)", input.getPatentNumber(), (claimsText != null ? claimsText.length() : 0));
            context.publish("Complete", 100);
            return output;
        }
        catch (LLMReadableCheckedException e) {
            throw e;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
