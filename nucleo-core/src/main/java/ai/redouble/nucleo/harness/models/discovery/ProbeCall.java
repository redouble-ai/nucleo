/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * One probe call on one catalog entry, pinned to it: the ping that asks a model for one word
 * ({@link CatalogPing}) and the classification call that asks the strongest model for a new
 * entry's shape ({@link ModelClassifier}). A model-dependent tool like every exchange with a
 * model in the runtime, with what is a probe's own: no transparent retries, because the
 * question is "does this succeed right now" and a retry would answer a different question
 * later, so an upstream signal fails the call at once and the discovery classifies it; a hard
 * cap, because a call that cannot answer in time IS the answer; and a client built straight
 * from the entry's provider for this call and closed after it, because the entry under probe
 * may be new or rewritten and the runtime's shared client cache is keyed by id. A utility job
 * that asks for no transaction: it touches no database.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
final class ProbeCall extends AbstractModelDependentTool<Void, String> {
    private final ClientProvider<?> provider;
    private final Depth depth;
    private final int outputBudget;
    private final String prompt;
    private LLMClient client;

    /**
     * @param parent       the discovery run the probe belongs to
     * @param provider     the entry's provider, which builds the client for this call
     * @param spec         the entry under probe; the call is pinned to it
     * @param depth        the depth the call runs at
     * @param outputBudget the answer's {@code max_tokens}
     * @param prompt       the one message the call sends
     * @param cap          the hard cap on the whole call
     */
    ProbeCall(Identifiable parent, ClientProvider<?> provider, ModelSpec spec, Depth depth, int outputBudget, String prompt, Duration cap) {
        super(parent, "ProbeCall");
        pinModel(spec);
        setUpstreamRetries(0);
        setTimeout(cap);
        this.provider = provider;
        this.depth = depth;
        this.outputBudget = outputBudget;
        this.prompt = prompt;
    }

    @Override
    public JobType getJobType() {
        return JobType.UTILITY;
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        wireConversation(req, depth, this::buildConversation);
        req.setRequiresTransaction(false);
        return req;
    }

    private ConversationContext buildConversation() {
        ConversationContext built = new ConversationContext();
        built.setGrade(pinnedModel().getGrade());
        built.setDepth(depth);
        built.setOutputDeclaration(OutputDeclaration.of(outputBudget));
        built.addText(prompt);
        built.setResponseHandler(StringResponseHandler.instance);
        return built;
    }

    /**
     * A client of this call's own, straight from the provider of the entry under probe. A
     * provider that builds something other than a chat client for the entry is refused by name:
     * the entry is on a key whose client cannot converse.
     */
    @Override
    protected LLMClient client(JobResources resources) {
        ModelSpec spec = pinnedModel();
        Client built = provider.createClient(spec);
        if (!(built instanceof LLMClient llm)) {
            throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                    + "' built " + built.getClass().getSimpleName() + " for LLM entry " + spec.getId());
        }
        client = llm;
        return llm;
    }

    @Override
    public String execute(JobResources resources, JobContext<String> context) throws LLMReadableCheckedException {
        try {
            return converse(resources);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
        finally {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    /** The provider's envelope of the probe's response: served model, request id, headers, usage, rate limits. */
    LLMResponse<?> response() {
        return lastResponse();
    }
}
