/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The shipped picker: a deployment that has authored nothing runs on the entries it can
 * call, and one that has written {@code pins} into its {@code models.json} runs on its order,
 * with no picker class in either case.
 *
 * <p>A request declares its grade and the inputs beyond text it sends ({@link Situation#getSends()},
 * from {@code ModelBinding.setSends}). It is served by the first entry, in this order, that is
 * eligible - not closed, a provider artifact on the classpath serving it, its data-share posture
 * provisioned where it needs one, its provider's credential in the store, permitted by the
 * request's compliance envelope - and accepts every declared input:
 * <ol>
 *   <li>The grade's entries in the deployment's order ({@link CatalogPins#grades()}), the first
 *       being the grade's default. One that cannot be called is skipped, logged once naming it
 *       and why: the order names the fallback the deployment chose, so refusing would ignore it.
 *       One that does not accept a declared input is passed over silently; that is what the
 *       order is for.</li>
 *   <li>The grade's other entries, cheapest first by list price per million, input plus output
 *       (an unpriced entry after every priced one, in catalog order), logged once naming the
 *       entry and saying the choice was not the deployment's.</li>
 *   <li>Nothing of the grade qualifies: the same two steps at the next rung up, logged once. A
 *       deployment with one strong model runs every seat up to that model's grade on it;
 *       over-qualification is legal at the gate.</li>
 *   <li>Nothing at the grade or above: a {@link ModelResolutionError} naming, per provider in the
 *       pool, what to provide - the deployment cannot serve the seat at all, which is a broken
 *       deployment, not a correctable fault. A seat above everything the deployment serves never
 *       reaches this picker at its own grade: the gate lowers it to the picker's
 *       {@link #ceiling()} first, with a warning, so a deployment with one small model still
 *       answers every seat.</li>
 * </ol>
 * The payload never chooses: a request that carries images or files it did not declare is
 * refused at the gate, so a conversation never changes model because of what its history holds.
 * Nothing is cached, so the choice follows the credentials and the order as they change.
 *
 * <p>The embeddings declaration and the decision declaration are single pins: the pin, unless
 * no provider on this classpath serves it, else the first eligible embeddings entry (the warning
 * states that every stored vector is now tied to it) and the first eligible decision entry whose
 * provider serves its wire id, chosen once. The ceiling is never pinned: it is the highest rung
 * with an eligible entry, else null, which the gate refuses with its own message.
 *
 * <p>An {@link OpenModelPicker}: the framework's walls (deprecation, envelope, inputs, probe
 * state) and its overload substitution apply as to any picker. A deployment that wants per-seat
 * routing or a route policy of its own authors a class; that is the escalation, not the entry
 * ticket.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class DefaultModelPicker extends OpenModelPicker {
    private static final Logger log = LoggerFactory.getLogger(DefaultModelPicker.class);
    private final Predicate<ModelSpec> callable;
    /** Every choice or skip already logged, by grade, inputs and entry, so a fan-out logs each once. */
    private final Set<String> logged = ConcurrentHashMap.newKeySet();
    private volatile ModelSpec chosenEmbeddings;
    private volatile ModelSpec chosenDecision;

    public DefaultModelPicker() {
        this(DefaultModelPicker::providerConfigured);
    }

    /** Over an explicit "can this deployment call it" answer, so the rules are testable without a credential store. */
    DefaultModelPicker(Predicate<ModelSpec> callable) {
        this.callable = callable;
    }

    private static boolean providerConfigured(ModelSpec spec) {
        return ClientProviders.get(spec.getProviderKey()).configured();
    }

    @Override
    protected ModelSpec pick(Seat seat, Situation situation) {
        // a situation built without a binding declares nothing beyond text
        Set<Input> sends = situation.getSends() != null ? situation.getSends() : Set.of();
        List<Grade> rungs = Grade.rungs();
        for (int i = rungs.indexOf(seat.grade()); i < rungs.size(); i++) {
            Grade rung = rungs.get(i);
            ModelSpec spec = fromGrade(rung, sends, situation.getEnvelope());
            if (spec != null) {
                if (rung != seat.grade() && logged.add("above/" + seat.grade() + "/" + sends + "/" + spec.getId())) {
                    log.warn("Nothing of grade {}{} can be called by this deployment ({}): serving {} ({} via {}) of grade {},"
                                    + " the nearest above, for every such {} seat. Provide the credential for an entry of {}, or place one"
                                    + " in the grade's order in models.json (\"pins\").",
                            seat.grade(), describe(sends), explain(qualifying(seat.grade(), sends)), spec.getId(), spec.getIdentity(),
                            spec.getProviderKey(), rung, seat.grade(), seat.grade());
                }
                return spec;
            }
        }
        List<ModelSpec> pool = new ArrayList<>();
        for (int i = rungs.indexOf(seat.grade()); i < rungs.size(); i++) {
            pool.addAll(qualifying(rungs.get(i), sends));
        }
        throw new ModelResolutionError("No catalog entry of grade " + seat.grade() + " or above" + describe(sends)
                + " can be called by this deployment. "
                + (pool.isEmpty() && !sends.isEmpty() ? "The catalog carries no entry of that grade or above" + describe(sends) + " at all."
                : explain(pool))
                + " Or place an entry for " + seat.grade() + " in models.json (\"pins\").");
    }

    /**
     * The grade's server for a request sending these inputs: the first entry of the deployment's
     * order that is eligible, permitted by the envelope and accepts them, else the grade's
     * cheapest other such entry, else null when nothing of the grade qualifies. An entry the
     * envelope refuses is skipped like one that cannot be called: the order names the fallback,
     * so the grade is served by it rather than refused at the gate.
     */
    private ModelSpec fromGrade(Grade grade, Set<Input> sends, ComplianceEnvelope envelope) {
        List<String> order = order(grade);
        for (String id : order) {
            ModelSpec spec = Models.spec(id);
            String why = whyNot(spec, envelope);
            if (why != null) {
                if (logged.add("skip/" + grade + "/" + id)) {
                    log.warn("The order for grade {} places {}, which this deployment cannot call ({}): serving the next entry that"
                            + " qualifies instead.", grade, id, why);
                }
                continue;
            }
            if (acceptsAll(spec, sends)) {
                return spec;
            }
        }
        List<ModelSpec> others = new ArrayList<>();
        for (ModelSpec candidate : Models.pool(grade)) {
            if (!order.contains(candidate.getId()) && whyNot(candidate, envelope) == null && acceptsAll(candidate, sends)) {
                others.add(candidate);
            }
        }
        if (others.isEmpty()) {
            return null;
        }
        // stable: entries of equal price, and every unpriced one, keep their catalog order
        others.sort(Comparator.comparingDouble(DefaultModelPicker::listPrice));
        ModelSpec cheapest = others.get(0);
        if (logged.add("fill/" + grade + "/" + sends + "/" + cheapest.getId())) {
            log.warn("{} for grade {}{}: serving {} ({} via {}), the cheapest entry of the grade this deployment can call. Place an"
                            + " entry in the grade's order in models.json (\"pins\": {{\"{}\": [\"<catalog id>\"]}}) to make the choice deliberate.",
                    order.isEmpty() ? "No order" : "Nothing in the order qualifies", grade, describe(sends), cheapest.getId(),
                    cheapest.getIdentity(), cheapest.getProviderKey(), grade);
        }
        return cheapest;
    }

    /** The grade's entries in the deployment's order, empty when the catalog places none. */
    private static List<String> order(Grade grade) {
        CatalogPins pins = Models.pins();
        return pins != null && pins.grades().containsKey(grade) ? pins.grades().get(grade) : List.of();
    }

    /** Every entry of the grade that accepts the inputs, for the refusal that names what to provide. */
    private static List<ModelSpec> qualifying(Grade grade, Set<Input> sends) {
        List<ModelSpec> pool = new ArrayList<>();
        for (ModelSpec candidate : Models.pool(grade)) {
            if (acceptsAll(candidate, sends)) {
                pool.add(candidate);
            }
        }
        return pool;
    }

    private static boolean acceptsAll(ModelSpec spec, Set<Input> sends) {
        for (Input input : sends) {
            if (!spec.accepts(input)) {
                return false;
            }
        }
        return true;
    }

    /** The inputs in words for a message: nothing for a text-only request, " that accepts images and documents" otherwise. */
    private static String describe(Set<Input> sends) {
        if (sends.isEmpty()) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (Input input : Input.values()) {
            if (sends.contains(input)) {
                words.add(input.name().toLowerCase(Locale.ROOT));
            }
        }
        return " that accepts " + String.join(" and ", words);
    }

    /** The entry's list price per million tokens, input plus output; an unpriced entry sorts after every priced one. */
    private static double listPrice(ModelSpec spec) {
        if (!spec.isPriced() || spec.getOutputPricePerMillion() == null) {
            return Double.MAX_VALUE;
        }
        return spec.getInputPricePerMillion() + spec.getOutputPricePerMillion();
    }

    /**
     * The pinned entry, or null when no provider on this classpath serves it (a catalog written by
     * a build that carried the provider): nothing here can call it, so its seat falls to the
     * picker's own choice, as it does for a pin the catalog loader dropped.
     */
    private static ModelSpec servablePin(String id) {
        ModelSpec spec = Models.spec(id);
        return spec != null && ClientProviders.find(spec.getProviderKey()) != null ? spec : null;
    }

    /**
     * Callable now: not closed, a provider artifact on the classpath declares its provider,
     * the data-share posture is provisioned where the entry needs it (a LAX-requiring model
     * without a LAX project fails at the first call, so it is never served), and the
     * provider's credential is in the store.
     */
    private boolean eligible(ModelSpec candidate) {
        return whyNot(candidate) == null;
    }

    /** Why a request under this envelope cannot be served by the entry, in words for a log line, or null when it can. */
    private String whyNot(ModelSpec candidate, ComplianceEnvelope envelope) {
        String why = whyNot(candidate);
        if (why != null) {
            return why;
        }
        return envelope.permits(candidate) ? null : "the compliance envelope " + envelope.getClass().getSimpleName() + " refuses it";
    }

    /** Why the entry cannot be called now, in words for a log line, or null when it can. */
    private String whyNot(ModelSpec candidate) {
        if (candidate.getStatus() != ModelStatus.OPEN) {
            return "it is " + candidate.getStatus().name().toLowerCase(Locale.ROOT);
        }
        if (ClientProviders.find(candidate.getProviderKey()) == null) {
            return "no provider artifact on the classpath declares " + candidate.getProviderKey();
        }
        if (candidate.requiresLax() && Settings.get(ModelSettings.class).mantleLaxProject == null) {
            return "it is served only under provider data sharing and no Mantle project provides it";
        }
        if (!callable.test(candidate)) {
            return "its provider " + candidate.getProviderKey() + " has no credential";
        }
        return null;
    }

    /** Per provider present in the pool, what would make one of its entries callable. */
    private static String explain(Collection<ModelSpec> pool) {
        if (pool.isEmpty()) {
            return "The catalog carries no entry of that grade at all.";
        }
        Map<String, String> byProvider = new LinkedHashMap<>();
        for (ModelSpec candidate : pool) {
            if (candidate.getStatus() != ModelStatus.OPEN) {
                continue;
            }
            byProvider.computeIfAbsent(candidate.getProviderKey(), key -> {
                ClientProvider<?> provider = ClientProviders.find(key);
                if (provider == null) {
                    return "no provider artifact on the classpath declares this key - add the artifact that serves it";
                }
                String need = provider.describeCredential();
                if (candidate.requiresLax() && Settings.get(ModelSettings.class).mantleLaxProject == null) {
                    need += ", and a Bedrock Mantle project with data retention mode provider_data_share configured as ModelSettings.mantleLaxProject";
                }
                return need;
            });
        }
        if (byProvider.isEmpty()) {
            return "Every entry of that grade is closed.";
        }
        StringBuilder sb = new StringBuilder("To serve it, provide one of:");
        for (Map.Entry<String, String> entry : byProvider.entrySet()) {
            sb.append(" [").append(entry.getKey()).append("] ").append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    @Override
    public ModelSpec embeddingsSpec() {
        CatalogPins pins = Models.pins();
        ModelSpec pin = pins != null && pins.embeddings() != null ? servablePin(pins.embeddings()) : null;
        if (pin != null) {
            return pin;
        }
        ModelSpec spec = chosenEmbeddings;
        if (spec == null) {
            synchronized (this) {
                spec = chosenEmbeddings;
                if (spec == null) {
                    spec = firstEligibleEmbeddings();
                    chosenEmbeddings = spec;
                }
            }
        }
        return spec;
    }

    private ModelSpec firstEligibleEmbeddings() {
        Collection<ModelSpec> pool = Models.embeddingsPool();
        for (ModelSpec candidate : pool) {
            if (eligible(candidate)) {
                log.warn("No embeddings pin: serving {} ({} via {}), the first embeddings entry this deployment can call."
                        + " Every vector stored from now on is comparable only to vectors from this model; declare"
                        + " \"pins\": {{\"embeddings\": \"{}\"}} in models.json so the choice survives a catalog change.",
                        candidate.getId(), candidate.getIdentity(), candidate.getProviderKey(), candidate.getId());
                return candidate;
            }
        }
        log.warn("No embeddings entry can be called by this deployment; embeddings seats will refuse. {}", explain(pool));
        return null;
    }

    @Override
    public ModelSpec decisionSpec() {
        CatalogPins pins = Models.pins();
        ModelSpec pin = pins != null && pins.decision() != null ? servablePin(pins.decision()) : null;
        if (pin != null) {
            return pin;
        }
        ModelSpec spec = chosenDecision;
        if (spec == null) {
            synchronized (this) {
                spec = chosenDecision;
                if (spec == null) {
                    spec = firstEligibleDecision();
                    chosenDecision = spec;
                }
            }
        }
        return spec;
    }

    /**
     * The first decision entry this deployment can call whose provider serves its wire id: a
     * decision provider's endpoint is the credential's host, and one host serves one family
     * (a Kev server does not answer as Jev), so the catalog's other entry on the same key is
     * passed over rather than aimed at an endpoint that would refuse it.
     */
    private ModelSpec firstEligibleDecision() {
        Collection<ModelSpec> pool = Models.decisionPool();
        for (ModelSpec candidate : pool) {
            if (eligible(candidate) && ClientProviders.get(candidate.getProviderKey()).serves(candidate.getWireModelId())) {
                log.warn("No decision pin: serving {} ({} via {}), the first decision entry this deployment can call."
                        + " Declare \"pins\": {{\"decision\": \"{}\"}} in models.json to make the choice deliberate.",
                        candidate.getId(), candidate.getIdentity(), candidate.getProviderKey(), candidate.getId());
                return candidate;
            }
        }
        log.warn("No decision entry can be called by this deployment; decision seats will refuse. {}", explain(pool));
        return null;
    }

    /**
     * The highest rung with an entry this deployment can call, else null. Derived on every
     * call, never declared: it follows the credentials and the order as they change.
     */
    @Override
    public Grade ceiling() {
        List<Grade> rungs = Grade.rungs();
        for (int i = rungs.size() - 1; i >= 0; i--) {
            Grade grade = rungs.get(i);
            if (Models.pool(grade).stream().anyMatch(this::eligible)) {
                return grade;
            }
        }
        return null;
    }

    /**
     * Per grade, the entry the order and the credentials choose for a text-only request now, the
     * nearest grade above standing in for one with nothing callable, as {@link #pick} does; a
     * grade nothing at or above serves is left out. Declarative, as the contract says: judged
     * before any application's envelope, which only a resolution carries.
     */
    @Override
    public Map<Grade, ModelSpec> describePins() {
        Map<Grade, ModelSpec> result = new EnumMap<>(Grade.class);
        List<Grade> rungs = Grade.rungs();
        for (int i = 0; i < rungs.size(); i++) {
            for (int j = i; j < rungs.size(); j++) {
                ModelSpec spec = fromGrade(rungs.get(j), Set.of(), candidate -> true);
                if (spec != null) {
                    result.put(rungs.get(i), spec);
                    break;
                }
            }
        }
        return result;
    }
}
