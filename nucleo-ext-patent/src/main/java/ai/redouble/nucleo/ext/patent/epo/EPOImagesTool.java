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
 * Fetches patent document images metadata from EPO OPS.
 * Returns the PDF download URL and the representative drawing URL, for content
 * extraction by whatever document-parsing tool the deployment offers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("epo_images")
@DisplayName(value = "EPO Images", action = "Fetching Patent Images")
@ToolDescription(value = "Fetch patent document images from EPO OPS. Returns the PDF download URL, for content extraction with a document-parsing tool.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPOImagesTool extends AbstractEPOTool<EPOImagesInput, EPOImagesOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOImagesTool.class);
    private static final String EPO_BASE_URL = "https://ops.epo.org/3.2";
    public EPOImagesTool(Identifiable parent) {
        super(parent, EPOService.IMAGES, Duration.ofSeconds(60));
    }
    @Override
    public EPOImagesOutput execute(JobResources resources, JobContext<EPOImagesOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        context.publish("Fetching images metadata from EPO", 30);
        connect(resources);
        String path = "/rest-services/published-data/publication/" + publication + "/images";
        JsonNode response = client.get(path);
        EPOImagesOutput output = new EPOImagesOutput();
        output.setPatentNumber(input.getPatentNumber());

        // Navigate to document-instance(s)
        JsonNode instances = response
                .path("world-patent-data")
                .path("ops:document-inquiry")
                .path("ops:inquiry-result")
                .path("ops:document-instance");
        if (instances.isMissingNode()) {
            log.warn("EPO images: no document-instance found for {}", input.getPatentNumber());
            return output;
        }

        // Find FullDocument and Drawing instances
        if (instances.isArray()) {
            for (JsonNode instance : instances) {
                processInstance(instance, output);
            }
        }
        else {
            processInstance(instances, output);
        }
        log.info("EPO images fetched for: {} (pages={}, pdf={}, drawing={})",
                input.getPatentNumber(),
                output.getPageCount(),
                (output.getPdfUrl() != null),
                (output.getRepresentativeDrawingUrl() != null));
        context.publish("Complete", 100);
        return output;
    }
    private void processInstance(JsonNode instance, EPOImagesOutput output) {
        String desc = instance.path("desc").asText(null);
        if (desc == null) {
            desc = instance.path("@desc").asText(null);
        }
        String href = extractHref(instance);
        Integer pages = extractPageCount(instance);
        if (desc != null && desc.contains("FullDocument")) {
            if (href != null) {
                output.setPdfUrl(EPO_BASE_URL + href);
            }
            if (pages != null) {
                output.setPageCount(pages);
            }
        }
        else if (desc != null && desc.contains("Drawing")) {
            if (output.getRepresentativeDrawingUrl() == null && href != null) {
                output.setRepresentativeDrawingUrl(EPO_BASE_URL + href);
            }

            // If no page count from FullDocument yet, use drawing page count as fallback
            if (output.getPageCount() == null && pages != null) {
                output.setPageCount(pages);
            }
        }
    }
    private String extractHref(JsonNode instance) {
        // Try nested link element
        JsonNode linkNode = instance.path("link");
        if (!linkNode.isMissingNode()) {
            String href = linkNode.path("href").asText(null);
            if (href == null) {
                href = linkNode.path("@href").asText(null);
            }
            if (href != null) {
                return href;
            }
        }

        // Try @href directly on instance
        String href = instance.path("@href").asText(null);
        if (href == null) {
            href = instance.path("href").asText(null);
        }
        return href;
    }
    private Integer extractPageCount(JsonNode instance) {
        String pagesStr = instance.path("number-of-pages").asText(null);
        if (pagesStr == null) {
            pagesStr = instance.path("@number-of-pages").asText(null);
        }
        if (pagesStr != null) {
            try {
                return Integer.parseInt(pagesStr);
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
