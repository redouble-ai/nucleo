/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.llm.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The grouping tier on a decision model, through the dispatcher against the suite's fake
 * decision model deciding by a scripted policy: the names the model pairs join, a group's
 * proper name is the one the model chooses, and the groups are the same type the LLM tier
 * produces, so the reconciliation runs on them unchanged.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
class DecideProductGroupsTest {

    @BeforeAll
    static void start() {
        JobDispatcher.getInstance().start();
    }

    /** A model that pairs names by their family (brand and descriptor dropped) and calls the shortest name of a group the proper one. */
    static Map<String, Answer> policy(DecisionRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Map.Entry<String, Question> question : request.questions().entrySet()) {
            if (question.getValue() instanceof Noul noul) {
                java.util.regex.Matcher pair = java.util.regex.Pattern.compile("Do '([^']+)' and '([^']+)' mean").matcher(noul.instructions());
                boolean same = pair.find() && family(pair.group(1)).equalsIgnoreCase(family(pair.group(2)));
                // exactly the threshold for a pair that is one product: at or above joins
                answers.put(question.getKey(), new NoulAnswer(same ? DecideProductGroupsTool.THRESHOLD : 0.1));
            }
            else {
                Choice choice = (Choice) question.getValue();
                String shortest = choice.options().keySet().stream().min(Comparator.comparingInt(String::length)).orElseThrow();
                Map<String, Double> probabilities = new LinkedHashMap<>();
                for (String option : choice.options().keySet()) {
                    probabilities.put(option, option.equals(shortest) ? 0.8 : 0.2 / Math.max(1, choice.options().size() - 1));
                }
                answers.put(question.getKey(), new ChoiceAnswer(shortest, probabilities, 0.8));
            }
        }
        return answers;
    }

    static String family(String name) {
        return name.replaceAll("^Halcyon ", "").replaceAll(" (gravel|trail|city)$", "").trim();
    }

    @Test
    void theNamesTheModelPairsJoinAndEachGroupTakesTheNameTheModelChooses() throws Exception {
        FakeDecisionClient.seen.clear();
        FakeDecisionClient.policy = DecideProductGroupsTest::policy;
        ProductNames names = new ProductNames();
        names.setNames(List.of("Comet 2 city", "Halcyon Meridian 3 trail", "Kestrel 1", "Kestrel 1 gravel", "Meridian 2", "Meridian 3"));
        DecideProductGroupsTool tool = new DecideProductGroupsTool(Job.workflow("test-user", "decide-groups"));
        tool.setInput(names);
        ProductGroups groups = JobDispatcher.getInstance().submit(tool).get();
        Map<String, List<String>> byCanonical = new TreeMap<>();
        for (ProductGroups.Group group : groups.getGroups()) {
            byCanonical.put(group.getCanonical(), group.getAliases());
        }
        assertEquals(Set.of("Comet 2 city", "Kestrel 1", "Meridian 2", "Meridian 3"), byCanonical.keySet(), byCanonical.toString());
        assertEquals(List.of("Halcyon Meridian 3 trail", "Meridian 3"), byCanonical.get("Meridian 3"), "joined by the verdict on their pair, in the order the names came");
        assertEquals(List.of("Kestrel 1", "Kestrel 1 gravel"), byCanonical.get("Kestrel 1"));
        assertEquals(List.of("Comet 2 city"), byCanonical.get("Comet 2 city"), "a name alone is its own group and its own proper name");
        assertEquals(2, FakeDecisionClient.seen.size(), "one decision over every pair, one over the groups of several");
        assertEquals(15, FakeDecisionClient.seen.get(0).questions().size(), "six names make fifteen pairs");
        assertEquals(0.5, DecideProductGroupsTool.THRESHOLD, 1e-9, "a verdict at one half joins");
        assertEquals(List.of("same_1_2", "same_1_3", "same_1_4"), new ArrayList<>(FakeDecisionClient.seen.get(0).questions().keySet()).subList(0, 3),
                "one verdict per pair, keyed by the names' places from one");
        assertEquals(List.of("proper_1", "proper_2"), new ArrayList<>(FakeDecisionClient.seen.get(1).questions().keySet()));
        assertEquals(2, FakeDecisionClient.seen.get(1).questions().size(), "two groups of several names");
        assertEquals(List.of("Halcyon Meridian 3 trail", "Meridian 3"), new ArrayList<>(((Choice) FakeDecisionClient.seen.get(1).questions().get("proper_1")).options().keySet()),
                "the group's own names are the options, in the order the names came");
    }

    @Test
    void oneNameIsOneGroupAndCostsOneQuestion() throws Exception {
        FakeDecisionClient.seen.clear();
        FakeDecisionClient.policy = DecideProductGroupsTest::policy;
        ProductNames names = new ProductNames();
        names.setNames(List.of("Comet 2"));
        DecideProductGroupsTool tool = new DecideProductGroupsTool(Job.workflow("test-user", "decide-groups-one"));
        tool.setInput(names);
        ProductGroups groups = JobDispatcher.getInstance().submit(tool).get();
        assertEquals(1, groups.getGroups().size());
        assertEquals("Comet 2", groups.getGroups().get(0).getCanonical());
        assertEquals(1, FakeDecisionClient.seen.size(), "no groups of several, so no second decision");
        assertEquals(List.of("alone"), new ArrayList<>(FakeDecisionClient.seen.get(0).questions().keySet()), "no pair to ask about: one question, whether the name is a product");
    }
}
