/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seam where a schema becomes model-facing. `ToolProvider.schemaJson` carries the
 * `x-nucleo-*` keywords for a consuming harness; the tool definition blocks a thinker
 * builds must not, because they render into the prompt and into a provider's native tools
 * parameter, and none of that vocabulary is addressed to a model.
 *
 * <p>Pinned as a pair: the same schema with the keywords on one side of the seam and
 * without them on the other. A strip that silently stopped running would otherwise cost
 * tokens on every turn of every conversation with nothing failing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class ToolDefinitionSchemaTest {

    public static class AnnotatedToolInput {
        @LLMRequired
        @LLMDescription("What to look up")
        private String query;

        @LLMSummarizable(value = "the document body", size = SummarySize.BRIEF)
        @LLMDescription("Text to work over")
        private String body;

        @LLMContextIgnore
        @LLMDescription("Bookkeeping")
        private String trace;

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public String getTrace() { return trace; }
        public void setTrace(String trace) { this.trace = trace; }
    }

    public static class ProbeOutput {
        @LLMDescription("Whatever was found")
        private String result;

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
    }

    @ToolName("annotated_probe")
    @ToolDescription(value = "A tool whose input carries every annotation.", readOnly = true)
    public static class AnnotatedTool extends AbstractTool<AnnotatedToolInput, ProbeOutput> {
        public AnnotatedTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public ProbeOutput execute(JobResources resources, JobContext<ProbeOutput> context) {
            return null;
        }
    }

    static final class ProbeThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        ProbeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(AnnotatedTool.class);
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }

    @Test
    void theProviderCarriesTheKeywords() {
        String schema = ClassToolProvider.of(AnnotatedTool.class).schemaJson();
        assertTrue(schema.contains(NucleoSchemaKeywords.SUMMARIZABLE), schema);
        assertTrue(schema.contains(NucleoSchemaKeywords.CONTEXT_IGNORE), schema);
    }

    @Test
    void theDefinitionBlocksDoNot() {
        ProbeThinker thinker = new ProbeThinker(Job.workflow("schema-test", "schema-test"));
        List<ToolDefinitionBlock> blocks = thinker.buildToolDefinitionBlocks();
        assertFalse(blocks.isEmpty());
        for (ToolDefinitionBlock block : blocks) {
            assertFalse(block.schemaJson().contains(NucleoSchemaKeywords.PREFIX),
                    "tool " + block.name() + " offers harness metadata to the model: " + block.schemaJson());
        }
    }

    @Test
    void theStripLeavesTheSchemaItself() {
        ProbeThinker thinker = new ProbeThinker(Job.workflow("schema-test", "schema-test"));
        ToolDefinitionBlock block = thinker.buildToolDefinitionBlocks().stream()
                .filter(b -> "annotated_probe".equals(b.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(block.schemaJson().contains("What to look up"), block.schemaJson());
        assertTrue(block.schemaJson().contains("\"required\""), "required-ness still reaches the model: " + block.schemaJson());
        assertTrue(block.schemaJson().contains("Text to work over"), "the summarizable field is still described");
    }
}
