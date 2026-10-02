/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;

/**
 * Fetches bibliographic data for a patent from EPO OPS.
 * Returns a fully populated PatentArtifact with title, abstract, applicants, inventors,
 * dates, and classification codes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo_biblio")
@DisplayName(value = "EPO Biblio", action = "Fetching Patent Bibliographic Data")
@ToolDescription(value = "Fetch detailed bibliographic data for a patent from EPO OPS. Returns title, abstract, applicants, inventors, dates, and classification codes.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EPOBiblioTool extends AbstractEPOTool<EPOBiblioInput, EPOBiblioOutput> {
    private static final Logger log = LoggerFactory.getLogger(EPOBiblioTool.class);

    public EPOBiblioTool(Identifiable parent) {
        super(parent, EPOService.RETRIEVAL, Duration.ofSeconds(60));
    }

    @Override
    public EPOBiblioOutput execute(JobResources resources, JobContext<EPOBiblioOutput> context) throws LLMReadableCheckedException {
        context.publish("Validating input", 5);
        String publication = publication(input.getPatentNumber(), input.getInputFormat());
        context.publish("Fetching bibliographic data from EPO", 30);
        connect(resources);
        String path = "/rest-services/published-data/publication/" + publication + "/biblio";
        JsonNode response = client.get(path);
        EPOBiblioOutput output = new EPOBiblioOutput();

        // Navigate to exchange-document
        JsonNode exchangeDoc = navigateToExchangeDocument(response);
        if (exchangeDoc == null) {
            throw new ExternalServiceException("EPO", "Unable to parse bibliographic data for: " + input.getPatentNumber());
        }
        PatentArtifact patent = new PatentArtifact();
        patent.setSource("epo");

        // Extract patent number components
        String country = exchangeDoc.path("country").asText(null);
        String docNumber = exchangeDoc.path("doc-number").asText(null);
        String kind = exchangeDoc.path("kind").asText(null);
        patent.setJurisdiction(country);
        patent.setKindCode(kind);
        if (country != null && docNumber != null) {
            patent.setPatentNumber(EPOClient.docdbNumber(exchangeDoc));
            // Espacenet's query syntax takes the number undotted
            patent.setUrl("https://worldwide.espacenet.com/patent/search?q=pn%3D" + country + docNumber + (kind != null ? kind : ""));
        }

        // Extract bibliographic data from different sections
        JsonNode biblioData = exchangeDoc.path("bibliographic-data");

        // Title
        patent.setTitle(extractTitle(biblioData));

        // Abstract
        patent.setPatentAbstract(EPOClient.stripHtml(extractAbstract(exchangeDoc)));

        // Applicants
        patent.setApplicants(extractNames(biblioData.path("parties").path("applicants").path("applicant")));

        // Inventors
        patent.setInventors(extractNames(biblioData.path("parties").path("inventors").path("inventor")));

        // Dates
        extractDates(biblioData, patent);

        // Application number
        JsonNode appRef = biblioData.path("application-reference").path("document-id");
        patent.setApplicationNumber(extractDocId(appRef));

        // Classifications
        patent.setIpcClassifications(extractClassifications(biblioData.path("classifications-ipcr").path("classification-ipcr")));
        patent.setCpcClassifications(extractClassifications(biblioData.path("patent-classifications").path("patent-classification")));
        output.setPatent(patent);
        log.info("EPO biblio fetched for: {}", patent.getPatentNumber());
        context.publish("Complete", 100);
        return output;
    }

    private JsonNode navigateToExchangeDocument(JsonNode root) {
        JsonNode wpd = root.path("world-patent-data");
        if (wpd.isMissingNode())
            wpd = root;
        JsonNode regData = wpd.path("register-search");
        if (!regData.isMissingNode()) {
            JsonNode docs = regData.path("register-documents").path("register-document");
            if (docs.isArray() && docs.size() > 0)
                return docs.get(0);
            if (!docs.isMissingNode())
                return docs;
        }
        JsonNode exchDocs = wpd.path("exchange-documents").path("exchange-document");
        if (!exchDocs.isMissingNode()) {
            if (exchDocs.isArray() && exchDocs.size() > 0)
                return exchDocs.get(0);
            return exchDocs;
        }
        JsonNode exchDoc = wpd.path("exchange-document");
        if (!exchDoc.isMissingNode()) {
            if (exchDoc.isArray() && exchDoc.size() > 0)
                return exchDoc.get(0);
            return exchDoc;
        }
        return null;
    }

    private String extractTitle(JsonNode biblioData) {
        JsonNode inventionTitle = biblioData.path("invention-title");
        if (inventionTitle.isArray()) {
            for (JsonNode title : inventionTitle) {
                String lang = title.path("lang").asText(null);
                if ("en".equals(lang) || lang == null) {
                    String text = title.path("").asText(null);
                    if (text == null)
                        text = title.asText(null);
                    if (text != null)
                        return text;
                }
            }
            // Fall back to first title
            if (inventionTitle.size() > 0) {
                String text = inventionTitle.get(0).path("").asText(null);
                if (text == null)
                    text = inventionTitle.get(0).asText(null);
                return text;
            }
        }
        else if (!inventionTitle.isMissingNode()) {
            String text = inventionTitle.path("").asText(null);
            if (text == null)
                text = inventionTitle.asText(null);
            return text;
        }
        return null;
    }

    private String extractAbstract(JsonNode exchangeDoc) {
        JsonNode abstractNode = exchangeDoc.path("abstract");
        if (abstractNode.isArray()) {
            for (JsonNode abs : abstractNode) {
                String lang = abs.path("lang").asText(null);
                if ("en".equals(lang) || lang == null) {
                    return extractTextContent(abs);
                }
            }
            if (abstractNode.size() > 0) {
                return extractTextContent(abstractNode.get(0));
            }
        }
        else if (!abstractNode.isMissingNode()) {
            return extractTextContent(abstractNode);
        }
        return null;
    }

    private String extractTextContent(JsonNode node) {
        JsonNode p = node.path("p");
        if (p.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode para : p) {
                if (sb.length() > 0)
                    sb.append(" ");
                sb.append(para.asText(""));
            }
            return sb.toString();
        }
        else if (!p.isMissingNode()) {
            return p.asText(null);
        }
        return node.asText(null);
    }

    private List<String> extractNames(JsonNode nameNodes) {
        List<String> names = new ArrayList<>();
        if (nameNodes.isArray()) {
            for (JsonNode nameNode : nameNodes) {
                String name = extractSingleName(nameNode);
                if (name != null)
                    names.add(name);
            }
        }
        else if (!nameNodes.isMissingNode()) {
            String name = extractSingleName(nameNodes);
            if (name != null)
                names.add(name);
        }
        return names.isEmpty() ? null : names;
    }

    private String extractSingleName(JsonNode nameNode) {
        JsonNode nameContent = nameNode.path("addressbook").path("name");
        if (!nameContent.isMissingNode()) {
            return nameContent.asText(null);
        }
        JsonNode snm = nameNode.path("addressbook").path("snm");
        if (!snm.isMissingNode()) {
            String surname = snm.asText("");
            String given = nameNode.path("addressbook").path("fnm").asText("");
            return (given.isEmpty() ? surname : given + " " + surname).trim();
        }
        return null;
    }

    private void extractDates(JsonNode biblioData, PatentArtifact patent) {
        // Publication date
        JsonNode pubRef = biblioData.path("publication-reference").path("document-id");
        patent.setPublicationDate(extractDate(pubRef));

        // Filing/application date
        JsonNode appRef = biblioData.path("application-reference").path("document-id");
        patent.setFilingDate(extractDate(appRef));

        // Priority date
        JsonNode priorClaims = biblioData.path("priority-claims").path("priority-claim");
        if (priorClaims.isArray() && priorClaims.size() > 0) {
            patent.setPriorityDate(extractDate(priorClaims.get(0).path("document-id")));
        }
        else if (!priorClaims.isMissingNode()) {
            patent.setPriorityDate(extractDate(priorClaims.path("document-id")));
        }
    }

    private String extractDate(JsonNode docIdNode) {
        if (docIdNode.isArray()) {
            for (JsonNode docId : docIdNode) {
                String date = docId.path("date").asText(null);
                if (date != null)
                    return formatDate(date);
            }
        }
        else if (!docIdNode.isMissingNode()) {
            String date = docIdNode.path("date").asText(null);
            if (date != null)
                return formatDate(date);
        }
        return null;
    }

    private String formatDate(String rawDate) {
        if (rawDate == null || rawDate.length() < 8)
            return rawDate;
        // Convert YYYYMMDD to YYYY-MM-DD
        if (rawDate.length() == 8 && !rawDate.contains("-")) {
            return rawDate.substring(0, 4) + "-" + rawDate.substring(4, 6) + "-" + rawDate.substring(6, 8);
        }
        return rawDate;
    }

    private String extractDocId(JsonNode docIdNode) {
        if (docIdNode.isArray() && docIdNode.size() > 0) {
            return EPOClient.docdbNumber(docIdNode.get(0));
        }
        else if (!docIdNode.isMissingNode()) {
            return EPOClient.docdbNumber(docIdNode);
        }
        return null;
    }

    private List<String> extractClassifications(JsonNode classNodes) {
        List<String> classifications = new ArrayList<>();
        if (classNodes.isArray()) {
            for (JsonNode cls : classNodes) {
                String text = cls.path("text").asText(null);
                if (text == null)
                    text = cls.asText(null);
                if (text != null)
                    classifications.add(text.trim());
            }
        }
        else if (!classNodes.isMissingNode()) {
            String text = classNodes.path("text").asText(null);
            if (text == null)
                text = classNodes.asText(null);
            if (text != null)
                classifications.add(text.trim());
        }
        return classifications.isEmpty() ? null : classifications;
    }
}
