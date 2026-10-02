/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.function.*;

/**
 * A tool whose work is one exchange with a model. It declares a grade, or is pinned to an
 * entry, and it wires one conversation: the input rendered as the prompt, the response
 * contract from the output POJO, the seat's depth and answer size declared on it. The shape:
 * in {@code getRequirements}, {@link #wireConversation}; in {@code execute},
 * {@link #converse}, wrapped in {@code catch (Exception e) { throw LLMReadableCheckedException.unwrap(e); }}.
 * {@code QuickLLMQuestionTool} is the reference.
 *
 * <p>The grade arrives in the constructor, or later through {@link #setGrade} for a tool
 * whose grade is its input's or its parent's; the wiring reads it at requirements time. The
 * conversation is built once and kept across dispatcher attempts, which is what makes the
 * truncation escalation reach a retry: the client bumps the SENT message's budget to the
 * model ceiling, and the re-run sends that same message. The binding is re-minted per attempt
 * and prices exactly the conversation the wire sends, input under the resolved tokenizer,
 * output from the declaration, thinking from the spec and depth, so reservation and
 * {@code max_tokens} cannot drift.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public abstract class AbstractModelDependentTool<I, O> extends AbstractTool<I, O> implements ModelDependentTool<I, O> {
    private Grade grade;
    private ModelSpec pinned;
    private ConversationContext conversation;
    /** The correction protocol and its budget for the wired conversation; survives dispatcher re-runs of this same instance. */
    private final ResponseCorrection correction = new ResponseCorrection();

    /** A tool that learns its grade later, from its input or from the thinker that offers it. */
    protected AbstractModelDependentTool(Identifiable parent) {
        super(parent);
    }

    /** A tool whose grade is its own decision. */
    protected AbstractModelDependentTool(Identifiable parent, Grade grade) {
        super(parent);
        this.grade = grade;
    }

    /** With a job id prefix of its own. */
    protected AbstractModelDependentTool(Identifiable parent, String idPrefix, Grade grade) {
        super(parent, idPrefix);
        this.grade = grade;
    }

    @Override
    public Grade getGrade() {
        return grade;
    }

    @Override
    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    @Override
    public void pinModel(ModelSpec model) {
        this.pinned = model;
    }

    @Override
    public ModelSpec pinnedModel() {
        return pinned;
    }

    /**
     * Builds this tool's conversation once and wires a fresh binding onto it for this
     * attempt, from the pinned entry or the declared grade. Called from {@code getRequirements},
     * which the dispatcher runs at submit and again per attempt.
     *
     * @param output how much answer the call books: a rung sized to the output POJO, or a
     *               count where the tool's own instruction fixes the answer precisely
     */
    protected <T> ModelBinding wireConversation(JobRequirements req, Depth depth, OutputDeclaration output,
                                                Class<T> outputType, String additionalInstructions) {
        if (grade == null && pinned == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " declares no grade: pass it to the constructor,"
                    + " or setGrade before the requirements are captured");
        }
        if (conversation == null) {
            ConversationContext built = new ConversationContext();
            built.setGrade(pinned != null ? pinned.getGrade() : grade);
            built.setDepth(depth);
            built.setOutputDeclaration(output);
            OutgoingMessage<T> message = new OutgoingMessage<>(new PojoResponseHandler<>(outputType));
            message.setRole("user");
            message.setTimestamp(java.time.Instant.now());
            message.addText(buildInputPrompt(additionalInstructions));
            built.getMessages().add(message);
            conversation = built;
        }
        ModelBinding binding = requireSeat(req, depth);
        conversation.setModelBinding(binding);
        return binding;
    }

    /**
     * Wires a conversation the tool builds itself - one carrying images or files, or any
     * shape the rendered input prompt cannot express - built once through the supplier on
     * the first call and given a fresh binding per attempt, exactly as the prompt form is.
     * The tool then converses through {@link #converse}, so its answer is corrected the
     * same way; a tool that sends its own conversation on the client instead loses that.
     */
    protected ModelBinding wireConversation(JobRequirements req, Depth depth, Supplier<ConversationContext> builder) {
        if (conversation == null) {
            ConversationContext built = builder.get();
            if (built == null) {
                throw new IllegalStateException(getClass().getSimpleName() + " built no conversation to wire");
            }
            conversation = built;
        }
        ModelBinding binding = requireSeat(req, depth);
        conversation.setModelBinding(binding);
        return binding;
    }

    /** The request for the conversation {@link #wireConversation} built; execute sends it on the client resolved for its binding. */
    protected <T> LLMRequest<T> request() {
        return new LLMRequest<>(wired());
    }

    /**
     * Sends the wired conversation on the client resolved for its binding and returns the
     * typed answer, correcting the model the way {@code LLMCall} does: an answer that does
     * not parse, or parses without its required fields, goes back to the model as a
     * correction turn and the dispatcher re-runs this same instance, up to
     * {@link ResponseCorrection#MAX_CORRECTIONS} times. The retry signals this throws are
     * the dispatcher's; a tool that wraps them in a catch loses the correction, which is why
     * {@link LLMReadableCheckedException#unwrap} lets them through.
     */
    protected <T> T converse(JobResources resources) throws LLMReadableCheckedException {
        return correction.exchange(client(resources), wired());
    }

    /** The client for the wired conversation's resolved model. A test overrides it to answer without a transport. */
    protected LLMClient client(JobResources resources) {
        return resources.getLLMClient(wired().getModel());
    }

    private ConversationContext wired() {
        if (conversation == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " has no wired conversation - getRequirements"
                    + " wires it through wireConversation before execute runs");
        }
        return conversation;
    }

    /**
     * Builds the prompt text that {@link #wireConversation} sends to the LLM: the input
     * serialized as context, then the tool's instructions.
     */
    protected String buildInputPrompt(String additionalInstructions) {
        StringBuilder prompt = new StringBuilder();
        if (input != null) {
            prompt.append("Input data:\n");
            prompt.append(NucleoJsonSerializer.write(input)).append("\n\n");
        }
        if (additionalInstructions != null && !additionalInstructions.isEmpty()) {
            prompt.append(additionalInstructions);
        }
        return prompt.toString();
    }
}
