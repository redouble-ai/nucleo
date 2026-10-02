/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;
import java.util.*;

/**
 * The grouping tier of the pricing demo on a decision model: the same names in, the same
 * groups out as {@link CanonicalizeProductsTool}. Two decisions. The first is one verdict per
 * pair of names, "do these two mean the same product", the shape a decision model is best
 * at; every pair the model says yes to joins their groups. The second, for each group of
 * more than one name, is a choice of the name that is the product's proper name, without a
 * brand in front, a descriptor behind or a size. The model writes no name: every group is
 * made of the names the documents used.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
@DisplayName(value = "Decide Product Groups", action = "Deciding which names are one product")
@ToolName("decide_product_groups")
@ToolDescription(value = "Groups the product names documents used by the product each one means: a verdict per pair of names, then the proper name per group.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 2)
public class DecideProductGroupsTool extends AbstractTool<ProductNames, ProductGroups> {
    /** The probability a verdict must reach to join two names. */
    public static final double THRESHOLD = 0.5;
    private ModelBinding binding;

    public DecideProductGroupsTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofMinutes(5));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        // the names once as the state, and a question per pair: the pairs are the bulk of what the wire carries
        String names = String.join("\n", input.getNames());
        int pairs = input.getNames().size() * Math.max(0, input.getNames().size() - 1) / 2;
        binding = req.requireDecision(names.length() / 4 + pairs * 24 + 1);
        return req;
    }

    @Override
    public ProductGroups execute(JobResources resources, JobContext<ProductGroups> context) throws LLMReadableCheckedException {
        List<String> names = input.getNames();
        DecisionClient client = resources.getDecisionClient(binding.getModel());
        try {
            context.publish("Deciding " + names.size() * Math.max(0, names.size() - 1) / 2 + " pairs of " + names.size() + " names", 10);
            List<List<String>> groups = groups(names, client.decide(pairs(names)));
            List<List<String>> several = groups.stream().filter(group -> group.size() > 1).toList();
            Map<String, String> canonical = new LinkedHashMap<>();
            if (!several.isEmpty()) {
                context.publish("Deciding the proper name of " + several.size() + " groups", 70);
                canonical = canonicals(several, client.decide(proper(several)));
            }
            return assemble(groups, canonical);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    /** The first decision: the names as the state, one verdict per pair. */
    static DecisionRequest pairs(List<String> names) {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("product_names", names);
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                questions.put(pairId(i, j), Noul.of("Do '" + names.get(i) + "' and '" + names.get(j) + "' mean the same product or service?"
                        + " Names that differ only in spelling, capitalization, a brand name in front, a descriptor, an article or a"
                        + " model-family shorthand are one product; a different model number or generation is a different product;"
                        + " a part or a service described in different words is one product."));
            }
        }
        if (questions.isEmpty()) {
            questions.put("alone", Noul.of("Is '" + names.get(0) + "' the name of a product or service?"));
        }
        return new DecisionRequest(state, questions);
    }

    static String pairId(int i, int j) {
        return "same_" + (i + 1) + "_" + (j + 1);
    }

    /** The groups the verdicts yield: every pair the model joined joins, in the order the names came. */
    static List<List<String>> groups(List<String> names, DecisionResponse decision) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (String name : names) {
            parent.put(name, name);
        }
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (decision.noul(pairId(i, j)).probability() >= THRESHOLD) {
                    union(parent, names.get(i), names.get(j));
                }
            }
        }
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (String name : names) {
            members.computeIfAbsent(find(parent, name), root -> new ArrayList<>()).add(name);
        }
        return new ArrayList<>(members.values());
    }

    /** The second decision: per group of several names, which is the product's proper name. */
    static DecisionRequest proper(List<List<String>> groups) {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("groups", groups);
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            LinkedHashMap<String, String> options = new LinkedHashMap<>();
            for (String name : groups.get(g)) {
                options.put(name, "one of the group's names");
            }
            questions.put(groupId(g), new Choice("These names mean one product. Which of them is the product's proper name:"
                    + " the model name as such, without a brand name in front, a descriptor behind, a size or a colour?", options));
        }
        return new DecisionRequest(state, questions);
    }

    static String groupId(int g) {
        return "proper_" + (g + 1);
    }

    /** Per group of several, the name the model chose; keyed by the group's first name. */
    static Map<String, String> canonicals(List<List<String>> groups, DecisionResponse decision) {
        Map<String, String> canonical = new LinkedHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            canonical.put(groups.get(g).get(0), decision.choice(groupId(g)).choice());
        }
        return canonical;
    }

    static ProductGroups assemble(List<List<String>> groups, Map<String, String> canonical) {
        ProductGroups result = new ProductGroups();
        List<ProductGroups.Group> list = new ArrayList<>();
        for (List<String> group : groups) {
            ProductGroups.Group one = new ProductGroups.Group();
            one.setCanonical(canonical.getOrDefault(group.get(0), group.get(0)));
            one.setAliases(new ArrayList<>(group));
            list.add(one);
        }
        result.setGroups(list);
        return result;
    }

    private static String find(Map<String, String> parent, String name) {
        String root = name;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootB, rootA);
        }
    }
}
