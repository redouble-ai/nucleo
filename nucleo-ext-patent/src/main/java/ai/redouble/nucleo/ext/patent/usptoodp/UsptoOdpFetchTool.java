/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.ext.patent.ratelimiters.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.time.*;

/**
 * Fetches detailed US patent data by patent number from USPTO Open Data Portal.
 *
 * <p>Two API calls: (1) search by patent number for bibliographic metadata,
 * (2) associated-documents -> split grant XML for the abstract.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp_fetch")
@DisplayName(value = "USPTO ODP Fetch", action = "Fetching US Patent Details")
@ToolDescription(value = "Fetch detailed US patent bibliographic data by patent number from USPTO ODP. Returns title, inventors, applicants, dates, classifications, and abstract.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 2)
public class UsptoOdpFetchTool extends AbstractTool<UsptoOdpFetchInput, UsptoOdpFetchOutput> {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpFetchTool.class);
    private static final UsptoOdpRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
    private final UsptoOdpClient client;
    public UsptoOdpFetchTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(90));
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
    public UsptoOdpFetchOutput execute(JobResources resources, JobContext<UsptoOdpFetchOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        if (input.getPatentNumber() == null || input.getPatentNumber().trim().isEmpty()) {
            throw new InvalidInputException("patentNumber", input.getPatentNumber(), "patentNumber is required");
        }
        context.publish("Fetching patent metadata", 20);
        client.setHttpClient(resources.getHttpClient());
        String patNum = UsptoOdpClient.normalizePatentNumber(input.getPatentNumber());
        JsonNode item = client.findApplicationByPatentNumber(patNum);
        PatentArtifact patent = UsptoOdpSearchTool.parseMetaDataToArtifact(item);
        if (patent == null) {
            throw new ResourceNotFoundException("US patent", patNum);
        }
        String appNumber = item.path("applicationNumberText").asText(null);

        // The abstract lives in the split grant XML behind associated-documents
        if (appNumber != null) {
            context.publish("Fetching patent abstract", 60);
            try {
                String abstractText = fetchAbstract(appNumber);
                if (abstractText != null) {
                    patent.setPatentAbstract(abstractText);
                }
            }
            catch (Exception e) {
                log.warn("Failed to fetch abstract for {} (app {}): {}", patNum, appNumber, e.getMessage());
            }
        }
        UsptoOdpFetchOutput output = new UsptoOdpFetchOutput();
        output.setPatent(patent);
        log.info("Fetched US patent: {} - {}", patent.getPatentNumber(), patent.getTitle());
        context.publish("Complete", 100);
        return output;
    }

    /**
     * The {@code <abstract>} element of the split grant XML behind the associated-documents
     * endpoint; null when ODP lists no document for the application.
     */
    private String fetchAbstract(String applicationNumber) throws LLMReadableCheckedException {
        String xmlUri = client.grantXmlUri(applicationNumber);
        if (xmlUri == null) {
            return null;
        }
        return UsptoOdpClient.sectionText(client.downloadXml(xmlUri), UsptoOdpClient.ABSTRACT);
    }
}
