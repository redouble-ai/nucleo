/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The one development-time action that turns the shipped catalog into this account's: walk
 * every provider on the classpath, ask each configured one what the account can reach, ping
 * every entry that is reachable, and write a complete {@code models.json} with the account's
 * observed limits plus a report of everything that was learned and everything that was not.
 *
 * <p>Sources, in order of trust: the deployment's previous file (its entries, pins and
 * hand-set fields are kept), the shipped fragments (the seed: public facts and entry-tier
 * limits for every model the providers serve), the listings (which of those the account can
 * see, plus quotas and retention where the provider publishes them), and the pings (does the
 * call succeed now, and what limits do the response headers carry). Nothing is invented: a
 * listed model no source knows is reported as unknown with its raw listing, for a person to
 * add.
 *
 * <p>Per provider: not configured means its seed entries are left out and the report names
 * the credential to provide; a provider that cannot list ({@link ModelDiscovery} not
 * implemented) has its seed entries pinged blind; a listing failure is reported verbatim and
 * treated the same way, except the one that says everything: a listing that fails because the
 * provider's endpoint is not served where this deployment points (an unknown host, which no
 * retry changes) leaves the provider's seed entries out and closes its entries from the
 * previous file as {@code UNREACHABLE}, without a ping. Per entry: not listed by a provider
 * that did list means dropped, unless it came from the previous file, which is never shrunk
 * because history still prices against it, and closed as {@code UNLISTED}; reachable means
 * kept with the observed limits; unreachable means dropped for a seed entry and kept, closed
 * as {@code UNREACHABLE}, for a previous one. Kept is kept: a closed entry is in the file for
 * the runs it served, and nothing picks it until a later run reopens it. A ping that fails for a reason that says nothing
 * about the entry (overload, throttling, a 5xx) is repeated a few times, and an entry still
 * unanswered is kept with the limits it had and flagged unverified, never dropped on a bad
 * minute. A seed entry naming a model through another geography's profile ({@code us.} in a
 * region that lists {@code eu.}), or through a bare on-demand id where the region has a
 * profile for the model, is rewritten to the profile this region lists, pinged as rewritten,
 * and reported as such; the catalog id stays, {@code global.} ids are the same everywhere and
 * stay. Providers and entries are visited in order, one at a time, so two runs against the
 * same account read the same.
 *
 * <p>Run it from a classpath that carries the provider artifacts and the credentials:
 * {@code java -cp ... ai.redouble.nucleo.harness.models.discovery.CatalogDiscovery [--out models.json] [--report-only]}.
 * The result is validated by loading it through {@link JsonModelsBackend} before it is written.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
public final class CatalogDiscovery {
    private static final Logger log = LoggerFactory.getLogger(CatalogDiscovery.class);

    /**
     * What happened with one provider. {@code NOT_ON_CLASSPATH}: the previous file names the
     * provider and no artifact on this classpath declares it (a native image built without an
     * SDK the JVM build carries), so nothing is listed or pinged with it and its entries ride
     * through exactly as written.
     */
    public enum ProviderStatus {NOT_CONFIGURED, LISTED, LISTING_UNSUPPORTED, LISTING_FAILED, UNREACHABLE, OUT_OF_SCOPE, NOT_ON_CLASSPATH}

    /**
     * {@code UNVERIFIED}: every attempt failed for a reason that says nothing about the entry
     * (overload, throttling, a 5xx), so the entry is kept with the limits it had and flagged
     * for the next run rather than dropped on a bad minute.
     */
    /**
     * What a run decided about one entry. {@code NOT_ROUTABLE_HERE}: this process cannot route
     * the model - its compliance envelope refuses it, or the model needs the Mantle LAX project
     * and this deployment configures none - so it was not pinged; a fact about this deployment,
     * never about the account's reach, and the entry is kept. {@code NON_ZDR}: the
     * provider refused the ping because the model does not offer zero data retention (ZDR),
     * the mode the account runs at; on a surface that cannot route it any other way there is no entry to
     * write, and a new one is left out under its own heading rather than as unreachable.
     */
    public enum Verdict {REACHABLE, UNREACHABLE, UNVERIFIED, NOT_LISTED, NOT_CONFIGURED, SKIPPED, OLDER, NOT_ROUTABLE_HERE, NON_ZDR}

    /**
     * The provider's wording when a model refuses the account's retention mode; the only signal
     * the legacy Bedrock surface gives, since it lists no {@code allowed_modes}. The runtime
     * matches it in a ping's failure, never in serving.
     */
    static final String ZERO_RETENTION_REFUSAL = "data retention mode";

    /** Attempts at a ping whose failure is transient, and the pause between them in a live run. */
    static final int PING_ATTEMPTS = 3;
    static final Duration PING_RETRY_DELAY = Duration.ofSeconds(5);

    /** What happened with one provider. */
    public record ProviderReport(String key, ProviderStatus status, String detail, List<DiscoveredModel> listed) {}

    /**
     * What happened with one catalog entry. {@code kept} says whether it is in the written
     * file; {@code fromPrevious} whether the deployment's file already carried it.
     */
    public record EntryReport(String id, String provider, Verdict verdict, boolean fromPrevious, boolean kept,
                              ProbeOutcome outcome, Integer tpmBefore, Integer tpmAfter, Integer rpmBefore, Integer rpmAfter,
                              String wireBefore, String wireAfter, String note, boolean added) {
        /** An entry whose wire id the discovery rewrote to the geography profile this region lists. */
        public boolean relocated() {
            return wireBefore != null && !wireBefore.equals(wireAfter);
        }
    }

    /**
     * The whole run: the catalog to write, the report to read, and the facts both were made
     * from. {@code previousSource} names the deployment's file the run read, null on a first
     * pull. {@code unserved} lists the models the account offers that no provider key on their
     * platform has a client for (an embeddings family with its own request shape, a vendor the
     * surface's SDK does not speak), one line each, never classified since an entry would fail
     * on its first call. {@code nonZdr} (not zero data retention) lists the new entries left out because the model
     * refused the zero-retention mode the account runs at. {@code classifiedBy} names the model
     * whose judgment wrote the {@code classified} entries (each also noted in the entry itself);
     * {@code classifierFailure} says why unknowns stayed unclassified when they did.
     */
    public record Result(ObjectNode catalog, String report, List<ProviderReport> providers, List<EntryReport> entries,
                         Map<String, List<DiscoveredModel>> unknown, List<String> newerThanPinned, List<String> olderLeftOut,
                         List<String> unserved, List<String> nonZdr, String previousSource, String classifiedBy, List<String> classified,
                         List<String> classifiedDropped, String classifierFailure) {
        /** True when at least one provider was configured, so there was something to write. */
        public boolean anythingConfigured() {
            return providers.stream().anyMatch(p -> p.status() != ProviderStatus.NOT_CONFIGURED && p.status() != ProviderStatus.NOT_ON_CLASSPATH);
        }
    }

    /** One classification exchange: the chosen model, the prompt, the raw answer. A test scripts it; {@code run} wires {@link ModelClassifier#call}. */
    interface ClassifierCall {
        String call(ClientProvider<?> provider, ModelSpec spec, String prompt) throws Exception;
    }

    private final JsonModelsBackend seed;
    private final JsonModelsBackend previous;
    private final Map<String, ClientProvider<?>> providers;
    private final BiFunction<ClientProvider<?>, ModelSpec, ProbeOutcome> ping;
    private final Duration retryDelay;
    private final boolean includeOlder;
    /** The provider keys this run re-discovers; null means every provider. Out-of-scope entries ride through as they are. */
    private final Set<String> scope;
    /** How unknown listings are classified; null and they stay on the person's list, as a run without a callable model leaves them. */
    private final ClassifierCall classifier;
    /**
     * The compliance envelope this process runs under: an entry it refuses is kept and not
     * pinged, because the refusal is this deployment's policy and says nothing about whether
     * the account can reach the model.
     */
    private final ComplianceEnvelope envelope;
    /** The id of the model whose judgment classified this run's unknowns, once a seat was chosen. */
    private String classifierId;
    /** What each in-scope provider listed, by key then wire id, for the whole run: a key that declines a model looks up its siblings here. */
    private final Map<String, Map<String, DiscoveredModel>> listedByKey = new LinkedHashMap<>();

    /** Over explicit sources, providers and a ping, so the rules are testable without a network. */
    CatalogDiscovery(JsonModelsBackend seed, JsonModelsBackend previous, Map<String, ClientProvider<?>> providers,
                     BiFunction<ClientProvider<?>, ModelSpec, ProbeOutcome> ping) {
        this(seed, previous, providers, ping, PING_RETRY_DELAY, false);
    }

    /** With the pause between attempts at a transient ping explicit, so a test can make it zero. */
    CatalogDiscovery(JsonModelsBackend seed, JsonModelsBackend previous, Map<String, ClientProvider<?>> providers,
                     BiFunction<ClientProvider<?>, ModelSpec, ProbeOutcome> ping, Duration retryDelay) {
        this(seed, previous, providers, ping, retryDelay, false);
    }

