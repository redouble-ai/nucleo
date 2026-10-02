/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.deciding.*;

import java.util.*;
import java.util.regex.*;

/**
 * Splits a document into its statements: a paragraph's sentences (consecutive lines make
 * one paragraph), each bullet or numbered item. Headings are structure, not statements, and are
 * dropped; a fragment under three words is not a statement either. No model: the split is
 * what a person would mark with a highlighter, one stroke per claim, so a decision model can
 * pick claims one at a time.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@ToolName("split_statements")
@ToolDescription(value = "Splits a document into its statements: the sentences of every paragraph and every bullet, each with its place in the document", readOnly = true)
public class SplitStatementsTool extends DecisionTool<Document, ListArtifact<Statement>> {
    /** A sentence ends at a period, question or exclamation mark followed by a space and an upper-case letter, a digit or a quote. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'(\\[])");
    private static final Pattern ITEM = Pattern.compile("^\\s*([-*+•]|\\d+[.)])\\s+");
    /** A statement carries at least this many words. */
    static final int MIN_WORDS = 3;

    public SplitStatementsTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        requirements.setReadOnly(true);
        return requirements;
    }

    @Override
    public ListArtifact<Statement> execute(JobResources resources, JobContext<ListArtifact<Statement>> context) {
        List<Statement> statements = new ArrayList<>();
        for (String piece : split(input.getText())) {
            statements.add(new Statement(piece, input.getName(), statements.size() + 1));
        }
        ListArtifact<Statement> list = new ListArtifact<>();
        list.setIterands(statements);
        list.setIterandTypeAlias("statement");
        return list;
    }

    /** The statements of a text, in order: paragraphs and items gathered line by line, then each cut into sentences. */
    static List<String> split(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            boolean item = ITEM.matcher(line).find();
            if (line.isEmpty() || line.startsWith("#") || item) {
                flush(block, blocks);
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (item) {
                blocks.add(ITEM.matcher(line).replaceFirst(""));
                continue;
            }
            if (block.length() > 0) {
                block.append(' ');
            }
            block.append(line);
        }
        flush(block, blocks);
        List<String> statements = new ArrayList<>();
        for (String paragraph : blocks) {
            for (String sentence : SENTENCE_END.split(paragraph)) {
                String piece = sentence.strip();
                if (piece.split("\\s+").length >= MIN_WORDS) {
                    statements.add(piece);
                }
            }
        }
        return statements;
    }

    private static void flush(StringBuilder block, List<String> blocks) {
        if (block.length() > 0) {
            blocks.add(block.toString());
            block.setLength(0);
        }
    }
}
