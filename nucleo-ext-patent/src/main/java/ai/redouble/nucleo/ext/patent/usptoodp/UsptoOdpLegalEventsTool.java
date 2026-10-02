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
 * Fetches legal status and event history for a US patent from USPTO ODP.
 *
 * <p>Resolves the application number from a patent number when needed,
 * then retrieves the transaction history via the transactions endpoint.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp_legal_events")
@DisplayName(value = "USPTO ODP Legal Events", action = "Fetching US Patent Legal Events")
@ToolDescription(value = "Fetch legal status and event history for a US patent from USPTO ODP.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 2)
public class UsptoOdpLegalEventsTool extends AbstractTool<UsptoOdpLegalEventsInput, UsptoOdpLegalEventsOutput> {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpLegalEventsTool.class);
    private static final UsptoOdpRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
    private final UsptoOdpClient client;
    public UsptoOdpLegalEventsTool(Identifiable parent) {
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
    public UsptoOdpLegalEventsOutput execute(JobResources resources, JobContext<UsptoOdpLegalEventsOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String patNum = UsptoOdpClient.normalizePatentNumber(input.getPatentNumber());
        String appNumber = input.getApplicationNumber();
        if ((patNum == null || patNum.isEmpty()) && (appNumber == null || appNumber.trim().isEmpty())) {
            throw new InvalidInputException("patentNumber", null, "Either patentNumber or applicationNumber is required");
        }
        client.setHttpClient(resources.getHttpClient());
        String legalStatus = null;
        if (appNumber == null || appNumber.trim().isEmpty()) {
            context.publish("Resolving application number", 20);
            JsonNode wrapper = client.findApplicationByPatentNumber(patNum);
            appNumber = client.applicationNumber(wrapper, patNum);
            legalStatus = wrapper.path("applicationMetaData").path("applicationStatusDescriptionText").asText(null);
        }

        // Fetch transactions
        context.publish("Fetching legal events", 50);
        JsonNode txResponse = client.get("patent/applications/" + appNumber + "/transactions", "");
        JsonNode eventBag = txResponse.path("eventDataBag");
        List<String> legalEvents = new ArrayList<>();
        if (eventBag.isArray()) {
            for (JsonNode event : eventBag) {
                String eventCode = event.path("eventCode").asText(null);
                String description = event.path("eventDescriptionText").asText(null);
                String date = event.path("eventDate").asText(null);
                legalEvents.add(date + " - " + description + " (" + eventCode + ")");
            }
        }
        UsptoOdpLegalEventsOutput output = new UsptoOdpLegalEventsOutput();
        output.setPatentNumber(patNum);
        output.setLegalStatus(legalStatus);
        output.setLegalEvents(legalEvents);
        log.info("Fetched {} legal events for patent {}", legalEvents.size(), patNum);
        context.publish("Complete", 100);
        return output;
    }
}
