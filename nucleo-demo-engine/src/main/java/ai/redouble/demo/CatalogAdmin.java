/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.secrets.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The demo's write-side catalog logic, shared by every host: connecting a credential for the
 * session, running the discovery that writes the deployment's catalog, and editing or pinning
 * one of its entries. It is the twin of {@link DemoApi}: the same read-side status shape, and the
 * mutations the page drives it with, computed here once so no host carries a copy that can drift.
 * A host adds only its routing - it maps a route to one of these calls, hands over its own
 * {@link SessionStore}, and maps a refusal to its framework's status code: an
 * {@link IllegalArgumentException} is a bad request, an {@link IllegalStateException} a conflict,
 * an {@link UncheckedIOException} a server error.
 *
 * <p>The per-provider discovery fingerprints live here because this is the process-wide singleton
 * both hosts hold one of; a request-scoped resource would forget them between clicks.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class CatalogAdmin {
    private final DemoCatalog catalog;
    private final DemoCorpus corpus;
    private final SessionStore session;
    /** Per provider key, the fingerprint of the credential its last discovery this session ran under. Never a value, only digests. */
    private final Map<String, String> discovered = new ConcurrentHashMap<>();

    public CatalogAdmin(DemoCatalog catalog, DemoCorpus corpus, SessionStore session) {
        this.catalog = catalog;
        this.corpus = corpus;
        this.session = session;
    }

    /** The status the page renders, including whether each credential is held by the session store. */
    public RuntimeStatus status() {
        return DemoApi.status(catalog, corpus, session::holds);
    }

    /**
     * Holds the credentials for the session and tests them right then. A credential the
     * deployment already configures is refused rather than shadowed - session credentials fill
     * gaps, they never override, because the app's clients are constructed once around a
     * credential and shared. A credential this store itself holds may be corrected, and the
     * affected providers' cached clients are evicted in the same breath so the next call is built
     * around the new value. All records validate before any is held, so a refused request holds
     * nothing.
     */
    public ConnectOutcome connect(ConnectRequest input) {
        if (input.records() == null || input.records().isEmpty()) {
            throw new IllegalArgumentException("records: the credential parts to hold for this session");
        }
        for (SessionRecord record : input.records()) {
            if (record.id() == null || record.id().isBlank()) {
                throw new IllegalArgumentException("Every record names the credential id it fills");
            }
            for (ClientProvider<?> provider : ClientProviders.all().values()) {
                if (provider.credentialId().equals(record.id()) && provider.configured() && !session.holds(record.id())) {
                    throw new IllegalStateException("The credential '" + record.id()
                            + "' is already configured by the deployment; session credentials fill gaps, they never"
                            + " override. To change it, restart the process with the new value.");
                }
            }
        }
        for (SessionRecord record : input.records()) {
            boolean correction = session.holds(record.id());
            session.provide(record.id(), record.user(), record.secret(), record.host());
            if (correction) {
                for (ClientProvider<?> provider : ClientProviders.all().values()) {
                    if (provider.credentialId().equals(record.id())) {
                        ClientProviders.evict(provider.key());
                        discovered.remove(provider.key());
                    }
                }
            }
        }
        return verify(input.records());
    }

    /**
     * Tests a just-provided credential right then, so a bad key or endpoint is a red line on the
     * card and never a mystery in a later discovery: the first provider on the credential that can
     * list models makes that one authenticated, token-free call, and the answer - the count, or
     * the provider's own failure - travels back with the fresh status. A credential no provider
     * can list under is held and says so; it verifies on first use.
     */
    private ConnectOutcome verify(List<SessionRecord> records) {
        Set<String> ids = new HashSet<>();
        for (SessionRecord record : records) {
            ids.add(record.id());
        }
        for (ClientProvider<?> provider : new TreeMap<>(ClientProviders.all()).values()) {
            if (ids.contains(provider.credentialId()) && provider.configured() && provider instanceof ModelDiscovery discovery) {
                try {
                    return new ConnectOutcome(provider.key(), discovery.listModels().size(), null, null, status());
                }
                // a Throwable, not an Exception: verify reports the credential's health, and a
                // provider's client may fail with an Error rather than an Exception - a client the
                // closed world of a native image left uninitializable is one such failure, and it
                // is the credential's answer, not a crash of the probe
                catch (Throwable e) {
                    return new ConnectOutcome(provider.key(), null,
                            e.getClass().getSimpleName() + ": " + e.getMessage(), null, status());
                }
            }
        }
        return new ConnectOutcome(null, null, null,
                "held; no provider on this credential can list models, so it verifies on first use", status());
    }

    /**
     * The discovery, on a click instead of a terminal, and incremental: only providers whose
     * credentials are new or changed since their last discovery this session are listed and
     * pinged; everything already discovered rides through the scoped
     * {@link CatalogDiscovery#run(boolean, Set)} untouched, so a second click costs nothing unless
     * something changed. What ran is remembered as a fingerprint (a hash of the credential parts
     * and the connection facts, never the values). The result (models, limits, pins - never a
     * credential) is written to the demo's catalog file ({@link DemoHome#catalogFile()}, its
     * module's {@code src/main/resources/models.json}) and {@link Models#reload()} adopts it live.
     * A process outside its module has no such file and is refused before anything is pinged.
     */
    public DiscoverOutcome discover() {
        Set<String> scope = new LinkedHashSet<>();
        boolean anyConfigured = false;
        for (ClientProvider<?> provider : ClientProviders.all().values()) {
            if (!provider.configured()) {
                continue;
            }
            anyConfigured = true;
            if (!fingerprint(provider).equals(discovered.get(provider.key()))) {
                scope.add(provider.key());
            }
        }
        if (scope.isEmpty()) {
            throw new IllegalStateException(anyConfigured
                    ? "Every connected provider was already discovered this session with these exact credentials;"
                            + " provide a new or changed credential above to discover more"
                    : "No provider holds a credential; connect one in section 1 first");
        }
        Path out = DemoHome.catalogFile();
        if (out == null) {
            // refused before any provider is pinged: a run outside the demo's module has no file to
            // keep the result in, and a discovery whose result is thrown away is money spent on nothing
            throw new IllegalStateException("This process runs outside its module, on the catalog its build carried, and has no"
                    + " file to write a discovery to. Run the demo from its checkout, or name a file with -D"
                    + JsonModelsBackend.PROPERTY + ".");
        }
        CatalogDiscovery.Result result = CatalogDiscovery.run(false, scope);
        try {
            Files.writeString(out, NucleoJsonSerializer.write(result.catalog()), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("The discovery ran but its catalog could not be written to "
                    + out.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        // adopts as well as reloads: on a first run no file existed and nothing had named one,
        // so the write above is what creates the deployment's catalog
        DemoHome.adopt();
        List<ProviderOutcome> outcomes = new ArrayList<>();
        for (CatalogDiscovery.ProviderReport report : result.providers()) {
            if (!scope.contains(report.key())) {
                continue;
            }
            // a provider whose listing failed is NOT remembered as discovered: the next click
            // retries it (with a corrected credential or a recovered endpoint) at no cost to the
            // ones that succeeded
            if (report.status() != CatalogDiscovery.ProviderStatus.LISTING_FAILED) {
                discovered.put(report.key(), fingerprint(ClientProviders.all().get(report.key())));
            }
            outcomes.add(new ProviderOutcome(report.key(), report.status().name(),
                    report.listed() != null ? report.listed().size() : null,
                    result.unknown().containsKey(report.key()) ? result.unknown().get(report.key()).size() : null,
                    report.detail()));
        }
        return new DiscoverOutcome(result.report(), out.toAbsolutePath().toString(),
                result.catalog().get("models").size(), List.copyOf(scope), outcomes,
                result.classifiedBy(), result.classified(), result.classifiedDropped(), result.nonZdr(), result.unserved(),
                result.classifierFailure(), status());
    }

    /**
     * One entry of the deployment's own catalog file, edited from the page: its grade, its status
     * (OPEN or DISABLED), its input or output price, or a confirmation of its inferred facts; a
     * null leaves the field alone. {@link DemoCatalog#editEntry} states the rules and validates
     * the file by loading it before it is written; the runtime adopts it live. A refused edit is
     * an {@link IllegalArgumentException}; an edit with no file of the deployment's own to land
     * in is an {@link IllegalStateException}.
     */
    public RuntimeStatus editEntry(EntryEdit edit) {
        catalog.editEntry(edit.id(), edit.grade(), edit.status(), edit.inputPricePerMillion(), edit.outputPricePerMillion(), edit.confirmed());
        return status();
    }

    /**
     * One pin of the deployment's own catalog file, set or cleared from the page: the embeddings
     * entry or the decision entry; {@link DemoCatalog#pin} states the rules.
     */
    public RuntimeStatus pin(PinEdit edit) {
        catalog.pin(edit.slot(), edit.id());
        return status();
    }

    /**
     * One grade's order of the deployment's own catalog file, set from the page (a row dragged or
     * moved, a benchmark's order applied) or cleared; {@link DemoCatalog#order} states the rules.
     */
    public RuntimeStatus order(OrderEdit edit) {
        if (edit.ids() == null) {
            throw new IllegalArgumentException("An order names the grade's entry ids in order; an empty list clears it");
        }
        catalog.order(edit.grade(), edit.ids());
        return status();
    }

    /**
     * What a provider's discovery ran under, as a hash: the credential's parts and the connection
     * facts (the region, an endpoint), so a pasted key, a changed endpoint or a different region
     * all read as "changed" - and nothing secret is ever retained beyond the digest.
     */
    private static String fingerprint(ClientProvider<?> provider) {
        Credential credential = Secrets.configured().find(provider.credentialId());
        String material = (credential == null ? ""
                : credential.user() + "|" + credential.secret() + "|" + credential.host())
                + "|" + provider.connectionFacts();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
