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
 * Converts patent numbers between DOCDB and EPODOC formats using EPO OPS number service.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo_number_convert")
@DisplayName(value = "EPO Number Convert", action = "Converting Patent Number")
@ToolDescription(value = "Convert patent numbers between DOCDB and EPODOC formats using EPO OPS number service.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPONumberConversionTool extends AbstractEPOTool<EPONumberConversionInput, EPONumberConversionOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPONumberConversionTool.class);
    public EPONumberConversionTool(Identifiable parent) {
        super(parent, EPOService.OTHER, Duration.ofSeconds(30));
    }
    @Override
    public EPONumberConversionOutput execute(JobResources resources, JobContext<EPONumberConversionOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String inputFormat = input.getInputFormat() != null ? input.getInputFormat() : "docdb";
        String outputFormat = input.getOutputFormat() != null ? input.getOutputFormat() : "epodoc";
        String publication = publication(input.getPatentNumber(), inputFormat);
        context.publish("Converting patent number via EPO", 30);
        connect(resources);
        String path = "/rest-services/number-service/publication/" + publication + "/" + outputFormat;
        JsonNode response = client.get(path);
        EPONumberConversionOutput output = new EPONumberConversionOutput();

        // Extract converted number from response
        String convertedNumber = extractConvertedNumber(response, outputFormat);
        output.setConvertedNumber(convertedNumber);
        log.info("EPO number conversion: {} ({}) -> {} ({})", input.getPatentNumber(), inputFormat, convertedNumber, outputFormat);
        context.publish("Complete", 100);
        return output;
    }
    private String extractConvertedNumber(JsonNode root, String outputFormat) {
        JsonNode standardized = root.path("world-patent-data")
                .path("standardization")
                .path("standardization-request")
                .path("output")
                .path("publication-reference")
                .path("document-id");
        if (standardized.isArray() && standardized.size() > 0) {
            for (JsonNode docId : standardized) {
                String docType = docId.path("document-id-type").asText(null);
                if (outputFormat.equals(docType) || docType == null) {
                    return number(docId, outputFormat);
                }
            }
            return number(standardized.get(0), outputFormat);
        }
        else if (!standardized.isMissingNode()) {
            return number(standardized, outputFormat);
        }
        return null;
    }

    /**
     * The converted number in the format asked for. DOCDB prints dotted like every other EPO
     * tool; EPODOC is by definition the undotted form, and asking for it is the reason this
     * tool exists, so it prints the way the EPODOC service reads it back.
     */
    private static String number(JsonNode docId, String outputFormat) {
        if (!"epodoc".equals(outputFormat)) {
            return EPOClient.docdbNumber(docId);
        }
        String docNumber = docId.path("doc-number").asText(null);
        if (docNumber == null) {
            return null;
        }
        return docId.path("country").asText("") + docNumber + docId.path("kind").asText("");
    }
}
