/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.util.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The framework-owned gate in front of the configured {@link ModelPicker}. The harness
 * (and the rare resolver-level caller such as the RAG embeddings resolver) calls
 * {@link #resolve(Seat, Situation)}; nothing calls the raw picker, so every answer
 * passes the gate - non-bypassable by construction, the same posture as the job
 * submission door.
 *
 * <p>Gate order is fixed: kind first (an embeddings seat must resolve to an embeddings
 * spec and vice versa - also what makes the later grade check null-safe), then the
 * {@link ComplianceEnvelope}, then the inputs (every input the payload carries is one the
 * request declared, and every declared input one the entry accepts), then deprecation, and
 * the grade floor last, on the picked path alone (under-qualification refused,
 * over-qualification legal: promotion and upward failover depend on it; a pin declares no
 * floor). Every refusal names the seat and the reason; none is an NPE. The
 * floor is the picker's ceiling at most: a seat asking for a grade above everything the
 * deployment serves is lowered to the ceiling before the picker sees it, with a warning,
 * so code written for the whole ladder runs on a deployment with one model.
 *
 * <p>An envelope refusal of an UNPINNED seat is not terminal: the seat is served from the
 * best rung the envelope permits. A {@link Grade#CEILING} seat walks downward from the
 * picker's ceiling (it declared no floor - it asked for the best this application may
 * call), and an explicit-grade seat walks upward from its floor (over-qualification is
 * legal, serving below a declared floor never is), each walked rung logged once. A pin
 * stays terminal: the job named one exact entry, and an entry the envelope refuses is
 * refused. When the whole walk is refused, resolution fails with
 * {@link ModelResolutionError} - the deployment's model policy permits no model for the
 * seat at all, which is a broken deployment, not a correctable fault.
 *
 * <p>The picker instance is whatever {@link #use(ModelPicker)} declared at startup, else
 * resolved lazily from {@code ModelSettings.pickerClass} on the first resolution - the
 * same double-checked shape as {@code Models.backend()}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public final class ModelPickers {
    private static final Logger log = LoggerFactory.getLogger(ModelPickers.class);
    private static volatile ModelPicker picker;
    private static volatile String frozenEmbeddingsId;

    private ModelPickers() {}

    /** Resolves the seat through the configured picker and enforces the gate on the answer. */
    public static ModelSpec resolve(Seat seat, Situation situation) {
        return resolveWith(picker(), seat, situation);
    }

    /**
     * Resolves an embeddings need: the picker's {@link ModelPicker#embeddingsSpec()}
     * declaration through the gate, then FROZEN - the first resolved id binds the
     * process, and a later divergence refuses instead of serving. Stored vectors are
     * only comparable to vectors from the model that produced them; ad-hoc change is
     * silent search corruption, so it is structurally impossible here rather than
     * conventionally discouraged.
     */
    public static ModelSpec resolveEmbeddings(Seat seat, Situation situation) {
        return resolveEmbeddingsWith(picker(), seat, situation);
    }

    /** The embeddings channel over an explicit picker. Package-private so same-package tests can drive the freeze. */
    static ModelSpec resolveEmbeddingsWith(ModelPicker picker, Seat seat, Situation situation) {
        requireEnvelope(seat, situation);
        ModelSpec spec = picker.embeddingsSpec();
        if (spec == null) {
            throw new UncorrectableRuntimeLLMException("Picker " + picker.getClass().getSimpleName()
                    + " declares no embeddings spec - the embeddings model is corpus configuration, and this"
                    + " deployment has not configured one (seat " + seat.jobClass().getSimpleName() + ")");
        }
        gate(spec, seat, situation, "Embeddings declaration");
        String frozen = frozenEmbeddingsId;
        if (frozen == null) {
            synchronized (ModelPickers.class) {
                if (frozenEmbeddingsId == null) {
                    frozenEmbeddingsId = spec.getId();
                }
                frozen = frozenEmbeddingsId;
            }
        }
        if (!frozen.equals(spec.getId())) {
            throw new UncorrectableRuntimeLLMException("Embeddings declaration changed mid-process: "
                    + frozen + " already serves this process and " + spec.getId()
                    + " would produce vectors incomparable to everything stored. The embeddings model"
                    + " changes only with a deliberate corpus migration, never at resolution.");
        }
        return spec;
    }

    /**
     * Gates a job-pinned spec: the pin skips the picker consult - the job already
     * decided - but kind, envelope, vision, and deprecation still judge it. The grade
     * floor does not apply: a pin declares no floor, and a grade-less catalog entry is
     * legally pinnable.
     */
    public static ModelSpec resolvePinned(ModelSpec pinned, Seat seat, Situation situation) {
        requireEnvelope(seat, situation);
        gate(pinned, seat, situation, "Pinned spec");
        return pinned;
    }

    /**
     * Resolves a decision need: the picker's {@link ModelPicker#decisionSpec()} declaration
     * through the gate. Decision models are not on the capability ladder, so a decision seat
     * never reaches {@link ModelPicker#provide}: the deployment names the one entry that
     * answers decisions, and every decision seat gets it. Not frozen, unlike embeddings:
     * nothing stored depends on which decision model answered.
     */
    public static ModelSpec resolveDecision(Seat seat, Situation situation) {
        return resolveDecisionWith(picker(), seat, situation);
    }

    /** The decision channel over an explicit picker. Package-private so same-package tests can drive refusals. */
    static ModelSpec resolveDecisionWith(ModelPicker picker, Seat seat, Situation situation) {
        requireEnvelope(seat, situation);
        ModelSpec spec = picker.decisionSpec();
        if (spec == null) {
            throw new UncorrectableRuntimeLLMException("Picker " + picker.getClass().getSimpleName()
                    + " declares no decision spec - the decision model is deployment configuration, and this"
                    + " deployment has not configured one (seat " + seat.jobClass().getSimpleName() + ")");
        }
        gate(spec, seat, situation, "Decision declaration");
        return spec;
    }

    /** The gate over an explicit picker. Package-private so same-package tests can drive refusals. */
    static ModelSpec resolveWith(ModelPicker picker, Seat seat, Situation situation) {
        if (seat.embeddings()) {
            throw new UncorrectableRuntimeLLMException("Embeddings seat " + seat.jobClass().getSimpleName()
                    + " cannot resolve through the picker: the embeddings model is corpus configuration"
                    + " (ModelPicker.embeddingsSpec), resolved and frozen via resolveEmbeddings");
        }
        if (seat.decision()) {
            throw new UncorrectableRuntimeLLMException("Decision seat " + seat.jobClass().getSimpleName()
                    + " cannot resolve through the picker: the decision model is deployment configuration"
                    + " (ModelPicker.decisionSpec), resolved via resolveDecision");
        }
        requireEnvelope(seat, situation);
        boolean bestAvailable = seat.grade() == Grade.CEILING;
        Seat declared = atRung(picker, seat);
        Set<String> refused = new LinkedHashSet<>();
        RuntimeException walkEnd = null;
        for (Grade rung : rungsToTry(picker, declared.grade(), bestAvailable)) {
            Seat tried = rung == declared.grade() ? declared : new Seat(declared.jobClass(), rung, ModelKind.LLM);
            ModelSpec spec;
            try {
                spec = picker.provide(tried, situation);
            }
            catch (RuntimeException e) {
                if (rung == declared.grade()) {
                    throw e;
                }
                // A walked rung the picker itself cannot serve ends the walk; the seat's own
                // rung failing in the picker keeps its meaning and propagates above
                walkEnd = e;
                break;
            }
            if (spec == null) {
                throw new UncorrectableRuntimeLLMException("Model picker " + picker.getClass().getSimpleName()
                        + " returned no spec for seat " + describe(tried));
            }
            if (!situation.getEnvelope().permits(spec)) {
                refused.add(spec.getId());
                continue;
            }
            gate(spec, tried, situation, "Model picker resolved");
            // An embeddings seat never reaches this point (refused at entry), so every seat
            // here declares a grade floor to enforce
            if (spec.getGrade() == null) {
                throw new UncorrectableRuntimeLLMException("Catalog entry " + spec.getId()
                        + " carries no grade and cannot serve LLM seat " + describe(tried));
            }
            if (!spec.getGrade().atLeast(tried.grade())) {
                throw new UncorrectableRuntimeLLMException("Model picker resolved " + spec.getId() + " (grade "
                        + spec.getGrade() + ") below the declared floor of seat " + describe(tried));
            }
            if (rung != declared.grade() && walkWarned.add(declared.jobClass().getName() + "/" + declared.grade() + "/" + rung)) {
                log.warn("Compliance envelope {} refuses {} for seat {}: serving it at rung {} ({}), the best this"
                                + " application may call.", situation.getEnvelope().getClass().getSimpleName(),
                        refused, describe(declared), rung, spec.getId());
            }
            return spec;
        }
        throw new ModelResolutionError("This deployment cannot serve seat " + describe(declared)
                + ": the compliance envelope " + situation.getEnvelope().getClass().getSimpleName()
                + " refuses every entry the picker " + picker.getClass().getSimpleName()
                + " yields for it (" + String.join(", ", refused) + "). The deployment's model policy"
                + " permits no model for this seat at all - fix the picker's pins or the application's"
                + " envelope; there is nothing a caller can correct.", walkEnd);
    }

    /**
     * The rungs a seat may legally be served from, in preference order: its own rung first,
     * then upward to the picker's ceiling (over-qualification is legal, serving below a
     * declared floor never is). A best-available seat - one declared {@link Grade#CEILING} -
     * walks downward from the ceiling instead: it declared no floor, and the best entry the
     * envelope permits is exactly what it asked for.
     */
    private static List<Grade> rungsToTry(ModelPicker picker, Grade declared, boolean bestAvailable) {
        List<Grade> rungs = Grade.rungs();
        int at = rungs.indexOf(declared);
        List<Grade> order = new ArrayList<>();
        if (bestAvailable) {
            for (int i = at; i >= 0; i--) {
                order.add(rungs.get(i));
            }
            return order;
        }
        Grade ceiling = picker.ceiling();
        int top = ceiling != null && ceiling.isRung() ? Math.max(rungs.indexOf(ceiling), at) : rungs.size() - 1;
        for (int i = at; i <= top; i++) {
            order.add(rungs.get(i));
        }
        return order;
    }

    /** Seat, declared rung and served rung triplets already warned about, so a fan-out warns once. */
    private static final Set<String> walkWarned = ConcurrentHashMap.newKeySet();

    private static void requireEnvelope(Seat seat, Situation situation) {
        if (situation.getEnvelope() == null) {
            throw new UncorrectableRuntimeLLMException("Situation carries no ComplianceEnvelope for seat "
                    + seat.jobClass().getSimpleName() + " - the caller must supply the dispatcher's sealed envelope");
        }
    }

    /** The checks every resolution passes, picked or pinned: kind, envelope, inputs, deprecation. */
    private static void gate(ModelSpec spec, Seat seat, Situation situation, String source) {
        ComplianceEnvelope envelope = situation.getEnvelope();
        if (seat.kind() != spec.kind()) {
            throw new UncorrectableRuntimeLLMException(source + " " + spec.getId() + " for seat " + describe(seat)
                    + ": " + word(seat.kind()) + " seat must resolve to an entry of its own kind, and "
                    + spec.getId() + " is " + word(spec.kind()) + " entry");
        }
        if (!envelope.permits(spec)) {
            throw new UncorrectableRuntimeLLMException("Compliance envelope " + envelope.getClass().getSimpleName()
                    + " refuses " + spec.getId() + " for seat " + describe(seat));
        }
        // the declaration chooses the model and the payload is checked against it: an input
        // the request carries without declaring is a declaration the code forgot, refused so
        // the model is never chosen by what the history happens to hold; a declared input the
        // entry cannot take is a resolution that would fail at the provider, refused here by name.
        // A situation built without a binding declares nothing
        Set<Input> sends = situation.getSends() != null ? situation.getSends() : Set.of();
        if (situation.getCarried() != null) {
            for (Input carried : situation.getCarried()) {
                if (!sends.contains(carried)) {
                    throw new UncorrectableRuntimeLLMException("Seat " + describe(seat) + " sends " + inputWord(carried)
                            + " it did not declare: declare them on the request's model binding (ModelBinding.setSends)"
                            + " so the model that serves it is chosen to accept them");
                }
            }
        }
        for (Input input : sends) {
            if (!spec.accepts(input)) {
                throw new UncorrectableRuntimeLLMException(source + " " + spec.getId() + " for seat " + describe(seat)
                        + ", which sends " + inputWord(input) + ", and " + spec.getId() + " does not accept "
                        + inputWord(input) + " (" + (input == Input.IMAGES ? "supports_vision" : "supports_documents")
                        + " in the catalog)");
            }
        }
        if (spec.getStatus() != ModelStatus.OPEN) {
            throw new UncorrectableRuntimeLLMException(source + " " + spec.getStatus() + " spec " + spec.getId()
                    + " for seat " + describe(seat) + " - a " + spec.getStatus() + " spec stays in the catalog only so historical runs remain priceable");
        }
    }

    private static String describe(Seat seat) {
        if (seat.embeddings()) {
            return seat.jobClass().getSimpleName() + " (embeddings)";
        }
        if (seat.decision()) {
            return seat.jobClass().getSimpleName() + " (decision)";
        }
        return seat.jobClass().getSimpleName() + (seat.grade() != null ? " (grade " + seat.grade() + ")" : " (pinned)");
    }

    /** A kind in a refusal's words: {@code an LLM}, {@code an embeddings}, {@code a decision}. */
    private static String inputWord(Input input) {
        return switch (input) {
            case IMAGES -> "images";
            case DOCUMENTS -> "documents";
        };
    }

    private static String word(ModelKind kind) {
        return switch (kind) {
            case LLM -> "an LLM";
            case EMBEDDINGS -> "an embeddings";
            case DECISION -> "a decision";
        };
    }

    /**
     * Declares the deployment's picker as an instance: one call at startup, before the first
     * job resolves a model, and no configuration class or system property. A picker that
     * needs an endpoint, a credential source or any other constructor argument comes in this
     * way; {@code ModelSettings.pickerClass} remains for a deployment that prefers
     * to name a no-arg class in its configuration. Once, and only before the first
     * resolution: a second declaration, or one after a job has already resolved through the
     * configured class, throws instead of silently switching policy under running work.
     */
    public static void use(ModelPicker picker) {
        if (picker == null) {
            throw new UncorrectableRuntimeLLMException("ModelPickers.use requires a picker instance");
        }
        synchronized (ModelPickers.class) {
            if (ModelPickers.picker != null) {
                throw new UncorrectableRuntimeLLMException("The model picker is already " + ModelPickers.picker.getClass().getName()
                        + "; a deployment declares its picker once, before the first model resolution");
            }
            log.info("Model picker: {} (declared through ModelPickers.use)", picker.getClass().getName());
            ModelPickers.picker = picker;
        }
    }

    private static ModelPicker picker() {
        ModelPicker p = picker;
        if (p == null) {
            synchronized (ModelPickers.class) {
                p = picker;
                if (p == null) {
                    p = Reflection.newInstance(Settings.get(ModelSettings.class).pickerClass);
                    log.info("Model picker: {} (ModelSettings.pickerClass)", p.getClass().getName());
                    picker = p;
                }
            }
        }
        return p;
    }

    /**
     * Pushes an availability observation into the configured picker - the probe's only
     * write path, keeping the raw picker instance unreachable. Returns the displaced
     * observation for the same spec (drift input), null from stateless pickers.
     */
    public static ProbeOutcome recordProbe(ProbeOutcome outcome) {
        return picker().recordProbe(outcome);
    }

    /** The configured picker's declared grade pins, for reporting surfaces. */
    public static Map<Grade, ModelSpec> describePins() {
        return picker().describePins();
    }

    /** The rung a seat declaring {@link Grade#CEILING} resolves to under the configured picker. */
    public static Grade ceiling() {
        return ceilingOf(picker());
    }

    /**
     * The strongest grade a picker serves - what a seat declaring {@link Grade#CEILING}
     * resolves to. Refuses loudly when the picker names none: the picker serves no rung, so
     * a seat asking for the best available model has nothing to land on.
     */
    private static Grade ceilingOf(ModelPicker p) {
        Grade ceiling = p.ceiling();
        if (ceiling == null) {
            throw new UncorrectableRuntimeLLMException("Picker " + p.getClass().getSimpleName()
                    + " serves no rung, so a seat asking for the best available model has nothing to land on."
                    + " Provide a credential for an entry of the catalog, or, in a picker of your own, implement ModelPicker.ceiling().");
        }
        if (!ceiling.isRung()) {
            throw new UncorrectableRuntimeLLMException("Picker " + p.getClass().getSimpleName()
                    + " declares " + ceiling + " as its ceiling; a ceiling names a rung of the ladder");
        }
        return ceiling;
    }

    /**
     * A seat declaring {@link Grade#CEILING} becomes a seat at the picker's strongest served
     * rung, here and only here: no picker and no ladder comparison ever sees CEILING. A seat
     * declaring a rung above that ceiling becomes a seat at the ceiling too, with a warning
     * once per job class and grade: the code asked for more than this deployment serves, and
     * the deployment's best is what it gets, which is what a deployment with one model wants.
     * The grade floor below the ceiling stays a wall: a picker that returns less than it
     * could is a bug, and is refused.
     */
    private static Seat atRung(ModelPicker picker, Seat seat) {
        if (seat.grade() == Grade.CEILING) {
            return new Seat(seat.jobClass(), ceilingOf(picker), seat.kind());
        }
        Grade ceiling = picker.ceiling();
        if (ceiling != null && ceiling.isRung() && !ceiling.atLeast(seat.grade())) {
            if (loweredWarned.add(seat.jobClass().getName() + "/" + seat.grade())) {
                log.warn("Seat {} asks for grade {} and this deployment serves nothing above {}: serving it at {}."
                        + " Provide a credential for a stronger entry, or pin one in models.json, to serve it as declared.",
                        seat.jobClass().getSimpleName(), seat.grade(), ceiling, ceiling);
            }
            return new Seat(seat.jobClass(), ceiling, seat.kind());
        }
        return seat;
    }

    /** Job class and grade pairs already warned about being served at the ceiling, so a fan-out warns once. */
    private static final Set<String> loweredWarned = ConcurrentHashMap.newKeySet();

    /** The configured picker's embeddings declaration, for reporting surfaces - ungated, possibly null. */
    public static ModelSpec declaredEmbeddings() {
        return picker().embeddingsSpec();
    }

    /** The configured picker's decision declaration, for reporting surfaces - ungated, possibly null. */
    public static ModelSpec declaredDecision() {
        return picker().decisionSpec();
    }

    /** Test hook: forces picker re-resolution and thaws the embeddings freeze. Package-private. */
    static void resetForTests() {
        picker = null;
        frozenEmbeddingsId = null;
        loweredWarned.clear();
        walkWarned.clear();
    }
}
