/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.secrets.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Serves decision models on any endpoint that speaks TypeSafe's System One wire: TypeSafe's
 * own Jev, and every open replica served from a machine the deployment owns (Kev, Nimble,
 * OpenJev and the rest publish the same request and response shapes). This class is the
 * TypeSafe-compatible connection, a bearer key and an API root, for TypeSafe's Jev or a
 * replica served with a key; {@link LocalSystemOneProvider} is the same wire on a server on
 * this machine, reached by its address alone. Which model answers is a question of where the
 * credential's host points and which entry the catalog pins. The key ends in
 * {@code -decision}, which is how the runtime tells the decision family apart.
 *
 * <p>The endpoint's own listing ({@code GET /v1/models}) answers three questions here. It is
 * the discovery listing, so a connect verifies the credential against the server and a
 * discovery writes what the server serves. It is the connection facts, so a status surface
 * shows what stands behind the alias, once per run however many names the run answers under:
 * {@code model: jaredpalmer/kev-9b on mps, bfloat16}. And it is {@link #serves}: the endpoint serves the names it
 * lists, so the Jev entry is not aimed at a Kev server or the Kev entry at TypeSafe. The
 * listing is fetched on the first question about a host and kept per host; every
 * {@link #listModels()} refreshes it, and a connect and a discovery both list first.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class SystemOneProvider extends AbstractClientProvider<SystemOneClient> implements ModelDiscovery {
    private static final Logger log = LoggerFactory.getLogger(SystemOneProvider.class);
    /** The listing per API root, as the last fetch left it; a failed fetch is kept as its message. */
    private final ConcurrentHashMap<String, Listing> listings = new ConcurrentHashMap<>();
    private final String key;
    private final CredentialShape shape;

    private record Listing(List<SystemOneModelListing.Served> served, String failure) {}

    /** The TypeSafe-compatible endpoint: key {@code systemone-decision}, a bearer key and an API root. */
    public SystemOneProvider() {
        this("systemone-decision", SystemOneClient.SHAPE);
    }

    /** A System One endpoint under its own provider key, reached through its own credential. */
    protected SystemOneProvider(String key, CredentialShape shape) {
        this.key = key;
        this.shape = shape;
    }

    @Override
    public String key() {return key;}

    @Override
    public String platform() {return "systemone";}

    @Override
    public String credentialId() {return shape.id();}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(shape);}

    @Override
    protected SystemOneClient newClient() {return new SystemOneClient(shape.id());}

    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        Credential credential = credential();
        Listing listing = fetch(credential);
        listings.put(credential.host(), listing);
        if (listing.failure() != null) {
            throw new IOException(listing.failure());
        }
        // an endpoint that says what stands behind each name may list one run under several
        // names (Kev answers as kev-latest and as jev-latest); the discovery gets the run once,
        // under the first name, since a second entry for the same weights would be a duplicate
        // to price and pin, while serves() keeps answering for every name
        List<DiscoveredModel> models = new ArrayList<>(listing.served().size());
        Set<String> runs = new HashSet<>();
        for (SystemOneModelListing.Served served : listing.served()) {
            if (served.run() != null && !runs.add(served.run())) {
                continue;
            }
            models.add(served.discovered());
        }
        return models;
    }

    /**
     * The endpoint and what it runs: one line per run, however many names it answers under,
     * under {@code model} when there is one run and under its first name when there are several;
     * a listing that failed is the failure's message.
     */
    @Override
    public Map<String, String> connectionFacts() {
        Credential credential = Secrets.configured().find(credentialId());
        if (credential == null || credential.host() == null || credential.host().isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("endpoint", credential.host());
        Listing listing = listing(credential);
        if (listing.failure() != null) {
            facts.put("models", listing.failure());
        }
        else {
            List<SystemOneModelListing.Served> runs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (SystemOneModelListing.Served served : listing.served()) {
                if (served.run() == null || seen.add(served.run())) {
                    runs.add(served);
                }
            }
            for (SystemOneModelListing.Served served : runs) {
                facts.put(runs.size() == 1 ? "model" : served.name(), served.summary());
            }
        }
        return facts;
    }

    /**
     * Whether the endpoint lists the name. An endpoint whose listing could not be read serves
     * everything as far as this can tell: the ping that follows says otherwise by itself, and
     * a transient failure must not drop an entry from a catalog.
     */
    @Override
    public boolean serves(String wireModelId) {
        Credential credential = Secrets.configured().find(credentialId());
        if (credential == null || credential.host() == null || credential.host().isBlank()) {
            return true;
        }
        Listing listing = listing(credential);
        if (listing.failure() != null) {
            return true;
        }
        for (SystemOneModelListing.Served served : listing.served()) {
            if (served.name().equals(wireModelId)) {
                return true;
            }
        }
        return false;
    }

    private Credential credential() {
        Credential credential = Secrets.configured().require(credentialId());
        if (credential.host() == null || credential.host().isBlank()) {
            throw new IllegalStateException("The credential '" + credentialId()
                    + "' carries no host; its host part is the API root the listing is read from (e.g. http://127.0.0.1:8009)");
        }
        return credential;
    }

    private Listing listing(Credential credential) {
        return listings.computeIfAbsent(credential.host(), host -> fetch(credential));
    }

    private static Listing fetch(Credential credential) {
        try {
            return new Listing(SystemOneModelListing.list(credential.host(), credential.secret()), null);
        }
        catch (IOException | RuntimeException e) {
            log.warn("The System One endpoint at {} did not list its models: {}", credential.host(), e.getMessage());
            return new Listing(List.of(), e.getMessage());
        }
    }
}
