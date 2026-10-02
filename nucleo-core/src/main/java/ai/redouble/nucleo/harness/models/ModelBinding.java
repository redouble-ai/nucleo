/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;

import java.util.*;

/**
 * The deferred binding between a declared model need and the spec that ends up serving
 * it. Minted by {@code JobRequirements.requireModel}/{@code requireEmbeddings}/
 * {@code requireDecision} - one per invocation, so each dispatcher attempt's requirements
 * capture is a fresh cell - and filled exactly once by the harness at the resolution step,
 * before any resource is acquired. Write-once is the "one resolution per attempt" invariant
 * made an object: a second {@link #resolve(ModelSpec)} throws, and every reader (reservation,
 * client, {@code ConversationContext.getModel()}) reads this same cell, so no stale binding
 * can exist.
 *
 * <p>A pre-resolution {@link #getModel()} throws: code that needs a model before the
 * harness has resolved one is depending on information that does not exist yet, and
 * the contract makes that loud instead of serving a stale spec.
 *
 * <p>Three LLM declaration forms, each priced as exactly what the wire will carry:
 * <ul>
 *   <li><b>Wired conversation</b> ({@code requireModel(Grade, Depth)} +
 *       {@code conversation.setModelBinding(binding)}): input counted from the
 *       conversation post-resolution under the resolved spec's tokenizer, output and
 *       thinking from the conversation's own reserve resolution.
 *   <li><b>Counted prompt</b> ({@code requireModelForPrompt}): the prompt text is
 *       counted post-resolution under the resolved spec's tokenizer, plus the declared
 *       output and the spec's thinking budget for the declared depth.
 *   <li><b>Flat scalars</b>: the declared input count, plus the declared output and the
 *       spec's thinking budget for the declared depth.
 * </ul>
 * Every LLM form declares its output ({@link OutputDeclaration}, a rung or a count); there
 * is no default. A job that attaches its resolved binding to the conversation it builds
 * ({@code conversation.setModelBinding(binding)}) lets the conversation read the declared
 * depth and output from here, so the wire and the reservation cannot drift.
 *
 * <p>An embeddings or a decision binding has input only: the vector or the distribution is
 * not output the limiter meters. Both come in a flat form (a token count) and a counted form
 * (the request text, counted post-resolution under the resolved spec's tokenizer), and both
 * may be pinned.
 *
 * <p>Jobs may stash the binding returned by their own {@code getRequirements()} in a
 * field: the dispatcher captures requirements once per attempt and that capture is the
 * last mint before resolution, so the stashed cell is the one that resolves.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public class ModelBinding {
    private final Grade grade;
    private final Depth depth;
    private final Integer inTokens;
    private final OutputDeclaration output;
    private final String promptText;
    private final ModelKind kind;
    private final ModelSpec pinnedSpec;
    private boolean interactive;
    private Set<Input> sends;
    private ConversationContext conversation;
    private volatile ModelSpec spec;
    private volatile Integer reservation;
    private volatile int reservedInput;
    private volatile int reservedOutput;

    /** Wired-conversation form: sizes come from the conversation once it is attached. */
    public ModelBinding(Grade grade, Depth depth) {
        this(grade, depth, null, null, null, ModelKind.LLM, null);
        requireDeclaration(grade, depth);
    }

    /** Flat form: the declared input count and output declaration price the reservation. */
    public ModelBinding(Grade grade, Depth depth, int inTokens, OutputDeclaration output) {
        this(grade, depth, inTokens, output, null, ModelKind.LLM, null);
        requireDeclaration(grade, depth);
        requireOutput(output);
    }

    /** Counted-prompt form: the text is counted post-resolution under the resolved spec's tokenizer. */
    public ModelBinding(Grade grade, Depth depth, String promptText, OutputDeclaration output) {
        this(grade, depth, null, output, promptText, ModelKind.LLM, null);
        requireDeclaration(grade, depth);
        requireOutput(output);
        if (promptText == null) {
            throw new IllegalArgumentException("Prompt form requires the prompt text");
        }
    }

    /**
     * Wired pinned form: the job names the exact spec. Discouraged - a grade seat lets
     * the picker keep the system resilient - but first-class: the pin skips only the
     * picker consult, never the gate, and a retry re-mints the same spec (no failover
     * for pins, structurally). The grade is the spec's own, recorded for observability;
     * there is no floor to enforce.
     */
    public ModelBinding(ModelSpec pinned, Depth depth) {
        this(gradeOf(pinned), depth, null, null, null, ModelKind.LLM, pinned);
        requirePinnedLlm(pinned, depth);
    }

    /** Flat pinned form: exact spec plus the declared input count and output declaration. */
    public ModelBinding(ModelSpec pinned, Depth depth, int inTokens, OutputDeclaration output) {
        this(gradeOf(pinned), depth, inTokens, output, null, ModelKind.LLM, pinned);
        requirePinnedLlm(pinned, depth);
        requireOutput(output);
    }

    private static Grade gradeOf(ModelSpec pinned) {
        if (pinned == null) {
            throw new IllegalArgumentException("A pinned model requirement must name its spec");
        }
        return pinned.getGrade();
    }

    /** The declaration FORM sets the kind: a spec of another family in an LLM pin is a mint-time error, never a silent kind flip. */
    private static void requirePinnedLlm(ModelSpec pinned, Depth depth) {
        requirePinnedKind(pinned, ModelKind.LLM);
        if (depth == null) {
            throw new IllegalArgumentException("A pinned LLM requirement must declare its depth");
        }
    }

    private static void requirePinnedKind(ModelSpec pinned, ModelKind kind) {
        if (pinned == null) {
            throw new IllegalArgumentException("A pinned " + kind.name().toLowerCase() + " requirement must name its spec");
        }
        if (pinned.kind() != kind) {
            throw new IllegalArgumentException("Pinned " + kind.name().toLowerCase() + " form received "
                    + pinned.kind().name().toLowerCase() + " spec " + pinned.getId()
                    + " - pin a spec through the form of its own kind");
        }
    }

    private static void requireOutput(OutputDeclaration output) {
        if (output == null) {
            throw new IllegalArgumentException("A model requirement must declare its output - a rung or a token count; "
                    + "there is no default, silent or otherwise.");
        }
    }

    private static void requireDeclaration(Grade grade, Depth depth) {
        if (grade == null) {
            throw new IllegalArgumentException("An LLM requirement must declare its grade - the seat's capability floor");
        }
        if (depth == null) {
            throw new IllegalArgumentException("An LLM requirement must declare its depth");
        }
    }

    private ModelBinding(Grade grade, Depth depth, Integer inTokens, OutputDeclaration output, String promptText, ModelKind kind, ModelSpec pinnedSpec) {
        this.grade = grade;
        this.depth = depth;
        this.inTokens = inTokens;
        this.output = output;
        this.promptText = promptText;
        this.kind = kind;
        this.pinnedSpec = pinnedSpec;
    }

    /** Embeddings declaration: no grade - embeddings are a separate client family, pinned per corpus. */
    public static ModelBinding embeddings(int tokens) {
        return new ModelBinding(null, null, tokens, null, null, ModelKind.EMBEDDINGS, null);
    }

    /** Pinned embeddings declaration: the exact embeddings spec, still subject to the gate. */
    public static ModelBinding embeddingsPinned(ModelSpec pinned, int tokens) {
        requirePinnedKind(pinned, ModelKind.EMBEDDINGS);
        return new ModelBinding(null, null, tokens, null, null, ModelKind.EMBEDDINGS, pinned);
    }

    /** Decision declaration with a flat input count: no grade - decision models are a separate client family, named per deployment. */
    public static ModelBinding decision(int tokens) {
        return new ModelBinding(null, null, tokens, null, null, ModelKind.DECISION, null);
    }

    /** Decision declaration priced from the request text, counted post-resolution under the resolved spec's tokenizer. */
    public static ModelBinding decision(String requestText) {
        if (requestText == null) {
            throw new IllegalArgumentException("Counted decision form requires the request text");
        }
        return new ModelBinding(null, null, null, null, requestText, ModelKind.DECISION, null);
    }

    /** Pinned decision declaration: the exact decision spec, still subject to the gate. */
    public static ModelBinding decisionPinned(ModelSpec pinned, int tokens) {
        requirePinnedKind(pinned, ModelKind.DECISION);
        return new ModelBinding(null, null, tokens, null, null, ModelKind.DECISION, pinned);
    }

    /** Pinned decision declaration priced from the request text, counted under the pinned spec's tokenizer. */
    public static ModelBinding decisionPinned(ModelSpec pinned, String requestText) {
        requirePinnedKind(pinned, ModelKind.DECISION);
        if (requestText == null) {
            throw new IllegalArgumentException("Counted decision form requires the request text");
        }
        return new ModelBinding(null, null, null, null, requestText, ModelKind.DECISION, pinned);
    }

    /** Whether this binding names its exact spec. The dispatcher gates a pin instead of consulting the picker. */
    public boolean isPinned() {
        return pinnedSpec != null;
    }

    /** The pinned spec of a pinned binding; null for grade-declared seats. */
    public ModelSpec getPinnedSpec() {
        return pinnedSpec;
    }

    /**
     * A binding born resolved, for contexts constructed around a spec the harness has
     * already picked (client-internal conversations, compaction working copies). Not a
     * bypass of the picker for job-level work: {@code JobRequirements} only carries
     * bindings it minted itself.
     */
    public static ModelBinding preResolved(ModelSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("preResolved requires a spec");
        }
        ModelBinding binding = new ModelBinding(spec.getGrade(), null, null, null, null, spec.kind(), null);
        binding.spec = spec;
        return binding;
    }

    public Grade getGrade() {
        return grade;
    }

    public Depth getDepth() {
        return depth;
    }

    /** The declared output, or null for the wired form (the conversation declares) and for the input-only kinds. */
    public OutputDeclaration getOutput() {
        return output;
    }

    /** The client family this binding asks for. */
    public ModelKind getKind() {
        return kind;
    }

    public boolean isEmbeddings() {
        return kind == ModelKind.EMBEDDINGS;
    }

    public boolean isDecision() {
        return kind == ModelKind.DECISION;
    }

    public boolean isInteractive() {
        return interactive;
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    /** The inputs beyond text this request declared it sends; null when it declared none, a text-only request. */
    public Set<Input> getSends() {
        return sends;
    }

    /**
     * Declares the inputs beyond text the request sends, before it is resolved: the picker serves
     * it only from an entry that accepts every one of them, and the gate refuses a payload that
     * carries an input not declared here. Declared, never inferred, so the model a request gets
     * is known when it is declared and a conversation never changes model because of what its
     * history holds.
     */
    public void setSends(Set<Input> sends) {
        this.sends = Set.copyOf(sends);
    }

    public ConversationContext getConversation() {
        return conversation;
    }

    /** Called by {@code ConversationContext.setModelBinding} - the other half of the two-way wiring. */
    public void attachConversation(ConversationContext conversation) {
        this.conversation = conversation;
    }

    public boolean isResolved() {
        return spec != null;
    }

    /**
     * The resolved spec. Throws before resolution: pre-resolution model dependence is a
     * bug this contract surfaces loudly instead of serving a stale binding.
     */
    public ModelSpec getModel() {
        ModelSpec resolved = spec;
        if (resolved == null) {
            throw new UncorrectableRuntimeLLMException("Model binding is not resolved yet"
                    + (grade != null ? " (declared grade " + grade + ")" : " (" + kind.name().toLowerCase() + ")")
                    + ". The harness resolves bindings at the requirements-resolution step, before execution - "
                    + "code reading the model earlier depends on information that does not exist yet.");
        }
        return resolved;
    }

    /**
     * Fills the cell - harness-only, write-once. Stamps the resolved id into the wired
     * conversation's prior, so stickiness survives serialization and restarts.
     */
    public void resolve(ModelSpec resolved) {
        if (resolved == null) {
            throw new IllegalArgumentException("Cannot resolve a binding to null");
        }
        if (this.spec != null) {
            throw new IllegalStateException("Model binding already resolved to " + this.spec.getId()
                    + " - one resolution per attempt; the dispatcher mints a fresh binding for each attempt");
        }
        this.spec = resolved;
        if (conversation != null) {
            conversation.setPriorSpecId(resolved.getId());
        }
    }

    /**
     * Prices the resolved binding: the tokens it reserves against the resolved spec's
     * limiter. Called once by the harness after {@link #resolve(ModelSpec)}, before any
     * acquisition; the result is stored for {@link #getReservation()}. Output and thinking
     * are priced the same way on every LLM form, as the wire will carry them: a wired
     * conversation resolves its own reserve, the prompt and flat forms take the declared
     * output plus the spec's thinking budget for the declared depth, clamped at the output
     * ceiling exactly as the wire clamps. An embeddings or decision call has input only. A
     * payload that cannot fit the resolved spec's context window fails here, with the counts
     * named, instead of burning a reservation on a request the provider must reject.
     */
    public int price() {
        ModelSpec resolved = getModel();
        int in;
        int out;
        if (kind != ModelKind.LLM) {
            // the vector or the distribution is not output the limiter meters
            in = promptText != null ? TokenizerFactory.get().forModel(resolved).countTokens(promptText) : inTokens;
            out = 0;
        }
        else if (conversation != null) {
            in = conversation.getTotalTokens(resolved);
            out = conversation.outputReserve(resolved);
        }
        else {
            in = promptText != null ? TokenizerFactory.get().forModel(resolved).countTokens(promptText) : inTokens;
            out = ConversationContext.outputReserve(resolved, output.tokens(resolved), depth);
        }
        if (in + out > resolved.getMaxContextTokens()) {
            throw new UncorrectableRuntimeLLMException("Input (" + in + " tokens) plus the declared output reserve (" + out
                    + ") exceeds the context window of " + resolved.getId() + " (" + resolved.getMaxContextTokens() + ")");
        }
        reservedInput = in;
        reservedOutput = out;
        reservation = in + out;
        return reservation;
    }

    /** The input half of the priced reservation, for a spend gate that prices input and output at their own rates. */
    public int getReservedInput() {
        getReservation();
        return reservedInput;
    }

    /** The output half of the priced reservation. */
    public int getReservedOutput() {
        getReservation();
        return reservedOutput;
    }

    /** The priced reservation. Throws if {@link #price()} has not run - reading an unpriced binding is a harness bug. */
    public int getReservation() {
        Integer priced = reservation;
        if (priced == null) {
            throw new IllegalStateException("Model binding for " + getModel().getId()
                    + " has not been priced - the harness prices each binding after resolution, before acquisition");
        }
        return priced;
    }
}
