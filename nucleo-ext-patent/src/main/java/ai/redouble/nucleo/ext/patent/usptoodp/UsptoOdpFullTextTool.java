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
 * Fetches full patent text (abstract, claims, description) for a US patent.
 *
 * <p>Downloads the split grant XML from ODP's associated-documents endpoint
 * and parses the WIPO ST.36 Redbook elements. Returns a {@link PatentFullContentArtifact}
 * that includes all bibliographic fields plus the full text body.
 *
 * <p>Expensive operation - only invoke when the thinker explicitly needs the patent body.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp_fulltext")
@DisplayName(value = "USPTO ODP Full Text", action = "Fetching US Patent Full Text")
@ToolDescription(value = "Fetch full patent text (abstract, claims, description) for a US patent. Expensive - use only when body text is explicitly needed.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 2, max = 3)
public class UsptoOdpFullTextTool extends AbstractTool<UsptoOdpFullTextInput, UsptoOdpFullTextOutput> {
    private static final Logger log = LoggerFactory.getLogger(UsptoOdpFullTextTool.class);
    private static final UsptoOdpRateLimiter RATE_LIMITER =
            RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
    private final UsptoOdpClient client;
    public UsptoOdpFullTextTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofMinutes(3));
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
    public UsptoOdpFullTextOutput execute(JobResources resources, JobContext<UsptoOdpFullTextOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        if (input.getPatentNumber() == null || input.getPatentNumber().trim().isEmpty()) {
            throw new InvalidInputException("patentNumber", input.getPatentNumber(), "patentNumber is required");
        }
        client.setHttpClient(resources.getHttpClient());
        String patNum = UsptoOdpClient.normalizePatentNumber(input.getPatentNumber());
        context.publish("Fetching patent metadata", 15);
        JsonNode item = client.findApplicationByPatentNumber(patNum);
        PatentArtifact base = UsptoOdpSearchTool.parseMetaDataToArtifact(item);
        if (base == null) {
            throw new ResourceNotFoundException("US patent", patNum);
        }
        String appNumber = client.applicationNumber(item, patNum);
        context.publish("Fetching grant XML", 35);
        String xmlUri = client.grantXmlUri(appNumber);
        if (xmlUri == null) {
            throw new ExternalServiceException("USPTO ODP", "No grant XML available for patent " + patNum);
        }
        context.publish("Parsing full patent text", 55);
        String xml = client.downloadXml(xmlUri);

        // Build the full-content artifact (copy biblio fields from base)
        PatentFullContentArtifact fullArtifact = new PatentFullContentArtifact();
        fullArtifact.setPatentNumber(base.getPatentNumber());
        fullArtifact.setTitle(base.getTitle());
        fullArtifact.setApplicationNumber(base.getApplicationNumber());
        fullArtifact.setFilingDate(base.getFilingDate());
        fullArtifact.setPublicationDate(base.getPublicationDate());
        fullArtifact.setApplicants(base.getApplicants());
        fullArtifact.setInventors(base.getInventors());
        fullArtifact.setCpcClassifications(base.getCpcClassifications());
        fullArtifact.setKindCode(base.getKindCode());
        fullArtifact.setJurisdiction(base.getJurisdiction());
        fullArtifact.setSource(base.getSource());
        fullArtifact.setUrl(base.getUrl());
        fullArtifact.setPatentAbstract(UsptoOdpClient.sectionText(xml, UsptoOdpClient.ABSTRACT));
        fullArtifact.setClaims(UsptoOdpClient.sectionText(xml, UsptoOdpClient.CLAIMS));
        fullArtifact.setDescription(UsptoOdpClient.sectionText(xml, UsptoOdpClient.DESCRIPTION));
        UsptoOdpFullTextOutput output = new UsptoOdpFullTextOutput();
        output.setPatent(fullArtifact);
        log.info("Full text fetched for US patent {} (abstract={}, claims={}, desc={} chars)",
                patNum,
                len(fullArtifact.getPatentAbstract()),
                len(fullArtifact.getClaims()),
                len(fullArtifact.getDescription()));
        context.publish("Complete", 100);
        return output;
    }
    private static int len(String s) {
        return s != null ? s.length() : 0;
    }
}
