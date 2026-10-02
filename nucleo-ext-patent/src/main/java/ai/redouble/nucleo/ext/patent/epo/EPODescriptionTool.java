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
 * Fetches the full description/specification text of a patent from EPO OPS.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo_description")
@DisplayName(value = "EPO Description", action = "Fetching Patent Description")
@ToolDescription(value = "Fetch the full description/specification text of a patent from EPO OPS by patent number.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPODescriptionTool extends AbstractEPOTool<EPODescriptionInput, EPODescriptionOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPODescriptionTool.class);
    public EPODescriptionTool(Identifiable parent) {
        super(parent, EPOService.RETRIEVAL, Duration.ofSeconds(60));
    }
    @Override
    public EPODescriptionOutput execute(JobResources resources, JobContext<EPODescriptionOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        try {
            context.publish("Fetching description from EPO", 30);
            connect(resources);
            String path = "/rest-services/published-data/publication/" + publication + "/description";
            String xmlResponse;
            try {
                xmlResponse = client.getAsXml(path);
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.wrapWithContext(e, "EPO OPS", "patentNumber", input.getPatentNumber(), "fetching the description");
            }
            JsonNode response = EPOClient.xmlToJson(xmlResponse);
            String descriptionText = EPOText.sectionText(response, "description", "p", "\n\n");
            if (descriptionText == null) {
                descriptionText = EPOText.rawText(xmlResponse, "description");
            }
            descriptionText = EPOClient.stripHtml(descriptionText);
            EPODescriptionOutput output = new EPODescriptionOutput();
            output.setPatentNumber(input.getPatentNumber());
            output.setDescription(descriptionText);
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
