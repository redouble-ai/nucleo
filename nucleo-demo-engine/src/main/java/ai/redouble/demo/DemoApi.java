/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.secrets.*;

import java.util.*;
import java.util.function.*;

/**
 * The demo's read-side logic, shared by every host. The endpoints that only read process state and
 * shape it for the page - the skill list, the agent's capability palette, and the runtime status
 * (providers, their declared credentials, the catalog by grade) - are computed here once, so no host
 * carries a copy that can drift from the page it feeds. A host adds only its routing: it maps a
 * route to one of these calls and serializes the result. The write-side endpoints, where a host
 * builds a Tool or a Doer and submits it, stay in the host as the demonstration of using the runtime
 * under that framework; those carry no logic a second host would repeat, only the framework wiring.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public final class DemoApi {
    private DemoApi() {}

    /** Every registered skill, as the page lists them, sorted by name. */
    public static List<SkillEntry> skills() {
        List<SkillEntry> entries = new ArrayList<>();
        for (Skill skill : SkillRegistry.all()) {
            String description = skill.description() != null && skill.description().content() != null
                    ? skill.description().content().asText()
                    : null;
            entries.add(new SkillEntry(skill.name(), description, skill.metadata().getOrigin(),
                    skill.metadata().getBundleId(), skill.suggestedTools()));
        }
        entries.sort(Comparator.comparing(SkillEntry::name));
        return entries;
    }

    /** The agent's tools and the skills it may draw on, for the "what can it do" panel. The owner names the workflow the palette is enumerated under. */
    public static AgentCapabilities agentCapabilities(String owner) {
        DemoAgent agent = new DemoAgent(Job.workflow(owner, "agent-capabilities"));
        List<String> tools = new ArrayList<>(agent.palette());
        tools.sort(String::compareTo);
        return new AgentCapabilities(tools, agent.skillCatalog());
    }

    /**
     * What this process can do: whether the dispatcher runs, every provider with the credentials it
     * declares, the catalog by entry with each entry's seat, the compliance envelope's verdict,
     * and its status, the shipped corpus's path, and the day the corpus's price story is answered
     * for ({@link DemoCorpus#AS_OF}). {@code sessionHeld} answers whether a credential id is held as a session
     * credential - a host capability, so it is passed in; a host without one passes {@code id -> false}.
     */
    public static RuntimeStatus status(DemoCatalog catalog, DemoCorpus corpus, Predicate<String> sessionHeld) {
        List<ProviderStatus> providers = new ArrayList<>();
        for (ClientProvider<?> provider : ClientProviders.all().values()) {
            // the credentials the provider reads, as it declared them: the same parts and variables
            // every store reads by, so the page asks for exactly those and nothing else
            List<CredentialView> credentials = new ArrayList<>();
            for (CredentialShape shape : provider.credentialShapes()) {
                List<PartView> parts = new ArrayList<>();
                if (shape.user() != null) {
                    parts.add(new PartView("user", shape.user().variable(), shape.user().meaning()));
                }
                if (shape.secret() != null) {
                    parts.add(new PartView("secret", shape.secret().variable(), shape.secret().meaning()));
                }
                if (shape.host() != null) {
                    parts.add(new PartView("host", shape.host().variable(), shape.host().meaning()));
                }
                credentials.add(new CredentialView(shape.id(), parts));
            }
            providers.add(new ProviderStatus(provider.key(), provider.configured(), sessionHeld.test(provider.credentialId()),
                    provider.describeCredential(), credentials, provider.connectionFacts()));
        }
        List<CatalogEntry> entries = new ArrayList<>();
        ComplianceEnvelope envelope = JobDispatcher.getInstance().getComplianceEnvelope();
        for (ModelSpec spec : Models.all()) {
            entries.add(new CatalogEntry(spec.getId(), spec.getGrade(), spec.getProviderKey(), spec.kind(),
                    spec.hasSeat(), envelope.permits(spec), spec.getStatus(), spec.supportsVision(), spec.supportsDocuments(), spec.getOutputModalities(),
                    spec.getInputPricePerMillion(), spec.getOutputPricePerMillion(), spec.getCurrency(),
                    spec.getTpm(), spec.getMaxConcurrent(), spec.unverified(), spec.getNote()));
        }
        Map<String, Map<String, String>> serving = new LinkedHashMap<>();
        Map<String, Map<String, String>> refusals = new LinkedHashMap<>();
        serving(envelope, serving, refusals);
        return new RuntimeStatus(JobDispatcher.getInstance().isRunning(), providers, catalog.status(), entries, serving, refusals,
                corpus.path(), DemoCorpus.AS_OF);
    }

    /** The kinds of request the page shows a grade's server for: text alone, with images, with documents. */
    static final Map<String, Set<Input>> REQUEST_KINDS = Map.of("text", Set.of(), "images", Set.of(Input.IMAGES), "documents", Set.of(Input.DOCUMENTS));

    /**
     * Per grade and kind of request, the entry the runtime serves it with now, asked of the
     * runtime's own resolution - the picker and the gate - so the page shows what a request of
     * that grade would get, never a guess of its own. Where the runtime refuses, the refusal's
     * words go to {@code refusals} under the same grade and kind, for the page to show instead.
     */
    static void serving(ComplianceEnvelope envelope, Map<String, Map<String, String>> serving, Map<String, Map<String, String>> refusals) {
        for (Grade grade : Grade.rungs()) {
            Map<String, String> served = new LinkedHashMap<>();
            Map<String, String> refused = new LinkedHashMap<>();
            for (String kind : List.of("text", "images", "documents")) {
                Situation situation = new Situation();
                situation.setEnvelope(envelope);
                situation.setSends(REQUEST_KINDS.get(kind));
                try {
                    served.put(kind, ModelPickers.resolve(new Seat(DemoApi.class, grade, ModelKind.LLM), situation).getId());
                }
                catch (ModelResolutionError | UncorrectableRuntimeLLMException refusal) {
                    // the runtime's answer for this grade and kind is that nothing serves it: shown in its own words
                    refused.put(kind, refusal.getMessage());
                }
            }
            serving.put(grade.name(), served);
            refusals.put(grade.name(), refused);
        }
    }
}
