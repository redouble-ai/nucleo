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
import java.util.*;

/**
 * Fetches the INPADOC patent family for a given patent from EPO OPS.
 * Returns all family member patent numbers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo_family")
@DisplayName(value = "EPO Family", action = "Fetching Patent Family")
@ToolDescription(value = "Fetch the INPADOC patent family members for a given patent from EPO OPS. Returns all related patent numbers across jurisdictions.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPOFamilyTool extends AbstractEPOTool<EPOFamilyInput, EPOFamilyOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOFamilyTool.class);
    public EPOFamilyTool(Identifiable parent) {
        super(parent, EPOService.INPADOC, Duration.ofSeconds(60));
    }
    @Override
    public EPOFamilyOutput execute(JobResources resources, JobContext<EPOFamilyOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        context.publish("Fetching patent family from EPO", 30);
        connect(resources);
        String path = "/rest-services/family/publication/" + publication;
        JsonNode response = client.get(path);
        EPOFamilyOutput output = new EPOFamilyOutput();

        // Parse family members
        List<String> familyMembers = new ArrayList<>();
        JsonNode patentFamily = response.path("world-patent-data").path("patent-family");
        String familyId = patentFamily.path("family-id").asText(null);
        output.setFamilyId(familyId);
        JsonNode familyMemberNodes = patentFamily.path("family-member");
        if (familyMemberNodes.isArray()) {
            for (JsonNode member : familyMemberNodes) {
                String patNum = extractFamilyMemberNumber(member);
                if (patNum != null) {
                    familyMembers.add(patNum);
                }
            }
        }
        else if (!familyMemberNodes.isMissingNode()) {
            String patNum = extractFamilyMemberNumber(familyMemberNodes);
            if (patNum != null) {
                familyMembers.add(patNum);
            }
        }
        output.setFamilyMembers(familyMembers);
        log.info("EPO family fetched for: {} ({} members, family ID: {})", input.getPatentNumber(), familyMembers.size(), familyId);
        context.publish("Complete", 100);
        return output;
    }
    private String extractFamilyMemberNumber(JsonNode member) {
        JsonNode pubRef = member.path("publication-reference").path("document-id");
        if (pubRef.isArray()) {
            for (JsonNode docId : pubRef) {
                String docType = docId.path("document-id-type").asText(null);
                if ("docdb".equals(docType) || docType == null) {
                    return EPOClient.docdbNumber(docId);
                }
            }
            if (pubRef.size() > 0) {
                return EPOClient.docdbNumber(pubRef.get(0));
            }
        }
        else if (!pubRef.isMissingNode()) {
            return EPOClient.docdbNumber(pubRef);
        }
        return null;
    }
}
