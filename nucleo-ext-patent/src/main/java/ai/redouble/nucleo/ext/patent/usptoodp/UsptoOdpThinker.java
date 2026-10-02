/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * API Thinker for USPTO Open Data Portal queries.
 *
 * <p>Orchestrates all ODP tools (search, fetch, full-text) to handle
 * US patent queries. Exposed to domain agents as a tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@ToolName("uspto_odp")
@DisplayName(value = "USPTO ODP", action = "Querying USPTO ODP")
@ToolDescription(value = "Query USPTO Open Data Portal for US patent data. Searches patents, fetches bibliographic data with abstracts, and retrieves full patent text (claims, description) on demand.", readOnly = true)
@ToolWeight(type = ToolType.THINKER, min = 5, max = 15)
public class UsptoOdpThinker extends SingleObjectiveThinker<UsptoOdpThinkerInput, UsptoOdpThinkerOutput> {
    public UsptoOdpThinker(Identifiable parent) {
        // UsptoOdpThinkerOutput: a list of application records with their fields
        super(parent, new ThinkerDeclaration(Grade.MEDIUM, OutputSize.STANDARD));
        this.setAnswerHandler(new PojoResponseHandler<>(UsptoOdpThinkerOutput.class));
        this.setMaxIterations(10);
    }
    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        List<Class<? extends Tool>> tools = new ArrayList<>();
        tools.add(UsptoOdpSearchTool.class);
        tools.add(UsptoOdpFetchTool.class);
        tools.add(UsptoOdpFullTextTool.class);
        tools.add(UsptoOdpLegalEventsTool.class);
        tools.add(UsptoOdpContinuityTool.class);
        tools.add(UsptoOdpForeignPriorityTool.class);
        tools.add(CurrentTimeTool.class);
        return tools;
    }
    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You are a US patent research expert using the USPTO Open Data Portal.

            TOOLS:
            - uspto_odp_search: Search US patents by title, assignee, inventor, CPC, date range
            - uspto_odp_fetch: Fetch bibliographic details + abstract for a specific US patent number
            - uspto_odp_fulltext: Fetch full claims and description text (expensive - use only when explicitly needed)
            - uspto_odp_legal_events: Fetch legal status and prosecution event history
            - uspto_odp_continuity: Fetch parent/child application chain (US family)
            - uspto_odp_foreign_priority: Fetch foreign priority claims and earliest priority date

            WORKFLOW:
            1. Use uspto_odp_search to find patent numbers matching the query
            2. Use uspto_odp_fetch to get detailed bibliographic data for each matching patent
            3. Only use uspto_odp_fulltext if the query explicitly asks about claims or description text

            IMPORTANT:
            - Respect the maxPatents cap provided in the input; return them as PatentArtifacts
            - Patent numbers are plain numbers (e.g., "10757852"), no prefix needed
            - Set source to "uspto-odp" on all artifacts (tools do this automatically)
            - This tool covers US patents only. For international patents, use the EPO thinker.

            """;
    }
}
