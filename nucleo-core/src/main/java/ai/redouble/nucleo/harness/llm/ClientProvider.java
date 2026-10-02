/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.secrets.*;

import java.util.*;

/**
 * Constructs the concrete {@link Client} for one provider/endpoint. Each implementation is
 * monomorphic - it builds exactly one client class, declared by its type parameter - so the
 * provider hierarchy mirrors the client hierarchy (e.g. {@code AnthropicBedrockProvider}
 * sits under {@code AnthropicProvider} just as {@code AnthropicBedrockSDKClient} sits under
 * {@code AnthropicSDKClient}).
 *
 * <p>Providers are discovered and registered by {@link ClientProviders}; a model spec names
 * its provider through {@link ModelSpec#getProviderKey()}, which must equal this provider's
 * {@link #key()}. There is no central switch on provider type - construction is polymorphic
 * dispatch through {@link #createClient(ModelSpec)}.
 *
 * <p>A provider also states what it needs from the deployment before a client can be built
 * ({@link #credentialId()}), and answers whether the deployment holds it ({@link #configured()})
 * without touching the network. {@link DefaultModelPicker} reads that answer to serve a grade
 * from an entry the deployment can actually call, and to name what to provide when none can.
 *
 * @param <E> the exact client type this provider builds
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public interface ClientProvider<E extends Client> {
    /**
     * Registry key, matched against {@link ModelSpec#getProviderKey()}. Stable and unique
     * across providers (duplicate keys are rejected at registration).
     */
    String key();

    /**
     * Builds the client for the given spec. The returned client is bare (no per-job
     * observability wrap) and intended to be shared across jobs for the same spec.
     */
    E createClient(ModelSpec spec);

    /**
     * The id of the credential this provider's clients are constructed around, in the
     * deployment's {@link Secrets} store: the one {@link #createClient} would fail without.
     */
    String credentialId();

    /**
     * Whether this deployment holds what {@link #createClient} needs. A local question -
     * the store is consulted, no provider is called - so a picker may ask it for every
     * catalog entry at first resolution.
     */
    default boolean configured() {
        return Secrets.configured().find(credentialId()) != null;
    }

    /**
     * How a person provides what {@link #configured()} checks, in words a refusal can carry
     * ({@link Secrets#describe} for the store in use).
     */
    default String describeCredential() {
        return Secrets.configured().describe(credentialId());
    }

    /**
     * Non-secret facts about where this provider's calls go, resolved exactly as
     * {@link #createClient} would resolve them and without any network call: an endpoint URL,
     * an AWS region, an Azure resource. For a status surface to show next to
     * {@link #configured()}, because a deployment can hold a credential and still point
     * somewhere wrong, and the call failure that follows typically names neither the place nor
     * the choice that sent it there. Keys are words for a person ({@code endpoint},
     * {@code region}); a fact whose resolution fails carries the failure's message as its
     * value, since on a status surface the failure is the fact. By default the credential's
     * {@link Credential#host() host} when the deployment provided one - the endpoint-shaped
     * providers keep the API root there - and empty when nothing is deployment-chosen.
     */
    default Map<String, String> connectionFacts() {
        Credential credential = Secrets.configured().find(credentialId());
        return credential != null && credential.host() != null ? Map.of("endpoint", credential.host()) : Map.of();
    }

    /**
     * The concrete {@link ModelSpec} shapes this provider's catalog entries may name in their
     * {@code spec_type}, keyed by that name. The catalog loader resolves a spec type through the
     * discovered providers, so a provider artifact brings its own spec class along and the core
     * knows none of them. Empty for a provider whose models are all standard.
     */
    default Map<String, Class<? extends AbstractModelSpec>> specTypes() {
        return Map.of();
    }

    /**
     * The platform the credential belongs to: {@code bedrock} for every Bedrock surface,
     * {@code openai}, {@code azure}, {@code anthropic}. The scope of a deployment's
     * {@link ModelStatus#DISABLED}: a model disabled on one key of a platform is disabled on
     * every key of it (base Bedrock and Mantle are one platform), and on no key of another.
     */
    String platform();

    /**
     * The catalog identity of a model this provider's account lists under a wire id: the name
     * that stays the same across channels and endpoint spellings ({@code opus-4.7} for both
     * {@code us.anthropic.claude-opus-4-7} and {@code claude-opus-4-7-20260301}). A provider
     * whose vendor spells ids its own way overrides this; the default strips the endpoint
     * decorations every vendor adds (geography prefix, vendor prefix, version and date suffixes).
     */
    default String identityOf(String wireModelId) {
        return ModelLineage.identityOf(wireModelId);
    }

    /**
     * The catalog id a new entry on this provider gets for an identity the discovery adds:
     * the identity itself by default, endpoint-suffixed where the provider's entries are
     * ({@code claude-opus-5-bedrock}).
     */
    default String catalogIdOf(String identity, String wireModelId) {
        return identity;
    }

    /**
     * The shapes of the credentials this provider reads: which parts each has and the
     * environment variable each part comes from, declared once here and put on record by the
     * provider registry when it loads the provider, so every store reads and describes the
     * credential by the same declaration. By default one credential, {@link #credentialId()},
     * that is an API key alone; a provider whose credential has a host, a user, or that reads
     * several credentials declares them.
     */
    default List<CredentialShape> credentialShapes() {
        return List.of(CredentialShape.secret(credentialId(), EnvironmentSecrets.variableName(credentialId()), "the API key"));
    }

    /**
     * Whether this provider's client speaks to the model behind a wire id: the request shape
     * its client sends is the one that model family accepts. The keys of one platform list
     * each other's vendors (every Bedrock surface lists the same account), so a listing is
     * placed on the key that serves its family, and a model no key of the platform serves is a
     * listed fact with no client, reported as such and never classified into an entry that
     * would fail on its first call. True by default: a provider whose endpoint takes one
     * request shape for everything it lists (OpenAI, Azure, the Anthropic API) serves all of
     * it; a provider whose client speaks one family on a shared platform overrides.
     */
    default boolean serves(String wireModelId) {
        return true;
    }

    /**
     * Whether this provider is written for the model behind a wire id: its client is the one
     * that model's vendor made for it, carrying what a client that merely {@link #serves} it
     * does not. When a catalog entry names a provider this classpath does not carry, the entry
     * is linked in memory to a provider of its platform that claims its model before one that
     * only serves it ({@link ProviderLinks}). False by default: a provider claims a model only
     * when that model's family is the reason the provider exists.
     */
    default boolean claims(String wireModelId) {
        return false;
    }

    /**
     * The namespace this provider's wire ids are spelled in: the endpoint family that resolves
     * them. The platform's by default, since the keys of a platform address one endpoint family;
     * a provider whose endpoint names models its own way (Mantle's bare Claude ids, which
     * {@code bedrock-runtime} refuses) names its own. A catalog entry is linked only to a provider
     * of its own addressing ({@link ProviderLinks}), because its wire id means nothing to any other.
     */
    default String addressing() {
        return platform();
    }
}