    /**
     * {@code includeOlder}: whether older versions of a model the catalog already has a newer
     * version of are written. Off, a first pull keeps one entry per family and key, the newest,
     * and a listed older version is left out with a line saying so; on, they all go in.
     */
    CatalogDiscovery(JsonModelsBackend seed, JsonModelsBackend previous, Map<String, ClientProvider<?>> providers,
                     BiFunction<ClientProvider<?>, ModelSpec, ProbeOutcome> ping, Duration retryDelay, boolean includeOlder) {
        this(seed, previous, providers, ping, retryDelay, includeOlder, null, null, permitting());
    }

    CatalogDiscovery(JsonModelsBackend seed, JsonModelsBackend previous, Map<String, ClientProvider<?>> providers,
                     BiFunction<ClientProvider<?>, ModelSpec, ProbeOutcome> ping, Duration retryDelay, boolean includeOlder,
                     Set<String> scope, ClassifierCall classifier, ComplianceEnvelope envelope) {
        this.envelope = envelope;
        this.seed = seed;
        this.previous = previous;
        this.providers = providers;
        this.ping = ping;
        this.retryDelay = retryDelay;
        this.includeOlder = includeOlder;
        this.scope = scope;
        this.classifier = classifier;
    }

    /**
     * The real thing: shipped fragments, the deployment's file if any, every provider on the
     * classpath, live pings. The discovery's question is what the ACCOUNT can call, so the
     * process it runs in must permit every retention posture; which of those an application
     * may then use is that application's compliance envelope, sealed in its own process. The
     * seal is the host's: {@link #main} seals {@link #permitting()} when the discovery is the
     * process, and a harness that boots an application to reach its credential store seals it
     * through that application's servlet before calling {@link #execute}.
     */
    public static Result run(boolean includeOlder) {
        return run(includeOlder, null);
    }

    /**
     * The discovery over part of the classpath's providers: the named keys are re-listed and
     * re-pinged, and every other provider's previous entries ride through exactly as they are
     * - out of scope, never dropped, never pinged. For a caller that discovers incrementally
     * as credentials arrive, paying only for what changed. Null means every provider.
     */
    public static Result run(boolean includeOlder, Set<String> scope) {
        return new CatalogDiscovery(JsonModelsBackend.shipped(), JsonModelsBackend.deploymentFile(),
                ClientProviders.all(), CatalogPing::ping, PING_RETRY_DELAY, includeOlder, scope,
                ModelClassifier::call, JobDispatcher.getInstance().getComplianceEnvelope()).discover();
    }

    /** The envelope a discovery process runs under: every posture permitted, the default with its one knob open. */
    public static DefaultComplianceEnvelope permitting() {
        DefaultComplianceEnvelope permitting = new DefaultComplianceEnvelope();
        permitting.setAllowDataShare(true);
        return permitting;
    }

    public static void main(String[] args) throws IOException {
        JobDispatcher.getInstance().sealComplianceEnvelope(permitting());
        execute(args);
    }

