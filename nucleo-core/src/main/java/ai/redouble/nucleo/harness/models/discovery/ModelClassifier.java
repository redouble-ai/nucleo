/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The discovery's own judgment call: an account listed models the catalog has no entry for
 * and no ancestor to inherit a shape from, and classifying them is exactly what the runtime's
 * strongest model is for. One call per listing, all in parallel: each carries the task, the
 * existing entries as the reference for shapes and the grade ladder in use, its platform's
 * other unclassified listings for family context, and the one model it classifies; the
 * answer is that model's proposed entry in the catalog's own schema, or none, so a call
 * that fails costs exactly its own model. Guardrails stay deterministic and are the
 * caller's ({@code CatalogDiscovery}): a proposal may only name a listed model of its own
 * provider ({@link #rejection}), every accepted entry is pinged live before it is kept, and
 * each carries a note naming the classifier so a person knows which facts to verify -
 * classification is judgment on the record, never invisible invention.
 *
 * <p>The transport is {@code CatalogPing}'s, because the discovery runs before a catalog, a
 * picker or a dispatcher exists: a client straight from the provider, one call, a hard cap.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class ModelClassifier {
    static final String INSTRUCTIONS = """
            An account listed a model its catalog has no entry for. Decide whether it belongs, and \
            write its entry when it does.

            An entry states what a runtime must know to serve a model: identity and family, the Grade \
            of work it belongs to, context and output ceilings, whether it sees images, its thinking \
            mode, its published prices per million tokens, and its provider's default rate limits. The \
            listing carries none of that; you do, from public knowledge of these models. The Grade \
            ladder is a capability ladder, and a model's rung is decided by its vendor's own tier and \
            its generation, never by its price: MICRO is the vendor's nano, micro and lite tier and \
            open-weight models under 10B parameters; SMALL is the mini, small, haiku, flash and luna \
            tier and 10B to 35B; MEDIUM is the medium, sonnet, pro and terra tier and 35B to 150B; LARGE is the \
            large tier, models above 150B, and any flagship one generation behind the vendor's \
            current one; XL is the vendor's current flagship; MEGA is above the flagship. For a \
            mixture-of-experts model the active parameters per token count, never the total, and \
            the vendor's stated peer decides: a model the vendor says matches a mini-tier model is \
            SMALL. Each newer generation pushes a former flagship down one rung. An expensive old \
            model is an old model: price never promotes. A tier word in the listed name decides by \
            itself, and the run applies it; for the rest, match the reference entries' judgment: a \
            model comparable to one already graded belongs on its rung.

            You classify ONE model, named at the end. Its platform's other unclassified listings \
            follow it for family context only - classify none of them. Answer with JSON only, no \
            prose and no code fences: {"entries": [<the one entry>]} to add it, {"entries": []} to \
            leave it on the person's list. Whether it belongs is judged per family against the \
            reference entries of the same provider platform (the reference's provider_key values \
            tell you which keys share one), and deciding the family is your judgment where names \
            alone do not say (an o-series next to a GPT-series, a deployment alias, a codename):
            - Its family is never named in the reference on that platform: add it ONLY when it is \
            the newest listed version of that family among the context listings - the back-catalog \
            behind the newest stays out.
            - The reference names its family in an older version: add it when it is NEWER than the \
            reference's newest, leave it when older.
            - The family's newest reference entry on that platform carries "status": "DISABLED": add \
            it WITH "status": "DISABLED" - the deployment turned the family off deliberately, and a \
            new version does not undo a deliberate choice.
            - A version or codename newer than your knowledge, inside a family you recognize, is \
            exactly what this run must not drop: add it, inheriting ceilings, vision, thinking mode \
            and prices from the newest family member you know, graded no lower than that ancestor. \
            Leave a model out only when you cannot even place its family or kind.

            The entry, when you write one, spells its fields exactly the way the reference entries do:
            - wire_model_id: the named model's listed id, verbatim. Never another, never invented.
            - provider_key: the key whose client actually serves that model's family, chosen the way \
            the reference entries choose between a platform's keys (on Bedrock, Claude rides the \
            anthropic keys and every other vendor rides the converse key; embeddings ride the \
            embeddings key) - a model listed under one key may belong on a sibling key.
            - id: short kebab-case, unique against the reference, suffixed the way the reference \
            suffixes that provider's channel.
            - A deployment or alias of a base model you recognize (the listing's note may name it) \
            copies the base model's shape: grade, ceilings, vision, thinking mode, and the spec_type \
            the reference entries of comparable models carry.
            - Prices: the provider's published list prices where you know them, the platform's price \
            for the base model otherwise. A price is a number in a currency: state currency (for \
            example "USD"). Limits (tpm, rpm): the provider's published defaults, modest entry-tier \
            values when unpublished.
            - An embeddings model gets embedding_dimensions and no grade.
            - A decision model (a provider key ending in -decision: it answers typed questions with \
            probabilities and generates nothing) gets no grade, max_output_tokens 0, an input price and \
            no output price, and exactly one bound: tpm and rpm for a hosted endpoint, max_concurrent \
            for a server on the deployment's own machine; copy the reference entry of its provider.
            The entry is pinged live and carries a note telling a person what to verify.""";
    /** One model per call: the answer is one small entry, so no call takes long, none truncates, and one failing costs one model. */
    static final int OUTPUT_BUDGET = 2_000;
    static final Duration CAP = Duration.ofMinutes(6);

    private ModelClassifier() {}

    /** The one classification call, on {@code CatalogPing}'s transport, capped like the judge is. */
    static String call(ClientProvider<?> provider, ModelSpec spec, String prompt) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> answer = executor.submit(() -> {
                Client client = provider.createClient(spec);
                try {
                    if (!(client instanceof LLMClient llm)) {
                        throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                                + "' built " + client.getClass().getSimpleName() + " for LLM entry " + spec.getId());
                    }
                    LLMResponse<String> response = llm.singleResponse(new LLMRequest<>(conversation(llm, spec, prompt)));
                    if (!response.isSuccessful()) {
                        throw new UncorrectableRuntimeLLMException("The classification call on " + spec.getId()
                                + " failed: " + response.getReasonForFailure());
                    }
                    return response.getResponseMessage().getResponse();
                }
                finally {
                    if (client instanceof LLMClient llm) {
                        llm.close();
                    }
                }
            });
            try {
                return answer.get(CAP.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e) {
                answer.cancel(true);
                throw new UncorrectableRuntimeLLMException("No classification from " + spec.getId()
                        + " within " + CAP.toMinutes() + " minutes");
            }
            catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
    }

    private static ConversationContext conversation(LLMClient client, ModelSpec spec, String prompt) {
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(spec));
        // STANDARD depth, deliberately: with thinking suppressed the model deliberates in
        // prose inside the answer channel and truncates the budget; at STANDARD the judgment
        // rides the thinking channel and the answer stays tight JSON. The wall time is the
        // per-listing fan-out's job, not this knob's.
        conversation.setDepth(Depth.STANDARD);
        conversation.setOutputDeclaration(OutputDeclaration.of(OUTPUT_BUDGET));
        OutgoingMessage<String> message = client.createOutgoingMessage(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(Instant.now());
        message.addText(prompt);
        conversation.getMessages().add(message);
        return conversation;
    }

    /**
     * The catalog as the reference for shapes and grading, built once per run and shared by
     * every call's prompt. It travels without each entry's {@code note}: notes are prose for
     * a person (provenance, verify reminders), thousands of tokens across a grown catalog, and
     * say nothing about shapes or grading - a person is waiting behind these calls.
     */
    static String reference(Map<String, ObjectNode> entries) {
        ArrayNode reference = NucleoJsonSerializer.createArrayNode();
        for (ObjectNode entry : entries.values()) {
            ObjectNode compact = entry.deepCopy();
            compact.remove("note");
            reference.add(compact);
        }
        return NucleoJsonSerializer.write(reference);
    }

    /**
     * The task, the shared reference, the platform's other unclassified listings as family
     * context (the newest-only rule judges a family by seeing its versions side by side), and
     * the ONE model this call classifies - last, so every call shares its prefix.
     */
    static String prompt(String reference, Map.Entry<String, DiscoveredModel> listing,
                         List<Map.Entry<String, DiscoveredModel>> platformListings) {
        StringBuilder sb = new StringBuilder(INSTRUCTIONS);
        sb.append("\n\nThe catalog's existing entries, the reference for field spelling, shapes, families and grading:\n");
        sb.append(reference);
        sb.append("\n\nThe platform's other unclassified listings, family context only, one per line as provider_key then wire_model_id:\n");
        for (Map.Entry<String, DiscoveredModel> context : platformListings) {
            if (context == listing) {
                continue;
            }
            sb.append(context.getKey()).append("  ").append(context.getValue().wireModelId());
            if (context.getValue().note() != null) {
                sb.append("  (").append(context.getValue().note()).append(')');
            }
            sb.append('\n');
        }
        sb.append("\nThe model to classify, as provider_key then wire_model_id:\n");
        sb.append(listing.getKey()).append("  ").append(listing.getValue().wireModelId());
        if (listing.getValue().note() != null) {
            sb.append("  (").append(listing.getValue().note()).append(')');
        }
        sb.append('\n');
        return sb.toString();
    }

    /** The proposals out of the answer: the {@code entries} array, tolerant of the fences models add despite instructions. */
    static List<ObjectNode> parse(String response) throws IOException {
        String body = response.strip();
        if (body.startsWith("```")) {
            body = body.substring(body.indexOf('\n') + 1);
            int fence = body.lastIndexOf("```");
            if (fence >= 0) {
                body = body.substring(0, fence);
            }
        }
        JsonNode root = NucleoJsonSerializer.readTree(body);
        JsonNode array = root.isArray() ? root : root.get("entries");
        if (array == null || !array.isArray()) {
            throw new UncorrectableRuntimeLLMException("The classification answered without an 'entries' array");
        }
        List<ObjectNode> proposals = new ArrayList<>();
        for (JsonNode node : array) {
            if (node instanceof ObjectNode proposal) {
                proposals.add(proposal);
            }
        }
        return proposals;
    }

    /**
     * Why a proposal is refused, or null when it stands. The deterministic gate around the
     * judgment: only a model actually listed on the proposal's own platform (the keys of one
     * platform list each other's vendors), only on a key whose client speaks the model's family
     * ({@link ClientProvider#serves}: the right key for a family is the classifier's call within
     * that), only fields an entry needs, never an id the catalog already has - a classifier
     * suggests, it does not get to invent.
     */
    static String rejection(ObjectNode proposal, Map<String, List<DiscoveredModel>> unknown,
                            Map<String, ObjectNode> entries, Map<String, ClientProvider<?>> providers) {
        for (String required : List.of("id", "identity", "provider_key", "wire_model_id", "max_context_tokens", "max_output_tokens")) {
            if (!proposal.hasNonNull(required)) {
                return "missing " + required;
            }
        }
        boolean decision = proposal.get("provider_key").asText().endsWith("-decision");
        if (decision) {
            if (proposal.hasNonNull("grade")) {
                return "a decision model carries no grade";
            }
            boolean windowed = proposal.hasNonNull("tpm");
            boolean gated = proposal.hasNonNull("max_concurrent");
            if (windowed == gated) {
                return "a decision model states exactly one bound, tpm or max_concurrent";
            }
        }
        else if (!proposal.hasNonNull("grade") && !proposal.hasNonNull("embedding_dimensions")) {
            return "neither a grade nor embedding_dimensions";
        }
        if (proposal.hasNonNull("grade")) {
            try {
                if (!Grade.valueOf(proposal.get("grade").asText()).isRung()) {
                    return "grade " + proposal.get("grade").asText() + " is not a rung";
                }
            }
            catch (IllegalArgumentException e) {
                return "unknown grade " + proposal.get("grade").asText();
            }
            for (String priced : List.of("input_price_per_million", "output_price_per_million")) {
                if (!proposal.hasNonNull(priced)) {
                    return "missing " + priced;
                }
            }
        }
        String provider = proposal.get("provider_key").asText();
        if (!providers.containsKey(provider)) {
            return "provider_key " + provider + " is not a registered provider";
        }
        String wireId = proposal.get("wire_model_id").asText();
        if (listedOnPlatform(wireId, provider, unknown, providers) == null) {
            return "wire_model_id " + wireId + " was not among the unclassified listings of " + provider + "'s platform";
        }
        if (!providers.get(provider).serves(wireId)) {
            return "provider_key " + provider + " has no client for " + wireId + "; its client speaks another family";
        }
        if (entries.containsKey(proposal.get("id").asText())) {
            return "id " + proposal.get("id").asText() + " is already taken";
        }
        return null;
    }

    /**
     * The unclassified listing behind a proposal, looked up across its platform's keys: the
     * keys of one platform list each other's vendors, so a model listed under one key may
     * legitimately be proposed on the sibling key whose client serves its family.
     */
    static DiscoveredModel listedOnPlatform(String wireId, String providerKey,
                                            Map<String, List<DiscoveredModel>> unknown, Map<String, ClientProvider<?>> providers) {
        String platform = providers.get(providerKey).platform();
        for (Map.Entry<String, List<DiscoveredModel>> listed : unknown.entrySet()) {
            ClientProvider<?> lister = providers.get(listed.getKey());
            if (lister != null && Objects.equals(platform, lister.platform())) {
                for (DiscoveredModel model : listed.getValue()) {
                    if (wireId.equals(model.wireModelId())) {
                        return model;
                    }
                }
            }
        }
        return null;
    }

    /** Removes a classified listing wherever its platform's keys reported it, so it leaves the person's list everywhere. */
    static void removeFromPlatform(String wireId, String providerKey,
                                   Map<String, List<DiscoveredModel>> unknown, Map<String, ClientProvider<?>> providers) {
        String platform = providers.get(providerKey).platform();
        unknown.entrySet().removeIf(listed -> {
            ClientProvider<?> lister = providers.get(listed.getKey());
            if (lister != null && Objects.equals(platform, lister.platform())) {
                listed.getValue().removeIf(m -> wireId.equals(m.wireModelId()));
            }
            return listed.getValue().isEmpty();
        });
    }
}
