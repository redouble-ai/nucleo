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
 * Fetches foreign priority claims for a US patent from USPTO ODP.
 *
 * <p>Resolves the application number from a patent number when needed,
 * then retrieves the foreign priority data. Determines the earliest
 * priority date from the claims.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp_foreign_priority")
@DisplayName(value = "USPTO ODP Foreign Priority", action = "Fetching US Patent Foreign Priority")
@ToolDescription(value = "Fetch foreign priority claims for a US patent from USPTO ODP.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 2)
public class UsptoOdpForeignPriorityTool extends AbstractTool<UsptoOdpForeignPriorityInput, UsptoOdpForeignPriorityOutput> {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpForeignPriorityTool.class);
    private static final UsptoOdpRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
    private final UsptoOdpClient client;
    public UsptoOdpForeignPriorityTool(Identifiable parent) {
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
    public UsptoOdpForeignPriorityOutput execute(JobResources resources, JobContext<UsptoOdpForeignPriorityOutput> context) throws LLMReadableCheckedException {
        context.publish("Resolving application", 5);
        String patNum = UsptoOdpClient.normalizePatentNumber(input.getPatentNumber());
        client.setHttpClient(resources.getHttpClient());
        String appNumber = client.applicationNumberFor(input.getPatentNumber(), input.getApplicationNumber());

        // Fetch foreign priority data
        context.publish("Fetching foreign priority claims", 50);
        JsonNode fpResponse = client.get("patent/applications/" + appNumber + "/foreign-priority", "");
        JsonNode dataBag = fpResponse.path("patentFileWrapperDataBag");
        List<String> priorityClaims = new ArrayList<>();
        String earliestDate = null;
        if (dataBag.isArray() && !dataBag.isEmpty()) {
            JsonNode fpBag = dataBag.get(0).path("foreignPriorityBag");
            if (fpBag.isArray()) {
                for (JsonNode fp : fpBag) {
                    String office = fp.path("ipOfficeName").asText(null);
                    String filingDate = fp.path("filingDate").asText(null);
                    String fpAppNumber = fp.path("applicationNumberText").asText(null);
                    priorityClaims.add(office + " - " + filingDate + " - " + fpAppNumber);
                    if (filingDate != null && (earliestDate == null || filingDate.compareTo(earliestDate) < 0)) {
                        earliestDate = filingDate;
                    }
                }
            }
        }
        UsptoOdpForeignPriorityOutput output = new UsptoOdpForeignPriorityOutput();
        output.setPatentNumber(patNum);
        output.setPriorityDate(earliestDate);
        output.setPriorityClaims(priorityClaims);
        log.info("Fetched {} foreign priority claims for patent {}", priorityClaims.size(), patNum);
        context.publish("Complete", 100);
        return output;
    }
}
