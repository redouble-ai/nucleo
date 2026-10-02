/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.tools;

import ai.redouble.nucleo.ext.lit.models.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * Autonomous agent that searches biomedical literature, analyzes findings,
 * and produces a comprehensive synthesis with references to key papers.
 *
 * <p>This agent uses PubMed and bioRxiv search and fetch tools to:
 * <ul>
 *   <li>Search for relevant literature on a research topic (both published papers and preprints)</li>
 *   <li>Retrieve and analyze article details including abstracts</li>
 *   <li>Fetch full text from PubMed Central for open-access articles that carry a PMCID</li>
 *   <li>Identify key themes and findings across multiple papers</li>
 *   <li>Synthesize a comprehensive summary</li>
 *   <li>Provide references to the most relevant individual articles</li>
 * </ul>
 *
 * <p>The agent operates autonomously - the LLM decides which databases to search (PubMed for
 * published papers, bioRxiv/medRxiv for preprints), which articles to fetch for detailed analysis,
 * and how to synthesize findings.
 *
 * <p><b>As a Tool:</b> This agent can be registered in ToolRegistry and invoked
 * by other agents, enabling hierarchical research systems where meta-agents
 * coordinate literature reviews alongside other analysis tasks.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@MCP
@ToolName("literature_aggregator_agent")
@ToolDescription(value = "Autonomously search, analyze, and synthesize biomedical literature (published papers and preprints) on a research topic. Searches both PubMed and bioRxiv/medRxiv. Returns comprehensive summary with key article references and identified themes.", readOnly = true)
@ToolWeight(type = ToolType.THINKER, min = 5, max = 15)
public class LiteratureAggregator extends SingleObjectiveThinker<PaperAggregatorInput, LiteratureAggregationOutput> {

    /**
     * Creates a paper aggregator agent.
     * This is the REQUIRED constructor for all tools.
     *
     * <p>Input is provided via {@link Tool#setInput} before execution.
     *
     * @param parent parent identity for lineage tracking
     */
    public LiteratureAggregator(Identifiable parent) {
        // LiteratureAggregationOutput: a synthesis over a list of papers; observed max 15.7K tokens
        super(parent, new ThinkerDeclaration(Grade.XL, OutputSize.STANDARD));
        this.setAnswerHandler(new PojoResponseHandler<>(LiteratureAggregationOutput.class));
        this.setMaxIterations(20);
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        List<Class<? extends Tool>> tools = new ArrayList<>();
        tools.add(PubMedSearchTool.class);
        tools.add(PubMedFetchTool.class);
        tools.add(BioRxivSearchTool.class);
        tools.add(BioRxivFetchTool.class);
        tools.add(PMCFullTextTool.class);
        tools.add(CurrentTimeTool.class);
        return tools;
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return citationArtifactInstructions();
    }

    /**
     * The artifact-handling instructions for aggregation over citation artifacts: how to
     * read {@code «artifact:...»} references, and how to propagate citations upward through
     * {@code artifactRefs}. Shared by every aggregator whose output extends
     * {@code ArtifactResponse} and carries citations (this one, and the web aggregators in
     * the platform library).
     */
    public static String citationArtifactInstructions() {
        return """
            === CITATION ARTIFACT HANDLING ===

            This task involves aggregating citations from multiple sources. Citations are
            preserved as artifacts to maintain their integrity.

            When you receive CitationArtifact objects (PubMedArticle, BioRxivPreprint, etc.):
            1. They may appear as references like «artifact:link:cite~a1b2c3»
            2. Look up full citation data in the ARTIFACT REGISTRY
            3. Use the complete citation for your analysis

            When returning your LiteratureAggregationOutput:
            1. Include newly discovered citations in the appropriate fields
            2. Use artifactRefs to propagate important citations from child searches
            3. Use the full artifact reference as shown (e.g. «artifact:link:cite~a1b2c3»)
            4. Only propagate citations relevant to the parent's research question
            5. Your response extends ArtifactResponse with an artifactRefs field for this purpose

            This ensures citations maintain their bibliographic integrity throughout the
            multi-step literature search process.
            """;
    }
}
