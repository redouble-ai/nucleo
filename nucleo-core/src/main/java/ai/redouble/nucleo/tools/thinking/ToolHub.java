/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Central registry of {@link ToolProvider} availability and admission rules.
 *
 * <p>ToolHub is a system-wide singleton that manages:
 * <ul>
 *   <li><b>System-wide tools</b> - available to all thinkers (artifact tools, etc.).</li>
 *   <li><b>Compatible providers per thinker class</b> - resolved from {@link ToolSelector}
 *       declarations, cached per thinker class on first instance.</li>
 *   <li><b>Package scanning</b> - finds {@link Tool} subclasses with {@code @ToolName} in
 *       a package, wraps them as {@link ClassToolProvider}, cached forever.</li>
 *   <li><b>Admission rules</b> - {@code (thinkerClass, providerPredicate, guardrailClass)}
 *       triples for lazy admission. Predicates use {@link ToolProvider#toolClass()} so
 *       admission lookup never instantiates a tool.</li>
 *   <li><b>Compatible skills per thinker class</b> and <b>skill admission rules</b> - the
 *       same two mechanisms over {@link Skill}s: resolved from {@link SkillSelector}
 *       declarations, cached per thinker class, admitted through {@code request_skill}
 *       under {@code (thinkerClass, skillPredicate, guardrailClass)} rules. One hub, two
 *       catalogs.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
public class ToolHub {
    private static final Logger log = LoggerFactory.getLogger(ToolHub.class);

    private static final ToolHub INSTANCE = new ToolHub();

    private final List<Class<? extends Tool>> systemWideTools = new CopyOnWriteArrayList<>();
    private final Map<String, List<ToolProvider>> packageCache = new ConcurrentHashMap<>();
    private final Map<Class<? extends Thinker>, Set<ToolProvider>> thinkerToolCache = new ConcurrentHashMap<>();
    private final List<AdmissionEntry<ToolProvider>> admissionRules = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends Thinker>, Set<Skill>> thinkerSkillCache = new ConcurrentHashMap<>();
    private final List<AdmissionEntry<Skill>> skillAdmissionRules = new CopyOnWriteArrayList<>();

    /**
     * Admission rule entry over a catalog member (a {@link ToolProvider} or a {@link Skill}).
     * {@code thinkerClass == null} matches all thinkers; {@code predicate == null} matches
     * every member. The factory receives the requesting thinker as the guardrail's parent -
     * no reflective construction.
     */
    record AdmissionEntry<T>(
            Class<? extends Thinker> thinkerClass,
            Predicate<T> predicate,
            Function<Identifiable, AdmissionGuardrail> guardrailFactory) {
    }

    private ToolHub() {
    }

    public static ToolHub getInstance() {
        return INSTANCE;
    }

    // --- System-wide tools ---

    /**
     * Register a tool available to all thinkers.
     */
    public void registerSystemWideTool(Class<? extends Tool> toolClass) {
        systemWideTools.add(toolClass);
    }

    /**
     * Get all system-wide tools.
     */
    public List<Class<? extends Tool>> getSystemWideTools() {
        return Collections.unmodifiableList(systemWideTools);
    }

    /**
     * Check whether a tool name resolves to a known provider anywhere ToolHub has seen
     * one: system-wide tools, cached package scans, or per-thinker compatibility sets.
     *
     * <p>Used by {@code SchemaConformanceGuardrail} to warn about Skills that suggest
     * tools unknown to the running process.
     */
    public boolean isKnownTool(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        for (Class<? extends Tool> cls : systemWideTools) {
            ToolName ann = cls.getAnnotation(ToolName.class);
            if (ann != null && toolName.equals(ann.value())) {
                return true;
            }
        }
        for (List<ToolProvider> providers : packageCache.values()) {
            for (ToolProvider provider : providers) {
                if (toolName.equals(provider.name())) {
                    return true;
                }
            }
        }
        for (Set<ToolProvider> providers : thinkerToolCache.values()) {
            for (ToolProvider provider : providers) {
                if (toolName.equals(provider.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- Package scanning ---

    /**
     * Find every {@link Tool} subclass annotated with {@link ToolName} in a package and
     * return them as {@link ClassToolProvider}s. Results cached forever.
     */
    @SuppressWarnings("rawtypes")
    public List<ToolProvider> findToolProviders(String packageName) {
        return packageCache.computeIfAbsent(packageName, pkg -> {
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage(pkg))
                    .setScanners(Scanners.SubTypes));
            Set<Class<? extends Tool>> allTools = reflections.getSubTypesOf(Tool.class);
            List<ToolProvider> result = allTools.stream()
                    .filter(c -> c.getPackage().getName().equals(pkg))
                    .filter(c -> c.getAnnotation(ToolName.class) != null)
                    .map(c -> (ToolProvider) ClassToolProvider.of(c))
                    .toList();
            log.info("ToolHub: scanned package {} -> {} tools", pkg, result.size());
            return result;
        });
    }

    // --- Compatible providers per thinker class ---

    /**
     * Resolve the full set of compatible providers for a thinker. Called once per
     * thinker class, result cached.
     *
     * @param thinker the thinker instance (class used for cache key)
     * @return set of compatible providers, empty if thinker declares none
     */
    public Set<ToolProvider> resolveCompatibleTools(Thinker<?, ?> thinker) {
        return thinkerToolCache.computeIfAbsent(thinker.getClass(), cls -> {
            ToolSelector selector = ((AbstractThinker<?, ?>) thinker).declareCompatibleTools();
            if (selector == null) {
                return Set.of();
            }
            Set<ToolProvider> resolved = selector.getProviders();
            log.info("ToolHub: resolved {} compatible providers for {}", resolved.size(), cls.getSimpleName());
            return resolved;
        });
    }

    // --- Admission ---

    /**
     * Register an admission rule keyed by tool class. Tool class match uses
     * {@link ToolProvider#toolClass()} so no tool instance is constructed during
     * lookup.
     *
     * @param thinkerClass thinker class this rule applies to, or null for all thinkers
     * @param toolClass tool class this rule applies to, or null for all tools
     * @param guardrailFactory produces the admission guardrail to run (parent = the requesting thinker)
     */
    public void registerAdmission(
            Class<? extends Thinker> thinkerClass,
            Class<? extends Tool> toolClass,
            Function<Identifiable, AdmissionGuardrail> guardrailFactory) {
        Predicate<ToolProvider> predicate = toolClass == null
                ? null
                : provider -> toolClass.isAssignableFrom(provider.toolClass());
        admissionRules.add(new AdmissionEntry<>(thinkerClass, predicate, guardrailFactory));
    }

    /**
     * Register an admission rule keyed by an exact provider name or a glob pattern
     * ({@code *} wildcard at any position). Useful for MCP namespaces, e.g.
     * {@code "github.*"} matches every tool from the GitHub MCP connector.
     */
    public void registerAdmission(
            Class<? extends Thinker> thinkerClass,
            String toolNamePattern,
            Function<Identifiable, AdmissionGuardrail> guardrailFactory) {
        Predicate<ToolProvider> predicate = toolNamePattern == null
                ? null
                : provider -> globMatch(toolNamePattern, provider.name());
        admissionRules.add(new AdmissionEntry<>(thinkerClass, predicate, guardrailFactory));
    }

    /**
     * Register an admission rule with a custom predicate over the provider.
     */
    public void registerAdmission(
            Class<? extends Thinker> thinkerClass,
            Predicate<ToolProvider> providerPredicate,
            Function<Identifiable, AdmissionGuardrail> guardrailFactory) {
        admissionRules.add(new AdmissionEntry<>(thinkerClass, providerPredicate, guardrailFactory));
    }

    /**
     * Find every admission guardrail that applies to a given thinker + provider pair.
     *
     * @param thinker the thinker requesting the tool
     * @param provider the provider being requested
     * @return list of applicable guardrail classes, empty if no restrictions
     */
    public List<Function<Identifiable, AdmissionGuardrail>> findGuardrails(
            Thinker<?, ?> thinker, ToolProvider provider) {
        return applicable(admissionRules, thinker, provider);
    }

    private static <T> List<Function<Identifiable, AdmissionGuardrail>> applicable(
            List<AdmissionEntry<T>> rules, Thinker<?, ?> thinker, T member) {
        List<Function<Identifiable, AdmissionGuardrail>> found = new ArrayList<>();
        for (AdmissionEntry<T> entry : rules) {
            boolean thinkerMatch = entry.thinkerClass() == null
                    || entry.thinkerClass().isAssignableFrom(thinker.getClass());
            boolean memberMatch = entry.predicate() == null || entry.predicate().test(member);
            if (thinkerMatch && memberMatch) {
                found.add(entry.guardrailFactory());
            }
        }
        return found;
    }

    /**
     * Runs admission guardrails for one catalog member and returns the reason it is refused,
     * or null when every guardrail passed. A {@link GuardrailException} is the guardrail's own
     * verdict and travels to the model as a denial; anything else a guardrail throws is a fault
     * of ours, logged with its trace and reported to the model as an internal error it cannot
     * correct. The first refusal ends the run.
     */
    private static String runAdmission(List<AdmissionGuardrail> guardrails, AdmissionContext admissionContext,
                                       String subject, Thinker<?, ?> thinker, JobDispatcher dispatcher) {
        for (AdmissionGuardrail guardrail : guardrails) {
            try {
                guardrail.init(admissionContext);
                JobHandle<Void> handle = dispatcher.submit(guardrail);
                handle.get();
            }
            catch (Exception e) {
                Throwable cause = e;
                if (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null) {
                    cause = e.getCause();
                }
                if (cause instanceof GuardrailException ge) {
                    log.info("ToolHub: admission guardrail {} denied {}: {}", guardrail.getClass().getSimpleName(), subject, ge.getMessage());
                    return "Access denied: " + ge.getMessage();
                }
                log.error("ToolHub: admission guardrail {} crashed for {} ({})",
                        guardrail.getClass().getSimpleName(),
                        subject,
                        thinker.getClass().getSimpleName(),
                        cause);
                return "Admission check failed due to internal error. Try a different tool or approach.";
            }
        }
        return null;
    }

    /**
     * Request tools by name with lazy admission. The compatible set is first routed
     * through {@link AbstractThinker#reconcileCatalog(Set)} so subclass code has the
     * final say on what may be admitted. For each requested name: find in the
     * reconciled set, run admission guardrails, register admitted providers in the
     * target registry. Names absent from the reconciled set are rejected with a
     * "not found in compatible tools catalog" reason.
     *
     * @param thinker the thinker requesting tools
     * @param toolNames names of tools to activate
     * @param targetRegistry the thinker's tool registry to register admitted providers in
     * @return result with admitted and rejected lists
     */
    @SuppressWarnings("RedundantThrows") // the admission contract: a refusal may travel as an LLM-readable exception
    public RequestToolsResult requestTools(
            Thinker<?, ?> thinker,
            List<String> toolNames,
            ToolRegistry targetRegistry) throws LLMReadableCheckedException {
        Set<ToolProvider> compatible = ((AbstractThinker<?, ?>) thinker)
                .reconcileCatalog(resolveCompatibleTools(thinker));

        Map<String, ToolProvider> nameToProvider = new HashMap<>();
        for (ToolProvider provider : compatible) {
            nameToProvider.put(provider.name(), provider);
        }

        List<String> admitted = new ArrayList<>();
        List<RequestToolsResult.RejectedTool> rejected = new ArrayList<>();
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        for (String toolName : toolNames) {
            if (targetRegistry.hasTool(toolName)) {
                admitted.add(toolName);
                continue;
            }

            ToolProvider provider = nameToProvider.get(toolName);
            if (provider == null) {
                rejected.add(new RequestToolsResult.RejectedTool(
                        toolName, "Tool not found in compatible tools catalog"));
                continue;
            }

            // Hub-registered rules plus the tool's own declared admission guardrails.
            // Palette-time consultation is the token-saving half of admission; the same
            // guards are enforced again at dispatch, which is the guarantee. Asking the
            // tool requires an instance: it is a throwaway whose declared guard jobs run
            // under the thinker's lineage - intentional, not a leak to "fix".
            List<AdmissionGuardrail> guardrails = new ArrayList<>();
            String rejectionReason = null;
            AdmissionContext admissionContext =
                    new AdmissionContext(thinker.getUserId(), thinker.getClass().getName(), provider.toolClass());
            for (Function<Identifiable, AdmissionGuardrail> factory : findGuardrails(thinker, provider)) {
                guardrails.add(factory.apply(thinker));
            }
            // Class-backed providers only: their create is a cheap constructor call. A
            // dynamic provider's create acquires real resources (an MCP client), and its
            // adapter has no declarations to read anyway.
            if (provider instanceof ClassToolProvider) {
                try {
                    Tool<?, ?> probe = provider.create(thinker);
                    guardrails.addAll(probe.declareAdmissionGuardrails());
                }
                catch (Exception e) {
                    rejectionReason = "Admission check failed due to internal error. Try a different tool or approach.";
                    log.error("ToolHub: could not consult declared admission guardrails of {}", toolName, e);
                }
            }
            if (rejectionReason == null) {
                rejectionReason = runAdmission(guardrails, admissionContext, toolName, thinker, dispatcher);
            }
            if (rejectionReason == null) {
                targetRegistry.register(provider);
                admitted.add(toolName);
                log.info("ToolHub: admitted tool {} for {}{}",
                        toolName,
                        thinker.getClass().getSimpleName(),
                        (guardrails.isEmpty() ? " (no guardrails)" : " (" + guardrails.size() + " guardrail(s) passed)"));
            }
            else {
                rejected.add(new RequestToolsResult.RejectedTool(toolName, rejectionReason));
            }
        }
        RequestToolsResult result = new RequestToolsResult();
        result.setAdmitted(admitted);
        result.setRejected(rejected);
        return result;
    }

    // --- Skills: the same catalog and admission over Skill ---

    /**
     * Resolve the skills a thinker may pull in at run time, from its
     * {@link AbstractThinker#declareCompatibleSkills()} selector. Called once per thinker
     * class, result cached; {@link #registerCompatibleSkills} widens it later.
     *
     * @return the compatible skills, empty if the thinker declares none
     */
    public Set<Skill> resolveCompatibleSkills(Thinker<?, ?> thinker) {
        return thinkerSkillCache.computeIfAbsent(thinker.getClass(), cls -> {
            SkillSelector selector = ((AbstractThinker<?, ?>) thinker).declareCompatibleSkills();
            if (selector == null) {
                return Set.of();
            }
            Set<Skill> resolved = ConcurrentHashMap.newKeySet();
            resolved.addAll(selector.getSkills());
            log.info("ToolHub: resolved {} compatible skills for {}", resolved.size(), cls.getSimpleName());
            return resolved;
        });
    }

    /**
     * Register a skill admission rule keyed by an exact skill name or a glob pattern
     * ({@code *} wildcard at any position), e.g. {@code "demo.*"} for every skill of a
     * bundle whose names share a prefix.
     *
     * @param thinkerClass thinker class this rule applies to, or null for all thinkers
     * @param skillNamePattern the skill name or glob, or null for every skill
     * @param guardrailFactory produces the admission guardrail to run (parent = the requesting thinker)
     */
    public void registerSkillAdmission(
            Class<? extends Thinker> thinkerClass,
            String skillNamePattern,
            Function<Identifiable, AdmissionGuardrail> guardrailFactory) {
        Predicate<Skill> predicate = skillNamePattern == null
                ? null
                : skill -> globMatch(skillNamePattern, skill.name());
        skillAdmissionRules.add(new AdmissionEntry<>(thinkerClass, predicate, guardrailFactory));
    }

    /**
     * Register a skill admission rule with a custom predicate over the skill (its metadata,
     * its bundle, its suggested tools).
     */
    public void registerSkillAdmission(
            Class<? extends Thinker> thinkerClass,
            Predicate<Skill> skillPredicate,
            Function<Identifiable, AdmissionGuardrail> guardrailFactory) {
        skillAdmissionRules.add(new AdmissionEntry<>(thinkerClass, skillPredicate, guardrailFactory));
    }

    /**
     * Find every admission guardrail that applies to a given thinker + skill pair.
     */
    public List<Function<Identifiable, AdmissionGuardrail>> findSkillGuardrails(Thinker<?, ?> thinker, Skill skill) {
        return applicable(skillAdmissionRules, thinker, skill);
    }

    /**
     * Request skills by name with lazy admission, the counterpart of {@link #requestTools}.
     * The compatible set goes through {@link AbstractThinker#reconcileSkillCatalog(Set)} so
     * the thinker has the final say; each requested name is found in the reconciled set, run
     * through the skill admission guardrails and, when they pass, attached to the conversation,
     * whose preamble carries it from the next call on. A skill the conversation already holds
     * is reported admitted without a second attachment. Names absent from the reconciled set
     * are rejected as not in the catalog.
     *
     * <p>The admission context names the principal and the calling thinker; its tool class is
     * null, because a skill is content and has no class to name.
     *
     * @param thinker the thinker requesting skills
     * @param skillNames names of skills to admit
     * @param conversation the thinker's conversation of the current turn
     * @return result with admitted and rejected lists
     */
    @SuppressWarnings("RedundantThrows") // the admission contract: a refusal may travel as an LLM-readable exception
    public RequestSkillResult requestSkills(
            Thinker<?, ?> thinker,
            List<String> skillNames,
            ConversationContext conversation) throws LLMReadableCheckedException {
        Set<Skill> compatible = ((AbstractThinker<?, ?>) thinker)
                .reconcileSkillCatalog(resolveCompatibleSkills(thinker));
        Map<String, Skill> nameToSkill = new HashMap<>();
        for (Skill skill : compatible) {
            nameToSkill.put(skill.name(), skill);
        }
        Set<String> loaded = new java.util.HashSet<>();
        for (Skill skill : conversation.getLoadedSkills()) {
            loaded.add(skill.name());
        }
        List<String> admitted = new ArrayList<>();
        List<RequestSkillResult.RejectedSkill> rejected = new ArrayList<>();
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        for (String skillName : skillNames) {
            if (loaded.contains(skillName)) {
                admitted.add(skillName);
                continue;
            }
            Skill skill = nameToSkill.get(skillName);
            if (skill == null) {
                rejected.add(new RequestSkillResult.RejectedSkill(skillName, "Skill not found in compatible skills catalog"));
                continue;
            }
            List<AdmissionGuardrail> guardrails = new ArrayList<>();
            for (Function<Identifiable, AdmissionGuardrail> factory : findSkillGuardrails(thinker, skill)) {
                guardrails.add(factory.apply(thinker));
            }
            AdmissionContext admissionContext = new AdmissionContext(thinker.getUserId(), thinker.getClass().getName(), null);
            String rejectionReason = runAdmission(guardrails, admissionContext, "skill " + skillName, thinker, dispatcher);
            if (rejectionReason == null) {
                conversation.addSkill(skill);
                loaded.add(skillName);
                admitted.add(skillName);
                log.debug("ToolHub: admitted skill {} for {}{}",
                        skillName,
                        thinker.getClass().getSimpleName(),
                        (guardrails.isEmpty() ? " (no guardrails)" : " (" + guardrails.size() + " guardrail(s) passed)"));
            }
            else {
                rejected.add(new RequestSkillResult.RejectedSkill(skillName, rejectionReason));
            }
        }
        RequestSkillResult result = new RequestSkillResult();
        result.setAdmitted(admitted);
        result.setRejected(rejected);
        return result;
    }

    /**
     * Register skills for a thinker class at runtime, widening the cached compatible set the
     * way {@link #registerCompatible} does for tools.
     */
    public void registerCompatibleSkills(Class<? extends Thinker> thinkerClass, Skill... skills) {
        Set<Skill> set = thinkerSkillCache.computeIfAbsent(thinkerClass, k -> ConcurrentHashMap.newKeySet());
        Collections.addAll(set, skills);
    }

    /** Test-only: forgets every cached compatible set and every admission rule. */
    public void resetAll() {
        thinkerToolCache.clear();
        thinkerSkillCache.clear();
        admissionRules.clear();
        skillAdmissionRules.clear();
    }

    // --- Hot deploy ---

    /**
     * Register a tool class for a thinker class at runtime. Wraps in a
     * {@link ClassToolProvider} and updates the cached compatible set.
     */
    public void registerTool(Class<? extends Thinker> thinkerClass, Class<? extends Tool> toolClass) {
        registerCompatible(thinkerClass, ClassToolProvider.of(toolClass));
    }

    /**
     * Register one or more {@link ToolProvider}s for a thinker class at runtime.
     * Used by MCP connector wiring code to push providers into the catalog without
     * forcing thinker reconstruction.
     */
    public void registerCompatible(Class<? extends Thinker> thinkerClass, ToolProvider... providers) {
        Set<ToolProvider> set = thinkerToolCache.computeIfAbsent(
                thinkerClass, k -> ConcurrentHashMap.newKeySet());
        Collections.addAll(set, providers);
    }

    private static boolean globMatch(String pattern, String name) {
        if (pattern.indexOf('*') < 0) {
            return pattern.equals(name);
        }
        // Simple glob: replace * with .* in a regex
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return name.matches(regex);
    }
}
