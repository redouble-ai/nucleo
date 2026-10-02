/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.deciding.*;
import ai.redouble.nucleo.tools.registry.*;

import java.util.*;

/**
 * The demo's decision agent: a {@link DecisionThinker} over a folder of documents whose
 * objective is to find the statements that decide a change of a price. Its palette is three
 * tools that call no model: list the folder, read a file, split a document into statements.
 * Everything the run decides, the decision model decides: which of the files to open, which
 * documents to split, when to stop, and which statements answer the objective. The run costs
 * no model call but the decisions, so on a decision model served from this machine it costs
 * nothing at all.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@ToolName("price_change_finder")
@ToolDescription(value = "Finds the statements in a folder's documents that decide a change of a price, deciding with a decision model which files to open", readOnly = true)
@DisplayName(value = "Price Change Finder", action = "Finding the statements that decide a price change")
public class PriceChangeFinder extends DecisionThinker<Folder, Statement> {
    public static final String OBJECTIVE = "Find every statement in the documents of this folder that decides a change of a price"
            + " and says from when the new price applies. Open the documents likely to record such decisions, split them into"
            + " statements, and finish with the statements that decide a price change.";
    /** The key tool: the one that produces the statements the answer is a list of. */
    public static final Class<SplitStatementsTool> KEY_TOOL = SplitStatementsTool.class;
    /** The rest of the palette, in the order the model is offered them; the key tool follows. */
    public static final List<Class<? extends DecisionTool<?, ?>>> PALETTE = List.of(ListFolderTool.class, ReadDocumentTool.class);
    /** The whole palette as the model is offered it, for a page. */
    public static final List<Class<? extends DecisionTool<?, ?>>> TOOLS = List.of(ListFolderTool.class, ReadDocumentTool.class, SplitStatementsTool.class);

    static {
        Digests.register();
    }

    public PriceChangeFinder(Identifiable parent) {
        super(parent, Statement.class, KEY_TOOL, PALETTE, OBJECTIVE);
        // A person waits behind every run, so the demo's one-retry policy applies here as
        // everywhere; the thinker hands the budget to every decision it asks
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    /** The objective and the palette as a page shows them: each tool's name, what it does, what it takes and what it produces. */
    public static DecideCapabilities capabilities() {
        List<DecideCapabilities.ToolView> tools = new ArrayList<>();
        for (Class<? extends DecisionTool<?, ?>> tool : TOOLS) {
            ClassToolProvider provider = ClassToolProvider.of(tool);
            tools.add(new DecideCapabilities.ToolView(provider.name(), provider.description(),
                    TypeAliasRegistry.getAlias(provider.inputType()), TypeAliasRegistry.getAlias(provider.outputType())));
        }
        return new DecideCapabilities(OBJECTIVE, TypeAliasRegistry.getAlias(Folder.class), TypeAliasRegistry.getAlias(Statement.class), tools);
    }
}
