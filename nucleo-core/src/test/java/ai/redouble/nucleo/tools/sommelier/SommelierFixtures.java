/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;

import java.util.*;
import java.util.function.*;

/**
 * What the sommelier tests share: a tool provider that exists only as a catalog entry, and
 * a job that answers a sommelier question from a script where a model would.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
final class SommelierFixtures {

    private SommelierFixtures() {
    }

    /** A catalog of {@code count} tools named {@code tool_1} to {@code tool_<count>}. */
    static Set<ToolProvider> catalog(int count) {
        Set<ToolProvider> catalog = new LinkedHashSet<>();
        for (int i = 1; i <= count; i++) {
            catalog.add(new CatalogEntry("tool_" + i, "Describes entry number " + i));
        }
        return catalog;
    }

    /** A tool that is only ever listed, looked up and admitted, never created. */
    record CatalogEntry(String name, String description) implements ToolProvider {
        @Override
        public String schemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"query_of_" + name + "\":{\"type\":\"string\",\"description\":\"What to look for\"}}}";
        }

        @Override
        public ToolWeight weight() {
            return DefaultToolWeights.API_CALL_DEFAULT;
        }

        @Override
        public String displayName() {
            return name;
        }

        @Override
        public String actionVerb() {
            return name;
        }

        @Override
        public Class<?> inputType() {
            return String.class;
        }

        @Override
        public Class<? extends Tool> toolClass() {
            return CurrentTimeTool.class;
        }

        @Override
        public Object parseInput(JsonNode raw) {
            return raw;
        }

        @Override
        public Tool<?, ?> create(Identifiable parent) {
            throw new UnsupportedOperationException("a catalog entry of the sommelier tests is never created");
        }
    }

    /** Stands where a model call would: answers the question it was given through the script. */
    @ToolName("scripted_sommelier_call")
    @LLMDescription("Answers a sommelier question from a script.")
    @ReadOnly
    static class ScriptedCall extends AbstractTool<SommelierQuestion, SommelierAnswer> {
        private final Function<SommelierQuestion, List<String>> script;

        ScriptedCall(Identifiable parent, SommelierQuestion question, Function<SommelierQuestion, List<String>> script) {
            super(parent);
            this.script = script;
            setInput(question);
        }

        @Override
        public SommelierAnswer execute(JobResources resources, JobContext<SommelierAnswer> context) throws LLMReadableCheckedException {
            SommelierAnswer answer = new SommelierAnswer();
            answer.setFitting(script.apply(getInput()));
            return answer;
        }
    }
}
