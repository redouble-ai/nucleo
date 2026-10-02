/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.deciding;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * An agent whose model is a decision model. It works an objective over a palette of tools
 * whose inputs and outputs are all artifacts, and at every turn the model does one thing:
 * it ranks the legal moves. A move is a tool applied to an artifact in the registry whose
 * type the tool takes, or {@code finish}; the answer is the artifacts of the output type the
 * model selects when it finishes. The model never fills a field, never names anything
 * that is not there, and never writes a value: what leaves the run is what tools produced,
 * byte-identical through the registry.
 *
 * <p>Code computes the legal moves from the types, so the model only ranks what can run: the
 * run's world is every artifact it adopted and the iterands of every adopted list, in the
 * order they came to be, so the model reads and chooses in one order every run. A
 * tool is a function of one artifact, so a pair (tool, artifact) is applied at most once
 * per run: the move set shrinks every turn and the loop ends by construction, with
 * {@link #setMaxTurns} as the belt to that suspender. One round trip per turn carries
 * every question at once, independent of one another: a {@link Choice} over the tools with
 * a legal move plus {@code finish} (offered once an artifact of the output type exists), a
 * {@link Choice} per such tool over its candidate artifacts ("if this tool runs next, which
 * one"), and a {@link Noul} per artifact of the output type, "does this belong in the
 * answer". Code reads the winning tool's argument and discards the rest, and reads the
 * selection only when {@code finish} wins; output is free on every model of the class, so
 * asking everything costs the state once.
 *
 * <p>The state the model sees stays small: the objective and a digest of the registry, one
 * line per artifact, and the moves already made. Judgments that need a document's full
 * text belong inside tools, which may be decision calls of their own on a focused state.
 *
 * <p>The palette contract is a type. Every tool is a {@link DecisionTool}: an artifact in,
 * an artifact out ({@link ListArtifact} included, whose iterands become candidates by their
 * own type). The key tool is the one whose output is a list of the answer type, named apart
 * so the answer can always be assembled. A palette is legal because it compiled; the
 * constructor inspects no class. A subclass names the output type, the key tool, the rest
 * of the palette and the objective in its constructor
 * and is itself a tool with the {@code (Identifiable parent)} constructor, so a decision
 * thinker sits in another agent's palette as any tool does. The thinker holds no resources:
 * every decision is a {@link DecisionCall} and every tool run is a job, each through the
 * door, so scope, guardrails, admission and the record apply unchanged.
 *
 * <p>What the run leaves behind is {@link #turns()}: every turn's distributions, since a
 * decision model has no reasoning to read.
 *
 * @param <I> the input artifact type, the first entry of the registry
 * @param <O> the output artifact type; the answer is a {@link ListArtifact} of it
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public abstract class DecisionThinker<I extends Artifact, O extends Artifact> extends AbstractOrchestrator<I, ListArtifact<O>> {
    private static final Logger log = LoggerFactory.getLogger(DecisionThinker.class);
    /** The turn budget a run starts with; {@link #setMaxTurns} is the per-run knob. */
    public static final int DEFAULT_MAX_TURNS = 32;
    /** The probability an artifact of the output type must reach to be selected into the answer. */
    public static final double DEFAULT_SELECTION_THRESHOLD = 0.5;
    /** The metadata key the recorded turns are published under, as compact JSON. */
    public static final String OBS_TURNS = "obs.decision_turns";
    /** How many characters of an artifact's digest the state carries. */
    static final int DIGEST_CHARS = 160;
    private final Class<O> outputType;
    private final List<ClassToolProvider> palette;
    private final String objective;
    private int maxTurns = DEFAULT_MAX_TURNS;
    private double selectionThreshold = DEFAULT_SELECTION_THRESHOLD;
    private final List<DecisionTurn> turns = new CopyOnWriteArrayList<>();

    /**
     * The palette is a type: every tool is a {@link DecisionTool}, an artifact in and an
     * artifact out, and the key tool is the one whose output is a list of the answer type, so
     * the answer can always be assembled. Both are the compiler's to hold; nothing here
     * inspects a class. The key tool is folded into the palette, after the others and once.
     *
     * @param outputType the artifact type the answer is a list of
     * @param keyTool    the tool that produces a list of the answer type, each with the
     *                   {@code (Identifiable parent)} constructor and a {@code @ToolName}
     * @param palette    the other tools of the palette, in the order the model is offered them
     * @param objective  what the run is for, in words the model reads on every turn
     */
    protected DecisionThinker(Identifiable parent, Class<O> outputType, Class<? extends DecisionTool<?, ListArtifact<O>>> keyTool,
                              List<Class<? extends DecisionTool<?, ?>>> palette, String objective) {
        super(parent);
        if (outputType == null) {
            throw new IllegalArgumentException("A decision thinker names the artifact type its answer is a list of");
        }
        if (keyTool == null) {
            throw new IllegalArgumentException("A decision thinker names its key tool, the one that produces the answer type");
        }
        if (palette == null) {
            throw new IllegalArgumentException("A decision thinker takes a palette, empty when the key tool is the whole of it");
        }
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("A decision thinker states its objective: the words the model reads on every turn");
        }
        this.outputType = outputType;
        this.objective = objective;
        List<ClassToolProvider> providers = new ArrayList<>(palette.size() + 1);
        for (Class<? extends DecisionTool<?, ?>> tool : palette) {
            providers.add(ClassToolProvider.of(tool));
        }
        if (!palette.contains(keyTool)) {
            providers.add(ClassToolProvider.of(keyTool));
        }
        this.palette = List.copyOf(providers);
    }

    /** The turn budget for this run; the loop ends by construction before it in every run, this is the belt. */
    public void setMaxTurns(int maxTurns) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("A run has at least one turn");
        }
        this.maxTurns = maxTurns;
    }

    /** The probability an artifact of the output type must reach to be selected into the answer. */
    public void setSelectionThreshold(double selectionThreshold) {
        Answer.requireProbability(selectionThreshold, "The selection threshold");
        this.selectionThreshold = selectionThreshold;
    }

    /** The run's turns so far, in order; complete once the run has returned. */
    public List<DecisionTurn> turns() {
        return Collections.unmodifiableList(turns);
    }

    /** The tools of the palette, by name, in palette order. */
    public List<String> paletteNames() {
        return palette.stream().map(ClassToolProvider::name).toList();
    }

    @Override
    public ListArtifact<O> execute(JobContext<ListArtifact<O>> context) throws LLMReadableCheckedException {
        if (input == null) {
            throw new UncorrectableRuntimeLLMException("A decision thinker starts from its input artifact and has none");
        }
        ArtifactRegistry registry = new ArtifactRegistry();
        // the world in the order it came to be: the registry holds custody, this holds the order
        // the model reads and chooses in, which a decision model is sensitive to and a run's
        // record must repeat, so it is never a hash order
        LinkedHashMap<String, Artifact> known = new LinkedHashMap<>();
        adopt(registry, known, input);
        Set<String> applied = new HashSet<>();
        List<String> history = new ArrayList<>();
        turns.clear();
        for (int turn = 1; turn <= maxTurns; turn++) {
            context.checkCancellation();
            Map<ClassToolProvider, List<String>> moves = legalMoves(known, applied);
            List<String> outputs = outputCandidates(known);
            if (moves.isEmpty() && outputs.isEmpty()) {
                log.info("{}: no move left and nothing of type {} produced after {} turns; the answer is empty",
                        getId(), outputType.getSimpleName(), turn - 1);
                turns.add(new DecisionTurn(turn, null, DecisionTurn.FINISH, null, null, null, null, Map.of()));
                publishTurns(context);
                return finish(registry, List.of());
            }
            DecisionRequest request = request(known, moves, outputs, history, moves.isEmpty());
            DecisionResponse decision = await(submitInIteration(turn, newDecisionCall(request)));
            Map<String, Double> selection = selection(decision, outputs);
            ChoiceAnswer next = moves.isEmpty() ? null : decision.choice("next");
            String chosen = next == null ? DecisionTurn.FINISH : next.choice();
            if (DecisionTurn.FINISH.equals(chosen)) {
                turns.add(new DecisionTurn(turn, next, DecisionTurn.FINISH, null, null, null, null, selection));
                publishTurns(context);
                return finish(registry, selected(selection, known));
            }
            ClassToolProvider tool = byName(chosen);
            ChoiceAnswer argument = decision.choice(argumentId(tool));
            String ref = argument.choice();
            applied.add(moveKey(tool, ref));
            String resultRef = null;
            String failure = null;
            try {
                Artifact produced = run(tool, known.get(ArtifactRegistry.normalizeToKey(ref)), turn);
                resultRef = adopt(registry, known, produced);
                history.add(tool.name() + " on " + ref + " produced " + resultRef);
            }
            catch (ExecutionException e) {
                // A tool that could not do its work is a fact of the run the model reads next turn,
                // as the thinkers feed a failed tool call back. A correctable failure is the tool's
                // own verdict on its input and is logged as such; an uncorrectable one is a real
                // failure and keeps its stack in the log. Anything unreadable is a bug and propagates.
                LLMReadableException readable = readable(e);
                if (readable == null) {
                    throw LLMReadableCheckedException.unwrap(e);
                }
                failure = readable.getLLMMessage();
                history.add(tool.name() + " on " + ref + " failed: " + failure);
                if (readable.isCorrectable()) {
                    log.warn("{}: {} on {} failed: {}", getId(), tool.name(), ref, failure);
                }
                else {
                    log.error("{}: {} on {} failed: {}", getId(), tool.name(), ref, failure, e);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncorrectableRuntimeLLMException("Interrupted while " + tool.name() + " ran on " + ref, e);
            }
            turns.add(new DecisionTurn(turn, next, tool.name(), argument, ref, resultRef, failure, selection));
            context.publishUserProgress("Deciding", "turn " + turn + ": " + tool.name() + " on " + ref
                    + (failure != null ? " failed" : ""), Math.min(99, turn * 100 / maxTurns));
            publishTurns(context);
        }
        // the belt: the budget is spent, so the answer is what the model would select now
        List<String> outputs = outputCandidates(known);
        Map<String, Double> selection = Map.of();
        if (!outputs.isEmpty()) {
            DecisionResponse decision = await(submitInIteration(maxTurns + 1,
                    newDecisionCall(request(known, Map.of(), outputs, history, true))));
            selection = selection(decision, outputs);
        }
        turns.add(new DecisionTurn(maxTurns + 1, null, DecisionTurn.FINISH, null, null, null, null, selection));
        publishTurns(context);
        log.info("{}: the turn budget of {} is spent; finishing with what the model selects now", getId(), maxTurns);
        return finish(registry, selected(selection, known));
    }

    /**
     * Every decision the run asks: the thinker hands its own upstream-retry budget to each
     * call, as any thinker does to every model call it makes.
     */
    private DecisionCall newDecisionCall(DecisionRequest request) {
        DecisionCall call = new DecisionCall(this, request);
        call.setUpstreamRetries(getUpstreamRetries());
        return call;
    }

    /**
     * Takes an artifact into the run: the registry gets custody (a ref minted, everything
     * reachable indexed), and the world gets the artifact and, for a list, its iterands in
     * their order, recursively, each once. Returns the artifact's ref.
     */
    private static String adopt(ArtifactRegistry registry, LinkedHashMap<String, Artifact> known, Artifact artifact) {
        String ref = registry.register(artifact);
        known.putIfAbsent(ArtifactRegistry.normalizeToKey(ref), artifact);
        if (artifact instanceof ListArtifact<?> list && list.getIterands() != null) {
            for (Artifact iterand : list.getIterands()) {
                known.putIfAbsent(ArtifactRegistry.normalizeToKey(iterand.getArtifactRef()), iterand);
                if (iterand instanceof ListArtifact<?> nested) {
                    adopt(registry, known, nested);
                }
            }
        }
        return ref;
    }

    /** Every (tool, artifact) pair that can run now: the artifact's type fits the tool's input and the pair has not run. */
    private Map<ClassToolProvider, List<String>> legalMoves(Map<String, Artifact> known, Set<String> applied) {
        Map<ClassToolProvider, List<String>> moves = new LinkedHashMap<>();
        for (ClassToolProvider tool : palette) {
            List<String> candidates = new ArrayList<>();
            for (Artifact artifact : known.values()) {
                if (tool.inputType().isInstance(artifact) && !(artifact instanceof ListArtifact<?> && !ListArtifact.class.isAssignableFrom(tool.inputType()))
                        && !applied.contains(moveKey(tool, artifact.getArtifactRef()))) {
                    candidates.add(artifact.getArtifactRef());
                }
            }
            if (!candidates.isEmpty()) {
                moves.put(tool, candidates);
            }
        }
        return moves;
    }

    /** The artifacts of the output type a tool produced; the input is never an answer, whatever its type. */
    private List<String> outputCandidates(Map<String, Artifact> known) {
        List<String> outputs = new ArrayList<>();
        for (Artifact artifact : known.values()) {
            if (outputType.isInstance(artifact) && artifact != input) {
                outputs.add(artifact.getArtifactRef());
            }
        }
        return outputs;
    }

    /**
     * The turn's request: the state is the objective, the registry digest and the history;
     * the questions are the next move, each offered tool's argument, and the selection of
     * every artifact of the output type. When no move is left the next-move question is not
     * asked and the selection alone decides the answer.
     */
    private DecisionRequest request(Map<String, Artifact> known, Map<ClassToolProvider, List<String>> moves, List<String> outputs,
                                    List<String> history, boolean onlySelection) {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("objective", objective);
        List<String> artifacts = new ArrayList<>(known.size());
        for (Artifact artifact : known.values()) {
            artifacts.add(digest(artifact));
        }
        state.put("artifacts", artifacts);
        state.put("done", history.isEmpty() ? List.of("nothing yet") : List.copyOf(history));
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        if (!onlySelection) {
            LinkedHashMap<String, String> next = new LinkedHashMap<>();
            for (Map.Entry<ClassToolProvider, List<String>> move : moves.entrySet()) {
                next.put(move.getKey().name(), move.getKey().description() + " (" + move.getValue().size() + " artifact"
                        + (move.getValue().size() == 1 ? "" : "s") + " it could take)");
            }
            if (!outputs.isEmpty()) {
                next.put(DecisionTurn.FINISH, "Finish: the artifacts already produced answer the objective, nothing more needs to run");
            }
            questions.put("next", new Choice("Which tool should run next toward the objective, or should the run finish?"
                    + " A tool already run on an artifact is not offered for it again.", next));
            for (Map.Entry<ClassToolProvider, List<String>> move : moves.entrySet()) {
                LinkedHashMap<String, String> candidates = new LinkedHashMap<>();
                for (String ref : move.getValue()) {
                    candidates.put(ref, digest(known.get(ArtifactRegistry.normalizeToKey(ref))));
                }
                questions.put(argumentId(move.getKey()), new Choice("If " + move.getKey().name()
                        + " runs next, which artifact should it take as its input?", candidates));
            }
        }
        for (String ref : outputs) {
            questions.put(selectionId(ref), Noul.of("Does " + ref + " belong in the answer to the objective?"));
        }
        return new DecisionRequest(state, questions);
    }

    /**
     * One line a model reads: the ref, the type, and the artifact's own words cut to the digest
     * length. The words are a list's count and iterand type, else what the artifact type's
     * registered {@link ArtifactTextFormatter} says (an application registers one per type it
     * puts before a decision model, since the digest is all the model sees of an artifact),
     * else a link's title, else the artifact's summarized JSON.
     */
    static String digest(Artifact artifact) {
        String alias = ArtifactRegistry.extractAliasFromRef(artifact.getArtifactRef());
        String words;
        if (artifact instanceof ListArtifact<?> list) {
            words = "a list of " + (list.getIterands() == null ? 0 : list.getIterands().size()) + " " + list.getIterandTypeAlias();
        }
        else if (TextFormatterRegistry.find(artifact.getClass()) != null) {
            words = TextFormatterRegistry.format(artifact).replaceAll("\\s+", " ").strip();
        }
        else if (artifact instanceof LinkArtifact link && link.getTitle() != null) {
            words = link.getTitle();
        }
        else {
            words = NucleoJsonSerializer.writeSummarizedCompact(artifact);
        }
        if (words.length() > DIGEST_CHARS) {
            words = words.substring(0, DIGEST_CHARS) + "...";
        }
        return artifact.getArtifactRef() + " (" + alias + "): " + words;
    }

    private static String argumentId(ClassToolProvider tool) {
        return tool.name() + "_input";
    }

    private static String selectionId(String ref) {
        return "select_" + ArtifactRegistry.normalizeToKey(ref);
    }

    private static String moveKey(ClassToolProvider tool, String ref) {
        return tool.name() + " " + ArtifactRegistry.normalizeToKey(ref);
    }

    private ClassToolProvider byName(String name) {
        for (ClassToolProvider tool : palette) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        throw new UncorrectableRuntimeLLMException("The decision named tool '" + name + "', which is not in the palette " + paletteNames());
    }

    private Map<String, Double> selection(DecisionResponse decision, List<String> outputs) {
        LinkedHashMap<String, Double> selection = new LinkedHashMap<>();
        for (String ref : outputs) {
            selection.put(ref, decision.noul(selectionId(ref)).probability());
        }
        return selection;
    }

    private List<Artifact> selected(Map<String, Double> selection, Map<String, Artifact> known) {
        List<Artifact> selected = new ArrayList<>();
        for (Map.Entry<String, Double> entry : selection.entrySet()) {
            if (entry.getValue() >= selectionThreshold) {
                selected.add(known.get(ArtifactRegistry.normalizeToKey(entry.getKey())));
            }
        }
        return selected;
    }

    /** The first LLM-readable failure in the chain, the way the thinkers find a tool's own verdict; null when there is none. */
    private static LLMReadableException readable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LLMReadableException readable) {
                return readable;
            }
        }
        return null;
    }

    /**
     * Waits on a job of this run. A failure that the model can read stays readable, checked or
     * unchecked as it was thrown; anything else surfaces as the system failure it is.
     */
    private static <R> R await(JobHandle<R> handle) throws LLMReadableCheckedException {
        try {
            return handle.get();
        }
        catch (ExecutionException e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncorrectableRuntimeLLMException("Interrupted while waiting on " + handle, e);
        }
    }

    /**
     * Runs the tool on the artifact as a job of this turn and returns what it produced, an
     * artifact by the palette contract. The job's failure comes out as thrown, for the loop to classify.
     */
    @SuppressWarnings("unchecked")
    private Artifact run(ClassToolProvider provider, Artifact argument, int turn) throws LLMReadableCheckedException, ExecutionException, InterruptedException {
        Tool<Object, Object> tool = (Tool<Object, Object>) provider.create(this);
        tool.setInput(argument);
        Object produced = submitInIteration(turn, tool).get();
        if (!(produced instanceof Artifact artifact)) {
            throw new UncorrectableRuntimeLLMException("Tool " + provider.name() + " produced "
                    + (produced == null ? "nothing" : produced.getClass().getSimpleName()) + " in place of the artifact its type declares");
        }
        return artifact;
    }

    @SuppressWarnings("unchecked")
    private ListArtifact<O> finish(ArtifactRegistry registry, List<Artifact> selected) {
        ListArtifact<O> answer = new ListArtifact<>();
        List<O> iterands = new ArrayList<>(selected.size());
        for (Artifact artifact : selected) {
            iterands.add((O) artifact);
        }
        answer.setIterands(iterands);
        answer.setIterandTypeAlias(TypeAliasRegistry.getAlias(outputType));
        answer.setInstruction(objective);
        registry.register(answer);
        return answer;
    }

    private void publishTurns(JobContext<?> context) {
        context.putMetadata(OBS_TURNS, NucleoJsonSerializer.writeCompact(turns));
    }
}