    /**
     * The command without the seal: parse the arguments, run, print the report, write the file
     * unless told not to. The file written is the one {@code --out} names, else the one
     * {@code -Dnucleo.models} names; the runtime reads its catalog off the classpath, so where the
     * written file goes to be read - an application's {@code src/main/resources} - is the caller's.
     */
    public static void execute(String[] args) throws IOException {
        Path out = JsonModelsBackend.namedFile();
        boolean reportOnly = false;
        boolean includeOlder = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--report-only" -> reportOnly = true;
                case "--include-older" -> includeOlder = true;
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]
                        + "; usage: CatalogDiscovery [--out <path>] [--report-only] [--include-older]");
            }
        }
        if (out == null && !reportOnly) {
            throw new IllegalArgumentException("Name the file to write: --out <path>, or -D" + JsonModelsBackend.PROPERTY
                    + "=<path>. An application reads it from its classpath, so write it to its src/main/resources/"
                    + JsonModelsBackend.CLASSPATH_RESOURCE + "; usage: CatalogDiscovery [--out <path>] [--report-only] [--include-older]");
        }
        Result result = run(includeOlder);
        System.out.println(result.report());
        if (!result.anythingConfigured()) {
            System.out.println("Nothing written: no provider is configured.");
            System.exit(1);
        }
        if (reportOnly) {
            return;
        }
        Files.writeString(out, NucleoJsonSerializer.write(result.catalog()), StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + result.catalog().get("models").size() + " entries"
                + (result.catalog().has("pins") ? ", pins kept" : "") + ")");
    }

    Result discover() {
        Map<String, ObjectNode> entries = previous != null ? previous.rawEntries() : new LinkedHashMap<>();
        Set<String> fromPrevious = new HashSet<>(entries.keySet());
        seed.rawEntries().forEach(entries::putIfAbsent);
        Map<String, ModelSpec> specs = new HashMap<>();
        for (String id : entries.keySet()) {
            ModelSpec spec = previous != null ? previous.spec(id) : null;
            specs.put(id, spec != null ? spec : seed.spec(id));
        }
        Set<String> pinnedIds = pinnedIds();
        // a first pull keeps one entry per family and key, the newest, unless asked otherwise
        Set<String> older = previous == null && !includeOlder ? olderVersions(entries) : Set.of();
        List<ProviderReport> providerReports = new ArrayList<>();
        Map<String, EntryReport> entryReports = new LinkedHashMap<>();
        Map<String, List<DiscoveredModel>> unknown = new LinkedHashMap<>();
        List<String> newerThanPinned = new ArrayList<>();
        List<String> olderLeftOut = new ArrayList<>();
        List<String> unserved = new ArrayList<>();
        List<String> nonZdr = new ArrayList<>();
        // Providers sharing one surface (the four Bedrock ones, the two OpenAI ones) list the
        // same models; one that gets no entry is reported once, under the first that listed it
        Set<String> reportedUnknown = new HashSet<>();
        String today = LocalDate.now().toString();
        // every in-scope provider lists first, so that when a key declines a model it can see
        // whether a sibling key of the platform both lists it and has the client for it
        Map<String, ProviderReport> inspected = new LinkedHashMap<>();
        for (String key : new TreeSet<>(providers.keySet())) {
            if (scope == null || scope.contains(key)) {
                ProviderReport report = inspect(key, providers.get(key));
                inspected.put(key, report);
                if (report.listed() != null) {
                    Map<String, DiscoveredModel> byWire = new LinkedHashMap<>();
                    for (DiscoveredModel model : report.listed()) {
                        byWire.put(model.wireModelId(), model);
                    }
                    listedByKey.put(key, byWire);
                }
            }
        }
        for (String key : new TreeSet<>(providers.keySet())) {
            ClientProvider<?> provider = providers.get(key);
            if (scope != null && !scope.contains(key)) {
                // out of this run's scope: nothing listed, nothing pinged, and what the
                // previous catalog held for this provider is written back exactly as it was
                providerReports.add(new ProviderReport(key, ProviderStatus.OUT_OF_SCOPE,
                        "not asked for in this run; its entries ride through as they are", null));
                for (Map.Entry<String, ObjectNode> e : entries.entrySet()) {
                    if (key.equals(e.getValue().get("provider_key").asText())) {
                        boolean previousEntry = fromPrevious.contains(e.getKey());
                        Integer tpm = intOrNull(e.getValue(), "tpm");
                        Integer rpm = intOrNull(e.getValue(), "rpm");
                        String wire = e.getValue().get("wire_model_id").asText();
                        entryReports.put(e.getKey(), new EntryReport(e.getKey(), key, Verdict.SKIPPED, previousEntry,
                                previousEntry, null, tpm, tpm, rpm, rpm, wire, wire,
                                "provider out of this run's scope; kept as it was", false));
                    }
                }
                continue;
            }
            ProviderReport providerReport = inspected.get(key);
            providerReports.add(providerReport);
            Map<String, DiscoveredModel> listed = listedByKey.get(key);
            for (Map.Entry<String, ObjectNode> e : new ArrayList<>(entries.entrySet())) {
                String id = e.getKey();
                ObjectNode entry = e.getValue();
                if (!key.equals(entry.get("provider_key").asText())) {
                    continue;
                }
                boolean previousEntry = fromPrevious.contains(id);
                Integer tpmBefore = intOrNull(entry, "tpm");
                Integer rpmBefore = intOrNull(entry, "rpm");
                String wireBefore = entry.get("wire_model_id").asText();
                if (older.contains(id)) {
                    entryReports.put(id, new EntryReport(id, key, Verdict.OLDER, previousEntry, false,
                            null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore, null, false));
                    continue;
                }
                if (providerReport.status() == ProviderStatus.NOT_CONFIGURED) {
                    entryReports.put(id, new EntryReport(id, key, Verdict.NOT_CONFIGURED, previousEntry, previousEntry,
                            null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore, null, false));
                    continue;
                }
                if (providerReport.status() == ProviderStatus.UNREACHABLE) {
                    // the provider's endpoint is not served where this deployment points, so
                    // nothing of it can be called from here whatever its entries say: a seed
                    // entry is left out; an entry kept from the previous file is kept for the
                    // runs it served and closed as UNREACHABLE, so nothing picks or benchmarks
                    // it until a later run's listing answers and reopens it; an entry already
                    // closed by another word keeps that word
                    ModelStatus before = statusOf(entry);
                    boolean closedHere = previousEntry && before == ModelStatus.OPEN;
                    if (closedHere) {
                        entry.put("status", ModelStatus.UNREACHABLE.name());
                    }
                    String why = !previousEntry ? "its provider's endpoint is not served where this deployment points (seed entry left out)"
                            : closedHere ? "its provider's endpoint is not served where this deployment points; kept for the runs it served, status set to UNREACHABLE, not served until its provider answers again"
                            : before + ", not pinged";
                    entryReports.put(id, new EntryReport(id, key, previousEntry && !closedHere ? Verdict.SKIPPED : Verdict.UNREACHABLE,
                            previousEntry, previousEntry, null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore, why, false));
                    continue;
                }
                ModelStatus status = statusOf(entry);
                boolean listedNow = listed != null && (listed.get(wireBefore) != null || !regionsProfiles(wireBefore, listed.keySet()).isEmpty());
                String note = null;
                if (status == ModelStatus.UNLISTED && listedNow) {
                    // the account's word, and the account has changed its mind: the listing
                    // names the model again, so the entry reopens and earns a ping like a new one
                    entry.remove("status");
                    status = ModelStatus.OPEN;
                    note = "the account lists it again; reopened and pinged";
                }
                else if (status == ModelStatus.UNREACHABLE && listed != null) {
                    // this deployment's reach, and the provider answers from here now: an
                    // entry the listing names reopens and is pinged like a new one; one the
                    // listing does not name takes the account's word, UNLISTED, in place of
                    // the reach's, and reopens the day the listing names it
                    if (listedNow) {
                        entry.remove("status");
                        status = ModelStatus.OPEN;
                        note = "its provider answers again; reopened and pinged";
                    }
                    else {
                        entry.put("status", ModelStatus.UNLISTED.name());
                        entryReports.put(id, new EntryReport(id, key, Verdict.NOT_LISTED, previousEntry, true,
                                null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore,
                                "its provider answers again but does not list it; kept for the runs it served, status set to UNLISTED, not served until listed again", false));
                        continue;
                    }
                }
                if (status != ModelStatus.OPEN) {
                    entryReports.put(id, new EntryReport(id, key, Verdict.SKIPPED, previousEntry, true,
                            null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore,
                            status + ", not pinged", false));
                    continue;
                }
                if (previousEntry && note == null) {
                    // the deployment's file is the deployment's: an entry it already carries is
                    // never re-evaluated - no ping, no rewrite, its facts as the person left
                    // them - and it leaves this run only when the listing itself says the model
                    // is gone, which costs nothing to detect. Gone, it is kept for the runs it
                    // served and closed as UNLISTED, so nothing picks a model the account no
                    // longer serves; a later listing that names it again reopens it.
                    boolean gone = listed != null && !listedNow;
                    if (gone) {
                        entry.put("status", ModelStatus.UNLISTED.name());
                    }
                    entryReports.put(id, gone
                            ? new EntryReport(id, key, Verdict.NOT_LISTED, true, true,
                                    null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore,
                                    "the account no longer lists it; kept for the runs it served, status set to UNLISTED, not served until listed again", false)
                            : new EntryReport(id, key, Verdict.SKIPPED, true, true,
                                    null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore,
                                    "already in the deployment's file; kept as it was, not re-evaluated", false));
                    continue;
                }
                DiscoveredModel discovered = listed != null ? listed.get(wireBefore) : null;
                ModelSpec spec = specs.get(id);
                if (listed != null) {
                    List<String> profiles = regionsProfiles(wireBefore, listed.keySet());
                    if (profiles.size() == 1) {
                        // the same model on this region's own geography profile: what is pinged
                        // is what is written, and the listing already said it exists here
                        String relocated = profiles.get(0);
                        entry.put("wire_model_id", relocated);
                        discovered = listed.get(relocated);
                        spec = (previousEntry && previous != null ? previous : seed).specOf(entry);
                    }
                    else if (profiles.size() > 1) {
                        note = "this region lists the model under more than one geography, " + profiles + "; pick one by hand";
                    }
                    if (discovered == null) {
                        entryReports.put(id, new EntryReport(id, key, Verdict.NOT_LISTED, previousEntry, previousEntry,
                                null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, wireBefore, note, false));
                        continue;
                    }
                    if (Boolean.TRUE.equals(discovered.retired())) {
                        // the vendor's word, written as such; a retired model is not worth a call
                        entry.put("status", ModelStatus.DEPRECATED.name());
                        entryReports.put(id, new EntryReport(id, key, Verdict.SKIPPED, previousEntry, true,
                                null, tpmBefore, tpmBefore, rpmBefore, rpmBefore, wireBefore, entry.get("wire_model_id").asText(),
                                "the provider marks it retired; status set to DEPRECATED, not pinged", false));
                        continue;
                    }
                }
                Pinged pinged = pingAndApply(provider, spec, entry, discovered);
                Verdict verdict = pinged.verdict();
                boolean kept = keptAfterPing(verdict) || previousEntry;
                if (verdict == Verdict.NON_ZDR && !kept) {
                    nonZdr.add(id + "  (" + key + ": " + wireBefore + ")");
                }
                if (verdict == Verdict.UNREACHABLE && previousEntry) {
                    // kept is kept, nothing more: an entry of the deployment's file whose ping
                    // fails for a reason no retry changes stays for the runs it served and is
                    // closed, so nothing picks it until a later run reaches it
                    entry.put("status", ModelStatus.UNREACHABLE.name());
                }
                entryReports.put(id, new EntryReport(id, key, verdict, previousEntry, kept, pinged.outcome(),
                        tpmBefore, intOrNull(entry, "tpm"), rpmBefore, intOrNull(entry, "rpm"),
                        wireBefore, entry.get("wire_model_id").asText(), note, false));
            }
            if (listed != null) {
                addListed(key, provider, listed, entries, fromPrevious, pinnedIds, entryReports,
                        unknown, reportedUnknown, newerThanPinned, olderLeftOut, unserved, nonZdr, today);
            }
        }
        // a provider the previous file names that this classpath does not carry: nothing to list
        // or ping with, and its entries ride through as written, as an out-of-scope provider's do
        Set<String> absent = new TreeSet<>();
        for (Map.Entry<String, ObjectNode> e : entries.entrySet()) {
            String key = e.getValue().get("provider_key").asText();
            if (!providers.containsKey(key) && fromPrevious.contains(e.getKey())) {
                absent.add(key);
                Integer tpm = intOrNull(e.getValue(), "tpm");
                Integer rpm = intOrNull(e.getValue(), "rpm");
                String wire = e.getValue().get("wire_model_id").asText();
                entryReports.put(e.getKey(), new EntryReport(e.getKey(), key, Verdict.SKIPPED, true, true, null,
                        tpm, tpm, rpm, rpm, wire, wire, "provider not on this classpath; kept as written", false));
            }
        }
        for (String key : absent) {
            providerReports.add(new ProviderReport(key, ProviderStatus.NOT_ON_CLASSPATH,
                    "no artifact on this classpath declares it; its entries ride through as written", null));
        }
        // a model one key could not place may have been added by a later key of the platform
        for (Map.Entry<String, List<DiscoveredModel>> e : new ArrayList<>(unknown.entrySet())) {
            ClientProvider<?> provider = providers.get(e.getKey());
            e.getValue().removeIf(m -> knownOnPlatform(platformOf(e.getKey()), bare(m.wireModelId()),
                    provider.identityOf(m.wireModelId()), entries));
            if (e.getValue().isEmpty()) {
                unknown.remove(e.getKey());
            }
        }
        String classifierFailure = null;
        List<String> classified = new ArrayList<>();
        List<String> classifiedDropped = new ArrayList<>();
        if (!unknown.isEmpty() && classifier != null) {
            classifierFailure = classify(entries, fromPrevious, unknown, entryReports, classified, classifiedDropped, nonZdr, today);
        }
        String classifiedBy = classifierId;
        ObjectNode catalog = NucleoJsonSerializer.createObjectNode();
        ObjectNode providerDefaults = seed.rawProviderDefaults();
        if (previous != null) {
            previous.rawProviderDefaults().properties().forEach(field -> {
                ObjectNode merged = providerDefaults.has(field.getKey())
                        ? (ObjectNode) providerDefaults.get(field.getKey())
                        : providerDefaults.putObject(field.getKey());
                merged.setAll((ObjectNode) field.getValue());
            });
        }
        catalog.set("provider_defaults", providerDefaults);
        ArrayNode models = catalog.putArray("models");
        for (Map.Entry<String, ObjectNode> e : entries.entrySet()) {
            EntryReport report = entryReports.get(e.getKey());
            if (report != null && report.kept()) {
                models.add(e.getValue());
            }
        }
        ObjectNode pins = previous != null ? previous.rawPins() : null;
        if (pins != null) {
            catalog.set("pins", pins);
        }
        JsonNode recorded = previous != null ? previous.rawProviders() : null;
        if (recorded != null) {
            catalog.set(ProviderLinks.PROVIDERS, recorded);
        }
        ProviderLinks.stamp(catalog, providers);
        validate(catalog);
        List<EntryReport> ordered = new ArrayList<>(entryReports.values());
        String previousSource = previous != null ? previous.source() : null;
        Result result = new Result(catalog, null, providerReports, ordered, unknown, newerThanPinned, olderLeftOut, unserved, nonZdr,
                previousSource, classifiedBy, classified, classifiedDropped, classifierFailure);
        return new Result(catalog, render(result), providerReports, ordered, unknown, newerThanPinned, olderLeftOut, unserved, nonZdr,
                previousSource, classifiedBy, classified, classifiedDropped, classifierFailure);
    }

    /**
     * A new entry's retention posture is the listing's word alone: {@code requires_lax} is set
     * when the listing states the model cannot run at zero retention (Mantle's
     * {@code allowed_modes}) and removed otherwise, whatever the ancestor the entry inherited
     * its shape from or the classifier's proposal said. An ancestor's posture belongs to the
     * ancestor's surface, a classifier does not get to assert one, and on a surface whose
     * listing states no retention modes the ping is what says whether the model runs at zero
     * retention. Runs before the ping, because the posture decides how the ping is routed.
     */
    private static void retentionFromListing(ObjectNode entry, DiscoveredModel listing) {
        if (listing != null && Boolean.TRUE.equals(listing.requiresLax())) {
            entry.put("requires_lax", true);
        }
        else {
            entry.remove("requires_lax");
        }
    }

    /** Whether a verdict after a ping keeps a new entry: only a refusal of the model itself drops it. */
    private static boolean keptAfterPing(Verdict verdict) {
        return verdict != Verdict.UNREACHABLE && verdict != Verdict.NON_ZDR;
    }

    /**
     * Classifies the unknowns with the strongest model this run can call - the person's own
     * mechanism, on the record: the classifier proposes entries, {@link ModelClassifier#rejection}
     * gates each proposal deterministically (only a listed model of its own provider, never an
     * existing id), every accepted entry is pinged live like any other and dropped when
     * unreachable, and both the entry's note and the report name the classifier so a person
     * knows which facts are its judgment. Returns why nothing was classified, or null.
     */
    private String classify(Map<String, ObjectNode> entries, Set<String> fromPrevious,
                            Map<String, List<DiscoveredModel>> unknown, Map<String, EntryReport> entryReports,
                            List<String> classified, List<String> classifiedDropped, List<String> nonZdr, String today) {
        ModelSpec strongest = strongestCallable(entries, fromPrevious, entryReports);
        if (strongest == null) {
            return "no open, configured, reachable model in the catalog to classify with";
        }
        classifierId = strongest.getId();
        // one call per model, all in parallel on the same seat: the wall time is one small
        // call whatever the count, no answer is big enough to truncate, and a call that fails
        // costs exactly its own model - it stays on the person's list with the failure named,
        // while every other model lands. Each call carries its platform's other unclassified
        // listings as family context, which the newest-only rule needs to see.
        String referenceBlock = ModelClassifier.reference(entries);
        Map<String, List<Map.Entry<String, DiscoveredModel>>> byPlatform = new LinkedHashMap<>();
        unknown.forEach((key, models) -> {
            String platform = platformOf(key);
            for (DiscoveredModel model : models) {
                if (Boolean.TRUE.equals(model.retired())) {
                    // the vendor's word: a retired model stays on the person's list, flagged, and
                    // is not worth a classification call or the ping that would refuse it
                    continue;
                }
                byPlatform.computeIfAbsent(platform, p -> new ArrayList<>()).add(Map.entry(key, model));
            }
        });
        Map<Map.Entry<String, DiscoveredModel>, Future<List<ObjectNode>>> calls = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<Map.Entry<String, DiscoveredModel>> platformListings : byPlatform.values()) {
                for (Map.Entry<String, DiscoveredModel> listing : platformListings) {
                    calls.put(listing, executor.submit(() -> ModelClassifier.parse(
                            classifier.call(providers.get(strongest.getProviderKey()), strongest,
                                    ModelClassifier.prompt(referenceBlock, listing, platformListings)))));
                }
            }
        }
        List<ObjectNode> proposals = new ArrayList<>();
        String modelFailure = null;
        int failedModels = 0;
        for (Map.Entry<Map.Entry<String, DiscoveredModel>, Future<List<ObjectNode>>> call : calls.entrySet()) {
            try {
                proposals.addAll(call.getValue().get());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted while classifying";
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                failedModels++;
                if (modelFailure == null) {
                    modelFailure = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                }
                log.warn("Classifying {} failed; it stays on the person's list",
                        call.getKey().getValue().wireModelId(), cause);
            }
        }
        if (failedModels == calls.size() && !calls.isEmpty()) {
            return modelFailure;
        }
        record Accepted(ObjectNode proposal, String id, String key, String wireId, ModelSpec spec,
                        boolean disabled, DiscoveredModel listedModel) {}
        List<Accepted> accepted = new ArrayList<>();
        Set<String> claimedWires = new HashSet<>();
        for (ObjectNode proposal : proposals) {
            String rejection = ModelClassifier.rejection(proposal, unknown, entries, providers);
            if (rejection == null && !claimedWires.add(proposal.get("wire_model_id").asText())) {
                // parallel per-model calls each answer for their own model; a call that
                // disobeyed and answered for a sibling must not double it
                rejection = "wire_model_id " + proposal.get("wire_model_id").asText() + " was already classified in this run";
            }
            if (rejection != null) {
                log.warn("A classification proposal was refused ({}): {}", rejection, proposal);
                continue;
            }
            String id = proposal.get("id").asText();
            String key = proposal.get("provider_key").asText();
            String wireId = proposal.get("wire_model_id").asText();
            // the vendor's tier word in the listed name is a fact and decides the rung by itself;
            // the classifier's judgment covers only a name that carries none
            String tierWord = proposal.hasNonNull("grade") ? GradeCriterion.tierWord(wireId) : null;
            String verify = "context, output ceilings, prices and limits";
            if (tierWord != null) {
                Grade tier = GradeCriterion.fromName(wireId);
                if (!tier.name().equals(proposal.get("grade").asText())) {
                    log.info("The classifier graded {} {}; its name carries the tier word '{}', which decides {}",
                            wireId, proposal.get("grade").asText(), tierWord, tier);
                }
                proposal.put("grade", tier.name());
                verify = "grade " + tier + " from '" + tierWord + "' in its name; verify " + verify;
            }
            else {
                verify = "verify grade, " + verify;
            }
            proposal.put("note", "added " + today + " by discovery: classified by " + classifierId + "; " + verify);
            proposal.put("unverified", true);
            retentionFromListing(proposal, ModelClassifier.listedOnPlatform(wireId, key, unknown, providers));
            ModelSpec spec;
            try {
                spec = seed.specOf(proposal);
            }
            catch (RuntimeException e) {
                log.warn("A classification proposal did not resolve to a spec ({}): {}", e.getMessage(), proposal);
                continue;
            }
            // the deployment's deliberate choice outranks the classifier's judgment: a new
            // version of a family disabled on this platform arrives disabled, whatever the
            // proposal said, and is not worth a ping - the same rule inherited entries follow
            boolean disabled = statusOf(proposal) == ModelStatus.DISABLED
                    || familyDisabled(platformOf(key), ModelLineage.of(proposal.get("identity").asText()), entries);
            entries.put(id, proposal);
            accepted.add(new Accepted(proposal, id, key, wireId, spec, disabled,
                    ModelClassifier.listedOnPlatform(wireId, key, unknown, providers)));
        }
        // a person is waiting behind this run: every accepted entry pings concurrently (each
        // ping touches only its own proposal node), and the shared maps are written back here
        Map<String, Future<Pinged>> pings = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Accepted a : accepted) {
                if (!a.disabled()) {
                    pings.put(a.id(), executor.submit(() -> pingAndApply(providers.get(a.key()), a.spec(), a.proposal(), a.listedModel())));
                }
            }
        }
        for (Accepted a : accepted) {
            if (a.disabled()) {
                a.proposal().put("status", ModelStatus.DISABLED.name());
                entryReports.put(a.id(), new EntryReport(a.id(), a.key(), Verdict.SKIPPED, false, true, null,
                        null, intOrNull(a.proposal(), "tpm"), null, intOrNull(a.proposal(), "rpm"),
                        a.wireId(), a.wireId(), "classified by " + classifierId + "; its family is DISABLED here, added disabled, not pinged", true));
                classified.add(a.id());
                ModelClassifier.removeFromPlatform(a.wireId(), a.key(), unknown, providers);
                continue;
            }
            Pinged pinged;
            try {
                pinged = pings.get(a.id()).get();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted while pinging the classified entries";
            }
            catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            }
            Verdict verdict = pinged.verdict();
            if (!keptAfterPing(verdict)) {
                // the judgment stood, the account's own answer did not: this deployment
                // cannot call the model, and the drop is reported as its own list so the
                // page never claims an entry the file does not carry
                entries.remove(a.id());
            }
            entryReports.put(a.id(), new EntryReport(a.id(), a.key(), verdict, false, keptAfterPing(verdict), pinged.outcome(),
                    null, intOrNull(a.proposal(), "tpm"), null, intOrNull(a.proposal(), "rpm"),
                    a.wireId(), a.wireId(), "classified by " + classifierId + ", verify", true));
            switch (verdict) {
                case UNREACHABLE -> classifiedDropped.add(a.id());
                case NON_ZDR -> nonZdr.add(a.id() + "  (" + a.key() + ": " + a.wireId() + ")");
                default -> classified.add(a.id());
            }
            ModelClassifier.removeFromPlatform(a.wireId(), a.key(), unknown, providers);
        }
        return failedModels > 0
                ? failedModels + " of " + calls.size() + " model classifications failed (" + modelFailure
                        + "); those stay on the list below"
                : null;
    }

    /**
     * The strongest model this deployment can call RIGHT NOW, the classifier's seat: the
     * highest rung among open entries whose provider holds a credential, the most expensive
     * output price breaking the tie - and callable means callable: an entry gated behind a
     * data-share posture the deployment has not provisioned, or one this very run's ping
     * already found unreachable, is capability on paper and no seat.
     */
    private ModelSpec strongestCallable(Map<String, ObjectNode> entries, Set<String> fromPrevious,
                                        Map<String, EntryReport> entryReports) {
        ObjectNode best = null;
        int bestRung = -1;
        double bestPrice = -1;
        for (ObjectNode entry : entries.values()) {
            if (statusOf(entry) != ModelStatus.OPEN || !entry.hasNonNull("grade")) {
                continue;
            }
            ClientProvider<?> provider = providers.get(entry.get("provider_key").asText());
            if (provider == null || !provider.configured()) {
                continue;
            }
            if (entry.path("requires_lax").asBoolean(false) && Settings.get(ModelSettings.class).mantleLaxProject == null) {
                continue;
            }
            EntryReport report = entryReports.get(entry.get("id").asText());
            if (report != null && (report.verdict() == Verdict.UNREACHABLE || report.verdict() == Verdict.UNVERIFIED)) {
                continue;
            }
            int rung = Grade.rungs().indexOf(Grade.valueOf(entry.get("grade").asText()));
            double price = entry.hasNonNull("output_price_per_million") ? entry.get("output_price_per_million").asDouble() : 0;
            if (rung > bestRung || (rung == bestRung && price > bestPrice)) {
                best = entry;
                bestRung = rung;
                bestPrice = price;
            }
        }
        if (best == null) {
            return null;
        }
        return (fromPrevious.contains(best.get("id").asText()) && previous != null ? previous : seed).specOf(best);
    }

    /**
     * One ping, repeated up to {@link #PING_ATTEMPTS} times while the failure is transient. A
     * transient failure is the provider's minute, not the entry's standing, and a verdict that
     * drops a model from the catalog must not rest on one 503.
     */
    /** A ping and what it decided about the entry. */
    private record Pinged(ProbeOutcome outcome, Verdict verdict) {}

    /**
     * Pings an entry and writes what the ping observed onto it when the model answered: an
     * answer is reachable, a failure that says nothing about the entry is unverified, a refusal
     * of the account's retention mode is the model's posture rather than its reach, any other
     * failure is unreachable. An entry this process cannot route is not pinged at all - its
     * compliance envelope refuses the model, or the model needs the LAX project and none is
     * configured: either is the deployment's own state, and says nothing about the account.
     * What each verdict costs the entry is the caller's rule.
     */
    private Pinged pingAndApply(ClientProvider<?> provider, ModelSpec spec, ObjectNode entry, DiscoveredModel discovered) {
        if (!envelope.permits(spec) || (spec.requiresLax() && Settings.get(ModelSettings.class).mantleLaxProject == null)) {
            return new Pinged(null, Verdict.NOT_ROUTABLE_HERE);
        }
        ProbeOutcome outcome = pingWithRetry(provider, spec);
        if (outcome.getStatus() == ProbeOutcome.Status.OK) {
            apply(entry, discovered, outcome);
            return new Pinged(outcome, Verdict.REACHABLE);
        }
        if (outcome.getErrorMessage() != null && outcome.getErrorMessage().contains(ZERO_RETENTION_REFUSAL)) {
            return new Pinged(outcome, Verdict.NON_ZDR);
        }
        return new Pinged(outcome, transientFailure(outcome) ? Verdict.UNVERIFIED : Verdict.UNREACHABLE);
    }

    private ProbeOutcome pingWithRetry(ClientProvider<?> provider, ModelSpec spec) {
        ProbeOutcome outcome = ping.apply(provider, spec);
        for (int attempt = 2; attempt <= PING_ATTEMPTS && transientFailure(outcome); attempt++) {
            log.info("{} answered {} {} on attempt {}; retrying in {}", spec.getId(), outcome.getClassification(),
                    outcome.getErrorClass(), attempt - 1, retryDelay);
            try {
                Thread.sleep(retryDelay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return outcome;
            }
            outcome = ping.apply(provider, spec);
        }
        return outcome;
    }

    private static String prefix(String wireId) {
        return ModelLineage.prefix(wireId);
    }

    private static String bare(String wireId) {
        return ModelLineage.bare(wireId);
    }

    /**
     * The listed ids that are the same model as {@code wireId} on this region's own geography
     * profile, sorted: normally one, which the discovery writes in place of the seed's; none
     * for a {@code global.} id (the same string everywhere) or a model the region has no
     * profile for; more than one only if a region ever lists two geographies, which is a
     * person's call. A bare on-demand id is included: where the region has a profile for the
     * model, the profile is what AWS serves the model on, and on-demand invocation of the
     * bare id is refused for the newer ones.
     */
    static List<String> regionsProfiles(String wireId, Collection<String> listed) {
        if (ModelLineage.GLOBAL.equals(prefix(wireId))) {
            return List.of();
        }
        String model = bare(wireId);
        List<String> profiles = new ArrayList<>();
        for (String candidate : listed) {
            String head = prefix(candidate);
            if (head != null && !ModelLineage.GLOBAL.equals(head) && !candidate.equals(wireId) && bare(candidate).equals(model)) {
                profiles.add(candidate);
            }
        }
        Collections.sort(profiles);
        return profiles;
    }

    /**
     * The models a provider's account lists that no entry names: each gets an entry when the
     * catalog holds an ancestor to inherit from on the same key (a newer version of a known
     * family), or arrives disabled when the deployment disabled the same model on another key
     * of the platform; a model with neither is reported for a person to write. A newer model
     * of a disabled family arrives disabled; a newer model of a pinned one is announced and the
     * pin left alone; an older version of a known model is left out unless asked for; a model
     * that produces no text has no seat and is left out.
     */
    private void addListed(String key, ClientProvider<?> provider, Map<String, DiscoveredModel> listed,
                           Map<String, ObjectNode> entries, Set<String> fromPrevious,
                           Set<String> pinnedIds, Map<String, EntryReport> entryReports,
                           Map<String, List<DiscoveredModel>> unknown, Set<String> reportedUnknown,
                           List<String> newerThanPinned, List<String> olderLeftOut, List<String> unserved, List<String> nonZdr, String today) {
        Set<String> seen = new HashSet<>();
        for (DiscoveredModel model : listed.values()) {
            String bareId = bare(model.wireModelId());
            if (!seen.add(bareId)) {
                continue;
            }
            DiscoveredModel chosen = listed.get(representative(bareId, listed.keySet()));
            String identity = provider.identityOf(chosen.wireModelId());
            if (knownOn(key, bareId, identity, entries)) {
                continue;
            }
            if (!fits(key, provider, chosen)) {
                // another key of the platform may hold the client for it; when none does, the
                // account offers a model the runtime cannot call, said once
                if (!fitsOnPlatform(key, chosen) && !knownOnPlatform(platformOf(key), bareId, identity, entries) && reportedUnknown.add(identity)) {
                    unserved.add(chosen.wireModelId() + (chosen.note() != null ? "  " + chosen.note() : ""));
                }
                continue;
            }
            if (chosen.otherModality()) {
                if (!knownOnPlatform(platformOf(key), bareId, identity, entries) && reportedUnknown.add(identity)) {
                    addSeatless(key, provider, chosen, identity, entries, entryReports, today);
                }
                continue;
            }
            ModelLineage lineage = ModelLineage.of(identity);
            ObjectNode ancestor = null;
            ObjectNode newest = null;
            for (ObjectNode entry : entries.values()) {
                if (!key.equals(entry.get("provider_key").asText())) {
                    continue;
                }
                ModelLineage candidate = ModelLineage.of(entry.get("identity").asText());
                if (!candidate.sameFamily(lineage)) {
                    continue;
                }
                if (newest == null || candidate.compareVersion(ModelLineage.of(newest.get("identity").asText())) > 0) {
                    newest = entry;
                }
                if (candidate.compareVersion(lineage) < 0
                        && (ancestor == null || candidate.compareVersion(ModelLineage.of(ancestor.get("identity").asText())) > 0)) {
                    ancestor = entry;
                }
            }
            if (newest == null) {
                ObjectNode twin = disabledTwin(key, identity, entries);
                if (twin != null) {
                    addDisabled(key, provider, chosen, identity, twin, entries, entryReports,
                            "added " + today + " by discovery, disabled: " + identity + " is disabled on " + twin.get("provider_key").asText()
                                    + ", the same platform");
                }
                else if (!knownOnPlatform(platformOf(key), bareId, identity, entries) && reportedUnknown.add(identity)) {
                    // named on another key of this platform: the seed chose its channel and there
                    // is nothing here to inherit from, so it is neither added nor a person's chore
                    unknown.computeIfAbsent(key, k -> new ArrayList<>()).add(chosen);
                }
                continue;
            }
            if (ancestor == null || ModelLineage.of(newest.get("identity").asText()).compareVersion(lineage) >= 0) {
                if (!includeOlder) {
                    olderLeftOut.add(chosen.wireModelId() + "  (" + key + ": " + identity + " is not newer than " + newest.get("id").asText() + ")");
                    continue;
                }
                ancestor = newest;
            }
            String newId = provider.catalogIdOf(identity, chosen.wireModelId());
            if (entries.containsKey(newId)) {
                log.warn("{} listed {} whose catalog id {} is already taken; left out", key, chosen.wireModelId(), newId);
                continue;
            }
            ObjectNode entry = ancestor.deepCopy();
            entry.put("id", newId);
            entry.put("identity", identity);
            entry.put("wire_model_id", chosen.wireModelId());
            entry.put("provider_key", key);
            retentionFromListing(entry, chosen);
            String ancestorId = ancestor.get("id").asText();
            boolean disabled = statusOf(ancestor) == ModelStatus.DISABLED || familyDisabled(platformOf(key), lineage, entries);
            if (disabled) {
                entry.put("status", ModelStatus.DISABLED.name());
            }
            else {
                entry.remove("status");
            }
            entry.put("note", "added " + today + " by discovery: " + identity + " listed by the account, newer than " + ancestorId
                    + "; shape inherited from it, verify grade, context, output ceilings and prices");
            // an inherited shape is the discovery's inference, the prices included: marked until a person confirms it
            entry.put("unverified", true);
            for (String pinnedId : pinnedIds) {
                ObjectNode pinned = entries.get(pinnedId);
                if (pinned != null && key.equals(pinned.get("provider_key").asText())
                        && lineage.newerThan(ModelLineage.of(pinned.get("identity").asText()))) {
                    newerThanPinned.add(newId + " is newer than " + pinnedId + ", which is pinned; the pin is unchanged");
                }
            }
            entries.put(newId, entry);
            if (disabled) {
                entryReports.put(newId, new EntryReport(newId, key, Verdict.SKIPPED, false, true, null,
                        intOrNull(entry, "tpm"), intOrNull(entry, "tpm"), intOrNull(entry, "rpm"), intOrNull(entry, "rpm"),
                        chosen.wireModelId(), chosen.wireModelId(), "newer than " + ancestorId + ", which is DISABLED; added disabled, not pinged", true));
                continue;
            }
            ModelSpec spec = (fromPrevious.contains(ancestorId) && previous != null ? previous : seed).specOf(entry);
            Integer tpmBefore = intOrNull(entry, "tpm");
            Integer rpmBefore = intOrNull(entry, "rpm");
            Pinged pinged = pingAndApply(provider, spec, entry, chosen);
            Verdict verdict = pinged.verdict();
            if (!keptAfterPing(verdict)) {
                entries.remove(newId);
                if (verdict == Verdict.NON_ZDR) {
                    nonZdr.add(newId + "  (" + key + ": " + chosen.wireModelId() + ")");
                }
            }
            entryReports.put(newId, new EntryReport(newId, key, verdict, false, keptAfterPing(verdict), pinged.outcome(),
                    tpmBefore, intOrNull(entry, "tpm"), rpmBefore, intOrNull(entry, "rpm"),
                    chosen.wireModelId(), chosen.wireModelId(), "newer than " + ancestorId + ", shape inherited", true));
        }
    }

    /**
     * Whether a key is the one to hold a listed model: its client speaks the model's family
     * ({@link ClientProvider#serves}), and the model's output fits the key's kind - an
     * embeddings model rides an embeddings key and an LLM rides an LLM key, which the platform's
     * shared listing does not sort out by itself. A listing that names no modalities fits any
     * key that serves it, and the classifier sorts the kind out by name as before.
     */
    private static boolean fits(String key, ClientProvider<?> provider, DiscoveredModel model) {
        if (!provider.serves(model.wireModelId())) {
            return false;
        }
        Boolean embeddings = model.embeddingsOutput();
        return embeddings == null || model.otherModality() || embeddings == key.endsWith("-embeddings");
    }

    /**
     * Whether some key of the listing key's platform both lists the model and fits it, this
     * key included. A sibling that would serve the family but does not list the id (Mantle's
     * catalog names vendors the legacy runtime does not know) is no home for it.
     */
    private boolean fitsOnPlatform(String key, DiscoveredModel model) {
        String platform = platformOf(key);
        for (Map.Entry<String, Map<String, DiscoveredModel>> e : listedByKey.entrySet()) {
            DiscoveredModel theirs = e.getValue().get(model.wireModelId());
            if (theirs != null && Objects.equals(platform, platformOf(e.getKey())) && fits(e.getKey(), providers.get(e.getKey()), theirs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the entry of a model the account offers in a modality the runtime has no client
     * for: image generation, speech, video. The entry states what the model takes and produces
     * and nothing a call would need - no grade, no ceilings, no prices - so the catalog says
     * what the account has, no picker lands on it, and it is never pinged. A person who adds a
     * client for the modality later moves it to that client's key by hand.
     */
    private void addSeatless(String key, ClientProvider<?> provider, DiscoveredModel model, String identity,
                             Map<String, ObjectNode> entries, Map<String, EntryReport> entryReports, String today) {
        String newId = provider.catalogIdOf(identity, model.wireModelId());
        if (entries.containsKey(newId)) {
            log.warn("{} listed {} whose catalog id {} is already taken; left out", key, model.wireModelId(), newId);
            return;
        }
        ObjectNode entry = NucleoJsonSerializer.createObjectNode();
        entry.put("id", newId);
        entry.put("identity", identity);
        entry.put("provider_key", key);
        entry.put("wire_model_id", model.wireModelId());
        ArrayNode inputs = entry.putArray("input_modalities");
        model.inputModalities().forEach(inputs::add);
        ArrayNode outputs = entry.putArray("output_modalities");
        model.outputModalities().forEach(outputs::add);
        if (Boolean.TRUE.equals(model.supportsVision()) || model.inputModalities().contains("IMAGE")) {
            entry.put("supports_vision", true);
        }
        String modalities = "takes " + String.join(", ", model.inputModalities()) + ", produces " + String.join(", ", model.outputModalities());
        entry.put("note", "added " + today + " by discovery: listed by the account; " + modalities
                + "; the runtime has no client for that modality, so it has no seat and is never called");
        entries.put(newId, entry);
        entryReports.put(newId, new EntryReport(newId, key, Verdict.SKIPPED, false, true, null, null, null, null, null,
                model.wireModelId(), model.wireModelId(), modalities + "; no seat in the runtime, not pinged", true));
    }

    /** The same model, disabled by the deployment on another key of the same platform, or null. */
    private ObjectNode disabledTwin(String key, String identity, Map<String, ObjectNode> entries) {
        String platform = platformOf(key);
        for (ObjectNode entry : entries.values()) {
            String otherKey = entry.get("provider_key").asText();
            if (!otherKey.equals(key) && platform != null && platform.equals(platformOf(otherKey))
                    && identity.equals(entry.get("identity").asText()) && statusOf(entry) == ModelStatus.DISABLED) {
                return entry;
            }
        }
        return null;
    }

    /** An entry the deployment disabled elsewhere on the platform, written on this key disabled and never pinged. */
    private void addDisabled(String key, ClientProvider<?> provider, DiscoveredModel chosen, String identity, ObjectNode template,
                             Map<String, ObjectNode> entries, Map<String, EntryReport> entryReports, String note) {
        String newId = provider.catalogIdOf(identity, chosen.wireModelId());
        if (entries.containsKey(newId)) {
            return;
        }
        ObjectNode entry = template.deepCopy();
        entry.put("id", newId);
        entry.put("wire_model_id", chosen.wireModelId());
        entry.put("provider_key", key);
        // the spec shape is the key's, not the template's: keep it only where this key already uses it
        if (entry.has("spec_type") && entries.values().stream().noneMatch(e -> key.equals(e.get("provider_key").asText())
                && e.has("spec_type") && e.get("spec_type").asText().equals(entry.get("spec_type").asText()))) {
            entry.remove("spec_type");
        }
        entry.put("status", ModelStatus.DISABLED.name());
        entry.put("note", note);
        entries.put(newId, entry);
        entryReports.put(newId, new EntryReport(newId, key, Verdict.SKIPPED, false, true, null,
                intOrNull(entry, "tpm"), intOrNull(entry, "tpm"), intOrNull(entry, "rpm"), intOrNull(entry, "rpm"),
                chosen.wireModelId(), chosen.wireModelId(), note, true));
    }

    /** Whether the deployment disabled any model of the family on the platform. */
    private boolean familyDisabled(String platform, ModelLineage lineage, Map<String, ObjectNode> entries) {
        for (ObjectNode entry : entries.values()) {
            if (statusOf(entry) == ModelStatus.DISABLED && platform != null && platform.equals(platformOf(entry.get("provider_key").asText()))
                    && ModelLineage.of(entry.get("identity").asText()).sameFamily(lineage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Of a listed model's ids, the one to write: the region's own geography profile when it
     * has one, else the global profile, else the bare on-demand id.
     */
    private static String representative(String bareId, Collection<String> listed) {
        List<String> profiles = regionsProfiles(bareId, listed);
        if (profiles.size() == 1) {
            return profiles.get(0);
        }
        String global = ModelLineage.GLOBAL + "." + bareId;
        if (listed.contains(global)) {
            return global;
        }
        if (listed.contains(bareId)) {
            return bareId;
        }
        return profiles.isEmpty() ? bareId : profiles.get(0);
    }

    private String platformOf(String key) {
        ClientProvider<?> provider = providers.get(key);
        return provider != null ? provider.platform() : null;
    }

    /**
     * Whether an entry on this key names the model: by wire id under any geography (us., eu.
     * and the bare id are one model) or by identity (a surface that spells the same model
     * {@code openai.gpt-oss-120b} where another spells it {@code openai.gpt-oss-120b-1:0}).
     */
    private static boolean knownOn(String key, String bareId, String identity, Map<String, ObjectNode> entries) {
        for (ObjectNode entry : entries.values()) {
            if (key.equals(entry.get("provider_key").asText()) && names(entry, bareId, identity)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an entry on any key of the platform names the model. */
    private boolean knownOnPlatform(String platform, String bareId, String identity, Map<String, ObjectNode> entries) {
        for (ObjectNode entry : entries.values()) {
            if (platform != null && platform.equals(platformOf(entry.get("provider_key").asText())) && names(entry, bareId, identity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(ObjectNode entry, String bareId, String identity) {
        return bareId.equals(bare(entry.get("wire_model_id").asText())) || identity.equals(entry.get("identity").asText());
    }

    private static ModelStatus statusOf(ObjectNode entry) {
        return entry.has("status") ? ModelStatus.valueOf(entry.get("status").asText()) : ModelStatus.OPEN;
    }

    /** Every id the previous file's pins name, for the announcement of a newer model. */
    private Set<String> pinnedIds() {
        Set<String> ids = new HashSet<>();
        ObjectNode pins = previous != null ? previous.rawPins() : null;
        if (pins != null) {
            // a grade's order is a list of ids, the embeddings and decision pins one id each
            pins.properties().forEach(field -> {
                if (field.getValue().isTextual()) {
                    ids.add(field.getValue().asText());
                }
                for (JsonNode id : field.getValue()) {
                    if (id.isTextual()) {
                        ids.add(id.asText());
                    }
                }
            });
        }
        return ids;
    }

    /** Per key and family, every entry but the newest version. */
    private static Set<String> olderVersions(Map<String, ObjectNode> entries) {
        Map<String, ObjectNode> newest = new HashMap<>();
        for (ObjectNode entry : entries.values()) {
            String family = entry.get("provider_key").asText() + " " + ModelLineage.of(entry.get("identity").asText()).family();
            ObjectNode current = newest.get(family);
            if (current == null || ModelLineage.of(entry.get("identity").asText())
                    .compareVersion(ModelLineage.of(current.get("identity").asText())) > 0) {
                newest.put(family, entry);
            }
        }
        Set<String> older = new HashSet<>();
        for (ObjectNode entry : entries.values()) {
            String family = entry.get("provider_key").asText() + " " + ModelLineage.of(entry.get("identity").asText()).family();
            if (newest.get(family) != entry) {
                older.add(entry.get("id").asText());
            }
        }
        return older;
    }

    private static boolean transientFailure(ProbeOutcome outcome) {
        return outcome.getStatus() != ProbeOutcome.Status.OK
                && (outcome.getClassification() == ProbeOutcome.Classification.AVAILABILITY
                    || outcome.getClassification() == ProbeOutcome.Classification.THROTTLE);
    }

    private static ProviderReport inspect(String key, ClientProvider<?> provider) {
        if (!provider.configured()) {
            return new ProviderReport(key, ProviderStatus.NOT_CONFIGURED, provider.describeCredential(), null);
        }
        if (!(provider instanceof ModelDiscovery discovery)) {
            return new ProviderReport(key, ProviderStatus.LISTING_UNSUPPORTED,
                    provider.getClass().getSimpleName() + " does not implement ModelDiscovery; its seed entries are pinged blind", null);
        }
        try {
            List<DiscoveredModel> listed = discovery.listModels();
            log.info("{} listed {} models", key, listed.size());
            return new ProviderReport(key, ProviderStatus.LISTED, null, listed);
        }
        catch (Exception e) {
            UnknownHostException unknownHost = AbstractLLMClient.findUnknownHost(e);
            if (unknownHost != null) {
                // the one listing failure that says everything about the entries: the endpoint
                // is not served where this deployment points, so nothing of this provider can be
                // called from here, and no retry changes DNS
                log.warn("{} is not served where this deployment points: {}", key, unknownHost.getMessage());
                return new ProviderReport(key, ProviderStatus.UNREACHABLE, "UnknownHostException: " + unknownHost.getMessage()
                        + " - the endpoint is not served where this deployment points; its entries from the previous file are closed as UNREACHABLE, its seed entries left out", null);
            }
            log.warn("{} listing failed: {}: {}", key, e.getClass().getSimpleName(), e.getMessage());
            return new ProviderReport(key, ProviderStatus.LISTING_FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage() + "; its seed entries are pinged blind", null);
        }
    }

    /**
     * The observed facts over the entry: limits from the response headers first, else from
     * the listing's quotas; retention from the listing when it exposes it. A fact no source
     * stated leaves the entry's value alone.
     */
    private static void apply(ObjectNode entry, DiscoveredModel discovered, ProbeOutcome outcome) {
        if (outcome.getObservedTokensLimit() != null) {
            entry.put("tpm", outcome.getObservedTokensLimit().intValue());
        }
        else if (discovered != null && discovered.tpm() != null) {
            entry.put("tpm", discovered.tpm());
        }
        if (outcome.getObservedRequestsLimit() != null) {
            entry.put("rpm", outcome.getObservedRequestsLimit().intValue());
        }
        else if (discovered != null && discovered.rpm() != null) {
            entry.put("rpm", discovered.rpm());
        }
        if (discovered != null && discovered.requiresLax() != null) {
            if (discovered.requiresLax()) {
                entry.put("requires_lax", true);
            }
            else {
                entry.remove("requires_lax");
            }
        }
    }

    /** By loading it against the providers it was written for, so every entry is validated under the provider it names. */
    private void validate(ObjectNode catalog) {
        new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("discovery result", catalog.toString(), false)), providers);
    }

    private static Integer intOrNull(ObjectNode node, String field) {
        return node.has(field) && node.get(field).isNumber() ? node.get(field).intValue() : null;
    }

    private static String render(Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Catalog discovery ").append(Instant.now()).append('\n');
        if (!result.newerThanPinned().isEmpty()) {
            sb.append("NEWER THAN A PIN (the pins are unchanged; move one by hand if you want the newer model):\n");
            for (String line : result.newerThanPinned()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        // which file the run read is the first thing a person checks: a run that did not see the
        // deployment's file reads its pins from nowhere, and says so here instead of hiding it
        if (result.previousSource() != null) {
            long carried = result.entries().stream().filter(EntryReport::fromPrevious).count();
            sb.append("Previous file: ").append(result.previousSource()).append(", ").append(carried).append(" entries\n");
        }
        else {
            sb.append("No previous file: a first pull, from the shipped fragments alone\n");
        }
        sb.append("Providers:\n");
        for (ProviderReport p : result.providers()) {
            sb.append("  ").append(String.format("%-28s", p.key()));
            switch (p.status()) {
                case LISTED -> sb.append("listed ").append(p.listed().size()).append(" models");
                case NOT_CONFIGURED -> sb.append("NOT CONFIGURED - provide ").append(p.detail());
                case LISTING_UNSUPPORTED -> sb.append("listing unsupported - ").append(p.detail());
                case LISTING_FAILED -> sb.append("LISTING FAILED - ").append(p.detail());
                case UNREACHABLE -> sb.append("UNREACHABLE - ").append(p.detail());
                case OUT_OF_SCOPE -> sb.append("out of scope - ").append(p.detail());
                case NOT_ON_CLASSPATH -> sb.append("not on this classpath - ").append(p.detail());
            }
            sb.append('\n');
        }
        sb.append("Entries:\n");
        for (EntryReport e : result.entries()) {
            // the widest verdict fills its column exactly, so the gap is explicit
            sb.append(e.added() ? "+ " : "  ").append(String.format("%-34s %-14s ", e.id(), e.verdict()));
            switch (e.verdict()) {
                case REACHABLE -> {
                    sb.append(String.format("%6dms", e.outcome().getLatencyMs()));
                    sb.append(limit("tpm", e.tpmBefore(), e.tpmAfter(), e.outcome().getObservedTokensLimit() != null));
                    sb.append(limit("rpm", e.rpmBefore(), e.rpmAfter(), e.outcome().getObservedRequestsLimit() != null));
                    if (e.outcome().getServedModelId() != null) {
                        sb.append("  served ").append(e.outcome().getServedModelId());
                    }
                    if (e.outcome().getObservedTokensLimit() == null && e.outcome().getObservedRequestsLimit() == null) {
                        Map<String, String> headers = e.outcome().getHeaders();
                        sb.append("  [no rate-limit headers");
                        if (headers != null && !headers.isEmpty()) {
                            sb.append(" among ").append(headers.size()).append(": ")
                                    .append(String.join(", ", new TreeSet<>(headers.keySet())));
                        }
                        sb.append(']');
                    }
                }
                case UNREACHABLE -> {
                    if (e.outcome() == null) {
                        // the provider itself is unreachable from here: no ping was spent, the note says why
                        sb.append(e.note());
                    }
                    else {
                        sb.append(e.outcome().getClassification()).append(' ')
                                .append(e.outcome().getErrorClass()).append(": ").append(oneLine(e.outcome().getErrorMessage()))
                                .append(e.kept() ? "  (kept from your previous file, closed as UNREACHABLE)" : "  (seed entry left out)");
                    }
                }
                case UNVERIFIED -> sb.append("transient ").append(e.outcome().getClassification()).append(' ')
                        .append(e.outcome().getErrorClass()).append(": ").append(oneLine(e.outcome().getErrorMessage()))
                        .append("  (").append(PING_ATTEMPTS).append(" attempts; kept with the limits it had, unverified - run again)");
                case NOT_LISTED -> sb.append(e.kept() ? "the account does not list it any more (kept from your previous file, closed as UNLISTED)"
                                                       : "the account does not list it (seed entry left out)");
                case NOT_CONFIGURED -> sb.append(e.kept() ? "provider not configured (kept from your previous file, unverified)"
                                                           : "provider not configured (seed entry left out)");
                case SKIPPED -> sb.append("kept");
                case OLDER -> sb.append("an older version of a model this key has newer (a first pull leaves those out; --include-older adds them)");
                case NOT_ROUTABLE_HERE -> sb.append("this process cannot route the model (its compliance envelope refuses it, or the model needs the"
                        + " Mantle LAX project and none is configured); kept, not pinged - the account's reach is not in question");
                case NON_ZDR -> sb.append("the model does not offer the zero-retention mode this account runs at: ")
                        .append(oneLine(e.outcome().getErrorMessage()))
                        .append(e.kept() ? "  (kept from your previous file)" : "  (no entry on this surface)");
            }
            if (e.relocated()) {
                sb.append("  [").append(e.wireBefore()).append(" -> ").append(e.wireAfter()).append(", this region's own profile for the model]");
            }
            if (e.note() != null) {
                sb.append("  [").append(e.note()).append(']');
            }
            sb.append('\n');
        }
        // the pins as the run saw them, each with its entry's verdict: the check a person makes
        // after writing pins is that every one of them is REACHABLE, and this is that check
        JsonNode pins = result.catalog().get("pins");
        sb.append("Pins:\n");
        if (pins == null || pins.isEmpty()) {
            sb.append("  none: each grade is served by its cheapest reachable entry until an order is written\n");
        }
        else {
            Map<String, EntryReport> byId = new HashMap<>();
            for (EntryReport e : result.entries()) {
                byId.put(e.id(), e);
            }
            // one line per id, a grade's order numbered from its default
            pins.properties().forEach(pin -> {
                List<String> ids = new ArrayList<>();
                if (pin.getValue().isTextual()) {
                    ids.add(pin.getValue().asText());
                }
                for (JsonNode id : pin.getValue()) {
                    ids.add(id.asText());
                }
                for (int i = 0; i < ids.size(); i++) {
                    EntryReport e = byId.get(ids.get(i));
                    String slot = pin.getValue().isArray() ? pin.getKey() + " " + (i + 1) : pin.getKey();
                    sb.append(String.format("  %-11s %-34s %s%n", slot, ids.get(i),
                            e == null ? "NOT IN THE CATALOG" : e.verdict() + (e.kept() ? "" : " (left out)")));
                }
            });
        }
        if (!result.olderLeftOut().isEmpty()) {
            sb.append("Older versions the account lists, left out (rerun with --include-older to add them):\n");
            for (String line : result.olderLeftOut()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        if (!result.nonZdr().isEmpty()) {
            sb.append("Served only under provider data share - the model refuses the zero-retention mode this account runs at,"
                    + " and this surface routes it no other way, so there is no entry here (the Mantle listing is where it is reachable):\n");
            for (String line : result.nonZdr()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        if (!result.unserved().isEmpty()) {
            sb.append("Listed by the account, no client in the runtime speaks its request shape on this platform, left out:\n");
            for (String line : result.unserved()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        if (result.classifiedBy() != null) {
            sb.append(result.classified().isEmpty() && result.classifiedDropped().isEmpty()
                    ? "Classifier " + result.classifiedBy() + " was consulted and classified nothing it was confident about\n"
                    : "Classified by " + result.classifiedBy() + " (its judgment, pinged live; each entry's note says what to"
                            + " verify): " + String.join(", ", result.classified()) + '\n');
            if (!result.classifiedDropped().isEmpty()) {
                sb.append("Classified but dropped - this account's own ping refused them: ")
                        .append(String.join(", ", result.classifiedDropped())).append('\n');
            }
        }
        if (result.classifierFailure() != null) {
            sb.append("Unknowns left unclassified: ").append(result.classifierFailure()).append('\n');
        }
        if (!result.unknown().isEmpty()) {
            sb.append("Listed by the account, no entry and no ancestor to inherit a shape from (a person writes these, or asks the skill):\n");
            result.unknown().forEach((provider, models) -> {
                for (DiscoveredModel m : models) {
                    sb.append("  ").append(String.format("%-28s", provider)).append(m.wireModelId());
                    if (m.note() != null) {
                        sb.append("  ").append(m.note());
                    }
                    if (Boolean.TRUE.equals(m.retired())) {
                        sb.append("  [provider marks it retired]");
                    }
                    sb.append('\n');
                }
            });
        }
        return sb.toString();
    }

    /** Exact numbers with grouping: a limit is what admission reserves against, and a skim rounding would misstate it. */
    private static String limit(String name, Integer before, Integer after, boolean observed) {
        if (after == null) {
            return "";
        }
        if (before != null && before.equals(after)) {
            return "  " + name + " " + String.format("%,d", after) + (observed ? " (observed, unchanged)" : "");
        }
        return "  " + name + " " + (before != null ? String.format("%,d", before) : "none") + " -> " + String.format("%,d", after)
                + (observed ? " (observed)" : " (quota)");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
