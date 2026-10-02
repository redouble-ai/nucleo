/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.deciding.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * A decision thinker on a live System One model: the run is a real agentic loop where the
 * model never writes a word. The palette splits the minutes into statements and drafts a
 * notice from a statement; the model decides which statement deserves a notice, when to
 * finish, and which notices answer the objective. Off unless {@code systemone.live.url}
 * names a server:
 *
 * <pre>mvn -pl nucleo-provider-systemone -am test -Dtest=LiveDecisionThinkerTest -Dsystemone.live.url=http://127.0.0.1:8009</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class LiveDecisionThinkerTest {
    static final String MINUTES = "Meridian 3 and Kestrel 1 gravel retail and dealer prices go up by 20 percent from 1 June 2026,"
            + " on the prices in force in May. The Comet 2 is not affected: it carries no aluminium from Tai Han."
            + " Tune-up and bearing prices in the workshop are unchanged.";

    @TypeAlias("minutes")
    public static class Minutes extends AbstractArtifact {
        @LLMDescription("The minutes' text")
        private String text;

        public Minutes() {}

        public Minutes(String text) {this.text = text;}

        public String getText() {return text;}

        public void setText(String text) {this.text = text;}
    }

    @TypeAlias("statement")
    public static class Statement extends AbstractArtifact {
        @LLMDescription("One statement of the minutes")
        private String text;

        public Statement() {}

        public Statement(String text) {this.text = text;}

        public String getText() {return text;}

        public void setText(String text) {this.text = text;}
    }

    @TypeAlias("notice")
    public static class Notice extends AbstractArtifact {
        @LLMDescription("The customer notice")
        private String text;

        public Notice() {}

        public Notice(String text) {this.text = text;}

        public String getText() {return text;}

        public void setText(String text) {this.text = text;}
    }

    @ToolName("split_minutes")
    @ToolDescription(value = "Splits the minutes into their statements, one per sentence", readOnly = true)
    public static class SplitMinutes extends DecisionTool<Minutes, ListArtifact<Statement>> {
        public SplitMinutes(Identifiable parent) {
            super(parent);
        }

        @Override
        public ListArtifact<Statement> execute(JobResources resources, JobContext<ListArtifact<Statement>> context) {
            List<Statement> statements = new ArrayList<>();
            for (String sentence : input.getText().split("(?<=\\.)\\s+")) {
                statements.add(new Statement(sentence.trim()));
            }
            ListArtifact<Statement> list = new ListArtifact<>();
            list.setIterands(statements);
            list.setIterandTypeAlias("statement");
            return list;
        }
    }

    /** The key tool: the notices drafted from one statement, a list of one. */
    @ToolName("draft_notice")
    @ToolDescription(value = "Drafts a customer price notice from one statement of the minutes", readOnly = true)
    public static class DraftNotice extends DecisionTool<Statement, ListArtifact<Notice>> {
        public DraftNotice(Identifiable parent) {
            super(parent);
        }

        @Override
        public ListArtifact<Notice> execute(JobResources resources, JobContext<ListArtifact<Notice>> context) {
            ListArtifact<Notice> notices = new ListArtifact<>();
            notices.setIterands(List.of(new Notice("Price notice to customers: " + input.getText())));
            notices.setIterandTypeAlias("notice");
            return notices;
        }
    }

    static class NoticeWriter extends DecisionThinker<Minutes, Notice> {
        NoticeWriter(Identifiable parent) {
            super(parent, Notice.class, DraftNotice.class, List.of(SplitMinutes.class),
                    "Send customers a price notice for every product whose price changes according to the minutes,"
                            + " and no notice for anything whose price does not change");
        }
    }

    @Test
    void theModelSplitsTheMinutesDraftsTheRightNoticeAndSelectsIt() throws Exception {
        String url = System.getProperty("systemone.live.url");
        assumeTrue(url != null && !url.isBlank(), "no live server named: -Dsystemone.live.url");
        SystemOneTestSecrets.host = url;
        JobDispatcher.getInstance().start();
        NoticeWriter writer = new NoticeWriter(Job.workflow("live", "live-thinker"));
        writer.setInput(new Minutes(MINUTES));
        long started = System.nanoTime();
        ListArtifact<Notice> answer = JobDispatcher.getInstance().submit(writer).get(5, TimeUnit.MINUTES);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        List<DecisionTurn> turns = writer.turns();
        StringBuilder record = new StringBuilder("live decision thinker in " + elapsedMs + " ms, " + turns.size() + " turns:\n");
        for (DecisionTurn turn : turns) {
            record.append("  ").append(turn.turn()).append(": ").append(turn.tool());
            if (turn.next() != null) {
                record.append(" ").append(turn.next().probabilities());
            }
            if (turn.artifact() != null) {
                record.append(" on ").append(turn.artifact()).append(" ").append(turn.argument().probabilities());
            }
            if (turn.result() != null) {
                record.append(" -> ").append(turn.result());
            }
            if (turn.failure() != null) {
                record.append(" failed: ").append(turn.failure());
            }
            if (!turn.selection().isEmpty()) {
                record.append(" selection ").append(turn.selection());
            }
            record.append('\n');
        }
        record.append("  answer: ");
        for (Notice notice : answer.getIterands()) {
            record.append(notice.getText()).append(" | ");
        }
        System.out.println(record);
        assertEquals("split_minutes", turns.get(0).tool(), "the only legal first move");
        assertTrue(turns.get(turns.size() - 1).finished(), "the run ended on the model's word");
        assertFalse(answer.getIterands().isEmpty(), "a notice was selected: " + record);
        for (Notice notice : answer.getIterands()) {
            assertTrue(notice.getText().contains("Meridian 3 and Kestrel 1"), "a selected notice is about the products whose price changes: " + notice.getText());
        }
    }
}
