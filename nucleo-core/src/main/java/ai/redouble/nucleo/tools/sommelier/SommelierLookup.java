/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * One lookup of {@link ModelSommelier}: the catalog's pages are cut into parts of about
 * {@link SommelierSettings#partTokens} tokens, one {@link SommelierCall} reads each part,
 * all at once, and when there was more than one part a last call reads the tools the parts
 * named, side by side, and its answer is the lookup's.
 *
 * <p>The last call exists because a part sees only its own tools: each part names its best
 * candidate whatever the other parts hold, so the parts together name more tools than fit.
 * A catalog that fits one part is answered by that part's call alone.
 *
 * <p>A call that fails fails the lookup. A name a model returns that the catalog does not
 * hold is dropped and logged.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@DisplayName(value = "Sommelier Lookup", action = "Reading the tool catalog")
@ToolName("sommelier_lookup")
@ToolDescription(value = "Reads a tool catalog, in parts when it is long, and names the tools that serve a stated need.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY, min = 1, max = 1)
public class SommelierLookup extends AbstractDoer<SommelierQuestion, SommelierAnswer> {
    private static final Logger log = LoggerFactory.getLogger(SommelierLookup.class);
    private ModelRole role;

    public SommelierLookup(Identifiable parent) {
        super(parent, "sommelier-lookup");
    }

    /** The role every call of this lookup does the work of; unset, the calls run at {@link SommelierCall#GRADE}. */
    public void setRole(ModelRole role) {
        this.role = role;
    }

    public ModelRole getRole() {
        return role;
    }

    @Override
    public SommelierAnswer execute(JobContext<SommelierAnswer> context) throws LLMReadableCheckedException {
        SommelierQuestion question = getInput();
        Map<String, String> pages = question.getTools();
        List<Map<String, String>> parts = cut(pages, Settings.get(SommelierSettings.class).partTokens);
        nextStep();
        List<JobHandle<SommelierAnswer>> reading = new ArrayList<>();
        for (Map<String, String> part : parts) {
            reading.add(submitInCurrentStep(call(question.getNeed(), part)));
        }
        Map<String, String> named = new LinkedHashMap<>();
        for (JobHandle<SommelierAnswer> handle : reading) {
            named.putAll(known(await(handle), pages));
        }
        if (parts.size() > 1 && named.size() > 1) {
            named = known(await(submitInStep(call(question.getNeed(), named))), named);
        }
        SommelierAnswer answer = new SommelierAnswer();
        answer.setFitting(new ArrayList<>(named.keySet()));
        return answer;
    }

    /** The job that puts one set of tool pages to a model. */
    protected Job<SommelierAnswer> call(String need, Map<String, String> tools) {
        SommelierQuestion question = new SommelierQuestion();
        question.setNeed(need);
        question.setTools(tools);
        SommelierCall call = new SommelierCall(this);
        call.setInput(question);
        if (role != null) {
            call.setRole(role);
        }
        return call;
    }

    private static SommelierAnswer await(JobHandle<SommelierAnswer> handle) throws LLMReadableCheckedException {
        try {
            return handle.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemException("sommelier", "interrupted while a model was reading the tool catalog", e);
        }
        catch (ExecutionException e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    /** The tools an answer names, with their pages, in the order of the pages it was asked about. */
    private static Map<String, String> known(SommelierAnswer answer, Map<String, String> pages) {
        Map<String, String> named = new LinkedHashMap<>();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            if (answer.getFitting().contains(page.getKey())) {
                named.put(page.getKey(), page.getValue());
            }
        }
        for (String name : answer.getFitting()) {
            if (!pages.containsKey(name)) {
                log.warn("Sommelier: a model named a tool the catalog does not hold, dropped: {}", name);
            }
        }
        return named;
    }

    /**
     * Cuts the pages into the fewest parts that keep each at about {@code partTokens}
     * tokens, of even size, in the pages' order. A token is counted as four characters. A
     * single page longer than a part is a part by itself.
     */
    static List<Map<String, String>> cut(Map<String, String> pages, int partTokens) {
        long total = 0;
        for (String page : pages.values()) {
            total += page.length() / 4;
        }
        long count = Math.max(1, (total + partTokens - 1) / partTokens);
        long perPart = (total + count - 1) / count;
        List<Map<String, String>> parts = new ArrayList<>();
        Map<String, String> part = new LinkedHashMap<>();
        long held = 0;
        for (Map.Entry<String, String> page : pages.entrySet()) {
            long size = page.getValue().length() / 4;
            if (!part.isEmpty() && held + size > perPart) {
                parts.add(part);
                part = new LinkedHashMap<>();
                held = 0;
            }
            part.put(page.getKey(), page.getValue());
            held += size;
        }
        if (!part.isEmpty()) {
            parts.add(part);
        }
        return parts;
    }
}
