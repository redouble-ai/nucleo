/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.time.*;

/**
 * Fetches full patent content (biblio + claims + description) from EPO OPS in one tool call.
 * Returns a {@link PatentFullContentArtifact} with all bibliographic fields plus full text body.
 *
 * <p>Expensive: makes three sequential API calls (biblio, claims, description).
 * Use only when the thinker explicitly needs the patent body.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("epo_fulltext")
@DisplayName(value = "EPO Full Text", action = "Fetching Patent Full Text")
@ToolDescription(value = "Fetch full patent text (biblio + claims + description) from EPO OPS. Expensive - use only when body text is explicitly needed.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 3, max = 3)
public class EPOFullTextTool extends AbstractEPOTool<EPOFullTextInput, EPOFullTextOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOFullTextTool.class);
    public EPOFullTextTool(Identifiable parent) {
        super(parent, EPOService.RETRIEVAL, Duration.ofMinutes(3));
    }
    @Override
    public EPOFullTextOutput execute(JobResources resources, JobContext<EPOFullTextOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        connect(resources);

        // Step 1: Fetch bibliographic data via EPO biblio endpoint
        context.publish("Fetching bibliographic data", 15);
        String biblioPath = "/rest-services/published-data/publication/" + publication + "/biblio";
        JsonNode biblioJson = client.get(biblioPath);
        PatentFullContentArtifact fullArtifact = new PatentFullContentArtifact();
        fullArtifact.setSource("epo");
        fullArtifact.setPatentNumber(input.getPatentNumber());

        // Step 2: Fetch claims
        context.publish("Fetching claims", 40);
        try {
            String claimsPath = "/rest-services/published-data/publication/" + publication + "/claims";
            String claimsXml = client.getAsXml(claimsPath);
            JsonNode claimsJson = EPOClient.xmlToJson(claimsXml);
            String claimsText = EPOText.sectionText(claimsJson, "claims", "claim-text", "\n\n");
            if (claimsText == null) {
                claimsText = EPOText.rawText(claimsXml, "claims");
            }
            fullArtifact.setClaims(EPOClient.stripHtml(claimsText));
        }
        catch (Exception e) {
            log.warn("Failed to fetch claims for {}: {}", input.getPatentNumber(), e.getMessage());
        }

        // Step 3: Fetch description
        context.publish("Fetching description", 70);
        try {
            String descPath = "/rest-services/published-data/publication/" + publication + "/description";
            String descXml = client.getAsXml(descPath);
            JsonNode descJson = EPOClient.xmlToJson(descXml);
            String descText = EPOText.sectionText(descJson, "description", "p", "\n\n");
            if (descText == null) {
                descText = EPOText.rawText(descXml, "description");
            }
            fullArtifact.setDescription(EPOClient.stripHtml(descText));
        }
        catch (Exception e) {
            log.warn("Failed to fetch description for {}: {}", input.getPatentNumber(), e.getMessage());
        }
        EPOFullTextOutput output = new EPOFullTextOutput();
        output.setPatent(fullArtifact);
        log.info("Full text fetched for {} (claims={}, desc={} chars)", input.getPatentNumber(), len(fullArtifact.getClaims()), len(fullArtifact.getDescription()));
        context.publish("Complete", 100);
        return output;
    }
    private static int len(String s) {
        return s != null ? s.length() : 0;
    }
}
