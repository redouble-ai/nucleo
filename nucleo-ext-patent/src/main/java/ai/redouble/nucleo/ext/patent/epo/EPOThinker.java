/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * API Thinker for EPO Open Patent Services queries.
 * <p>
 * Orchestrates all EPO tools (search, biblio, claims, description, family,
 * legal status, number conversion) to handle patent queries.
 * <p>
 * This is an API Thinker - it wraps the EPO tools and is exposed to
 * domain agents as a tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@ToolName("epo")
@DisplayName(value = "EPO", action = "Querying EPO OPS")
@ToolDescription(value = "Query EPO Open Patent Services for patent data. Searches patents, fetches bibliographic data, claims, descriptions, family members, and legal status.", readOnly = true)
@ToolWeight(type = ToolType.THINKER, min = 5, max = 15)
public class EPOThinker extends SingleObjectiveThinker<EPOThinkerInput, EPOThinkerOutput> {
    public EPOThinker(Identifiable parent) {
        // EPOThinkerOutput: a list of patent records with bibliographic fields
        super(parent, new ThinkerDeclaration(Grade.MEDIUM, OutputSize.STANDARD));
        this.setAnswerHandler(new PojoResponseHandler<>(EPOThinkerOutput.class));
        this.setMaxIterations(10);
    }
    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        List<Class<? extends Tool>> tools = new ArrayList<>();
        tools.add(EPOSearchTool.class);
        tools.add(EPOBiblioTool.class);
        tools.add(EPOClaimsTool.class);
        tools.add(EPODescriptionTool.class);
        tools.add(EPOFullTextTool.class);
        tools.add(EPOFamilyTool.class);
        tools.add(EPOLegalStatusTool.class);
        tools.add(EPONumberConversionTool.class);
        tools.add(EPOImagesTool.class);
        tools.add(CurrentTimeTool.class);
        return tools;
    }
    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You are an EPO Open Patent Services expert. Your task is to query EPO OPS to find and analyze patent information.

            WORKFLOW:
            1. Use epo_search to find patent numbers matching the query (use structured fields or CQL as appropriate)
            2. Use epo_biblio to fetch bibliographic data for each matching patent
            3. Based on the query needs, optionally use:
               - epo_claims to get the full claims text
               - epo_description to get the full description/specification
               - epo_family to find related patents across jurisdictions (INPADOC family)
               - epo_legal_status to check current legal status and event history
               - epo_number_convert to convert between DOCDB and EPODOC number formats

            IMPORTANT:
            - Always search first via epo_search, then fetch details for matching patents
            - Respect the maxPatents cap provided in the input; return them as PatentArtifacts
            - Every patent number an EPO tool returns is dotted DOCDB (CC.NNNNNNN.K) - use it directly with any other tool
            - Include title, abstract, applicants, inventors, dates, and classifications
            - If the query asks about claims, legal status, or family - fetch those too
            - Set the source field to "epo" on all artifacts

            """;
    }
}
