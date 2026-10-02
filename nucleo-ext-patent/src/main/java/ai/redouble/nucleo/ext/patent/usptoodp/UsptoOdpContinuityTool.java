/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.ext.patent.ratelimiters.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;

/**
 * Fetches continuity chain (parent/child applications) for a US patent from USPTO ODP.
 *
 * <p>Resolves the application number from a patent number when needed,
 * then retrieves parent and child continuity data.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp_continuity")
@DisplayName(value = "USPTO ODP Continuity", action = "Fetching US Patent Continuity Chain")
@ToolDescription(value = "Fetch continuity chain (parent/child applications) for a US patent from USPTO ODP.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 2)
public class UsptoOdpContinuityTool extends AbstractTool<UsptoOdpContinuityInput, UsptoOdpContinuityOutput> {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpContinuityTool.class);
    private static final UsptoOdpRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
    private final UsptoOdpClient client;
    public UsptoOdpContinuityTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(60));
        this.client = new UsptoOdpClient();
    }
    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.requireRateLimiter(RATE_LIMITER, null);
        return req;
    }
    @Override
    public UsptoOdpContinuityOutput execute(JobResources resources, JobContext<UsptoOdpContinuityOutput> context) throws LLMReadableCheckedException {
        context.publish("Resolving application", 5);
        String patNum = UsptoOdpClient.normalizePatentNumber(input.getPatentNumber());
        client.setHttpClient(resources.getHttpClient());
        String appNumber = client.applicationNumberFor(input.getPatentNumber(), input.getApplicationNumber());

        // Fetch continuity data
        context.publish("Fetching continuity chain", 50);
        JsonNode contResponse = client.get("patent/applications/" + appNumber + "/continuity", "");
        JsonNode dataBag = contResponse.path("patentFileWrapperDataBag");
        List<String> parentPatents = new ArrayList<>();
        List<String> childPatents = new ArrayList<>();
        if (dataBag.isArray() && !dataBag.isEmpty()) {
            JsonNode item = dataBag.get(0);

            // Parse parent continuity
            JsonNode parentBag = item.path("parentContinuityBag");
            if (parentBag.isArray()) {
                for (JsonNode parent : parentBag) {
                    String parentPatNum = parent.path("parentPatentNumber").asText(null);
                    if (parentPatNum != null) {
                        parentPatents.add(parentPatNum);
                    }
                }
            }

            // Parse child continuity
            JsonNode childBag = item.path("childContinuityBag");
            if (childBag.isArray()) {
                for (JsonNode child : childBag) {
                    String childPatNum = child.path("childPatentNumber").asText(null);
                    if (childPatNum != null) {
                        childPatents.add(childPatNum);
                    }
                }
            }
        }
        List<String> familyMembers = new ArrayList<>();
        familyMembers.addAll(parentPatents);
        familyMembers.addAll(childPatents);
        UsptoOdpContinuityOutput output = new UsptoOdpContinuityOutput();
        output.setPatentNumber(patNum);
        output.setParentPatents(parentPatents);
        output.setChildPatents(childPatents);
        output.setFamilyMembers(familyMembers);
        log.info("Fetched continuity for patent {}: {} parents, {} children", patNum, parentPatents.size(), childPatents.size());
        context.publish("Complete", 100);
        return output;
    }
}
