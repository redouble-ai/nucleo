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
 * Fetches the legal status and event history of a patent from EPO OPS.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo_legal_status")
@DisplayName(value = "EPO Legal Status", action = "Fetching Patent Legal Status")
@ToolDescription(value = "Fetch the legal status and event history of a patent from EPO OPS. Returns current status and chronological legal events.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPOLegalStatusTool extends AbstractEPOTool<EPOLegalStatusInput, EPOLegalStatusOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOLegalStatusTool.class);
    public EPOLegalStatusTool(Identifiable parent) {
        super(parent, EPOService.INPADOC, Duration.ofSeconds(60));
    }
    @Override
    public EPOLegalStatusOutput execute(JobResources resources, JobContext<EPOLegalStatusOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        context.publish("Fetching legal status from EPO", 30);
        connect(resources);
        String path = "/rest-services/legal/publication/" + publication;
        JsonNode response = client.get(path);
        EPOLegalStatusOutput output = new EPOLegalStatusOutput();

        // Parse legal events
        List<String> legalEvents = new ArrayList<>();
        String currentStatus = null;
        JsonNode legalData = response.path("world-patent-data").path("register-search")
                .path("register-documents").path("register-document");
        if (legalData.isMissingNode()) {
            legalData = response.path("world-patent-data").path("legal");
        }
        JsonNode events = legalData.path("legal");
        if (events.isMissingNode()) {
            events = legalData;
        }
        if (events.isArray()) {
            for (JsonNode event : events) {
                String eventStr = formatLegalEvent(event);
                if (eventStr != null) {
                    legalEvents.add(eventStr);
                }
            }
        }
        else if (!events.isMissingNode()) {
            String eventStr = formatLegalEvent(events);
            if (eventStr != null) {
                legalEvents.add(eventStr);
            }
        }

        // Determine current status from the most recent event
        if (!legalEvents.isEmpty()) {
            currentStatus = legalEvents.get(legalEvents.size() - 1);
        }
        output.setLegalEvents(legalEvents);
        output.setCurrentStatus(currentStatus);
        log.info("EPO legal status fetched for: {} ({} events)", input.getPatentNumber(), legalEvents.size());
        context.publish("Complete", 100);
        return output;
    }
    private String formatLegalEvent(JsonNode event) {
        String code = event.path("code").asText(null);
        String date = event.path("date").asText(null);
        String desc = event.path("desc").asText(null);

        // Try alternative structure
        if (code == null) {
            code = event.path("L007EP").asText(null);
        }
        if (desc == null) {
            desc = event.path("text").asText(null);
        }
        if (code == null && desc == null && date == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (date != null) {
            sb.append("[").append(date).append("] ");
        }
        if (code != null) {
            sb.append(code);
        }
        if (desc != null) {
            if (code != null) sb.append(" - ");
            sb.append(desc);
        }
        return sb.toString();
    }
}
