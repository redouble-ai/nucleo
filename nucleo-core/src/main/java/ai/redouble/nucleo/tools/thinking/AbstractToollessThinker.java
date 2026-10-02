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
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.slf4j.*;

import java.util.*;

/**
* A thinker with no tools is one LLM call wearing a loop's clothes - and the
* clothes are what it pays for: tool-use instructions for tools that do not
* exist, the ThinkingResponse envelope with its final-answer and artifact
* plumbing, and a prompt cache written on every one-shot conversation and never
* read back. Every one of those costs belongs to the looping thinker's own turn,
* not to what a thinker IS, so this seat drops the loop and keeps the rest: the
* answer schema is the output POJO's own (no envelope), main-objective caching is
* off, and the whole exchange is one {@link LLMCall} per candidate answer.
* <p>
* A thinker in the type system as well as the name: the declaration and its
* compile-enforced grade and answer size, the read-only variant, the conversation
* obtained through {@link ConversationService} with its artifact registry,
* summarizer and seeded-artifact custody, the retain policy, and the shared
* validation and correction seats all arrive by inheritance. Guardrail refusals
* return to the model as correction turns, bounded by
* {@link #MAX_GUARDRAIL_CORRECTIONS}; parse and required-field corrections are
* {@link LLMCall}'s own and ride inside each call.
* <p>
* Tools are refused rather than ignored: this seat renders no tool definitions, so
* a tool handed to it could never be offered to the model, and accepting one
* silently would promise a capability that does not exist.
*
* @param <I> the typed input, same bound as every thinker
* @param <O> the answer POJO, same bound as every thinker - the response schema
*            is THIS class verbatim, so a lean seat picks a lean Reasoning type
 * @author Andrey Santrosyan
* @since 0.1 (2026-09-04)
*/
public abstract class AbstractToollessThinker<I extends ThinkerInput, O extends ThinkerOutput<? extends Reasoning>> extends AbstractThinker<I, O> {
    private static final Logger log = LoggerFactory.getLogger(AbstractToollessThinker.class);
    /** Guardrail-refusal correction turns per execution; exhaustion surfaces the last refusal. */
    public static final int MAX_GUARDRAIL_CORRECTIONS = 2;
    protected static final String TASK_OBJECTIVE_KEY = "task";
    private final Class<O> outputClass;
    private Depth depth;
    private O result;

    /**
     * @param declaration the seat's grade and answer size, required as on every thinker; the
     *                    answer rung is sized to the output POJO, or a raw count for the seat
     *                    that fits no rung
     * @param outputClass the answer POJO, whose own schema is the response contract
     */
    protected AbstractToollessThinker(Identifiable parent, ThinkerDeclaration declaration, Class<O> outputClass) {
        super(parent, declaration);
        if (outputClass == null) {
            throw new IllegalArgumentException("outputClass is required");
        }
        this.outputClass = outputClass;
    }

    /** The read-only variant, for a seat whose answer may not come from mutating anything. */
    protected AbstractToollessThinker(Identifiable parent, ThinkerDeclaration declaration, Class<O> outputClass, boolean forceReadOnly) {
        super(parent, declaration, forceReadOnly);
        if (outputClass == null) {
            throw new IllegalArgumentException("outputClass is required");
        }
        this.outputClass = outputClass;
    }

    /**
     * Thinking depth for the call. Null means the input's own depth, which {@link ThinkerInput}
     * initializes to STANDARD; set this to override what the caller put on the input.
     */
    public void setDepth(Depth depth) {
        this.depth = depth;
    }

    @Override
    public Depth getDepth() {
        if (depth != null) {
            return depth;
        }
        return getInput() != null ? getInput().getDepth() : null;
    }

    /**
     * Simple-case override: the static system-prompt text, annotated
     * {@code @StaticPrompt}. Same contract as the looping thinkers.
     */
    protected abstract String getSystemPromptText();

    /** First-class Prompt; override only for dynamic composition, as on the looping thinkers. */
    protected Prompt getSystemPrompt() throws LLMReadableCheckedException {
        return Prompts.bindStaticDefault(getClass().getName(), getSystemPromptText());
    }

    /** No tools, so none are declared by default. */
    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of();
    }

    /**
     * Refused: this seat renders no tool definitions, so a registered tool would never reach
     * the model. A caller that wants tools wants a looping thinker.
     */
    @Override
    public void addTool(Class<? extends Tool> toolClass) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is tool-less; use a looping thinker to offer tools");
    }

    @Override
    public void addTool(ToolProvider provider) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is tool-less; use a looping thinker to offer tools");
    }

    @Override
    protected O getResult() {
        return result;
    }

    /**
     * One call, then the validation seat, then either the answer or a correction turn. The
     * conversation arrives from the service with its grade, depth and answer size already
     * stamped, so this seat sets only what is its own: the objective, the answer turn, and the
     * decision not to cache a main objective a single call never reads back.
     */
    @Override
    protected void runThinkingLoop(ConversationContext conversation, JobContext<O> context) throws LLMReadableCheckedException {
        if (getInput() == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " has no input; a tool-less thinker's"
                    + " prompt renders its input and its depth comes from it");
        }
        conversation.setCacheMainObjective(false);
        ThinkerObjective objective = new ThinkerObjective();
        objective.setPrompt(getSystemPrompt());
        objective.setInput(getInput());
        conversation.putMainObjective(TASK_OBJECTIVE_KEY, objective);
        appendAnswerTurn(conversation, "Answer now with the final JSON.");
        int corrections = 0;
        while (true) {
            O answer;
            try {
                answer = submitInIteration(corrections, this.<O>newLLMCall(this, conversation)).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
            try {
                GuardrailEnforcer.enforceValidation(declareValidationGuardrails(), answer, context);
                result = answer;
                return;
            }
            catch (GuardrailException refusal) {
                corrections++;
                if (corrections > MAX_GUARDRAIL_CORRECTIONS) {
                    throw refusal;
                }
                log.warn("{} answer refused by validation guardrail (correction {}/{}): {}",
                        getClass().getSimpleName(),
                        corrections,
                        MAX_GUARDRAIL_CORRECTIONS,
                        refusal.getLLMMessage());
                appendAnswerTurn(conversation, refusal.explainToLLM());
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** A user turn carrying the output POJO's own schema as the response contract. */
    private void appendAnswerTurn(ConversationContext conversation, String text) {
        OutgoingMessage<O> turn = new OutgoingMessage<>(new PojoResponseHandler<>(outputClass));
        turn.setRole("user");
        turn.addText(text);
        conversation.getMessages().add(turn);
    }
}
