/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Default {@link ModelsBackend}: the catalog discovered on the classpath the way logback finds
 * its configuration.
 *
 * <p>The deployment's own file is the catalog when it exists, found the way logback finds its
 * configuration, the same way on every machine and in every launch mode: the file the
 * {@code nucleo.models} system property names, when it is set; else {@code models.json} on the
 * classpath, every occurrence enumerated and the first taken - the classpath orders the
 * application's own classes before its dependencies, so the nearest file to the class being run
 * wins, and the others are named in a warning. An application carries its catalog the way it
 * carries {@code logback.xml}, in {@code src/main/resources}, and the property is the override.
 * Where a catalog is written is the writer's decision:
 * {@link ai.redouble.nucleo.harness.models.discovery.CatalogDiscovery CatalogDiscovery} writes the
 * file it is told to. The file is
 * complete and alone - the deployment decides what exists, what it costs and what it may carry,
 * and nothing shipped in a jar is consulted beside it. Only when no such file exists does the
 * runtime fall back to the <b>fragments</b>: every {@code META-INF/nucleo/seed_models.json} on the
 * classpath, one per provider artifact, carrying the public facts of the models that provider
 * serves and its published entry-tier limits, so a fresh checkout with one credential runs
 * before anyone authors anything. Two fragments carrying the same id is a load error, and a
 * fragment may not declare {@code pins}: pins are the deployment's.
 *
 * <p>Within a file, the {@code provider_defaults} object (per provider key) sits under each
 * entry of the {@code models} array, so an entry carries only what differs from its provider's
 * defaults. The explicit {@code spec_type} selects the concrete {@link ModelSpec} subtype,
 * hand-dispatched through {@link NucleoJsonSerializer#convert} (no Jackson subtype machinery),
 * then validated fail-fast so a misspelled key throws at load rather than silently defaulting a
 * limit to zero. Field names are snake_case to match the framework serializer's naming strategy.
 *
 * <p>A written catalog records the providers it was written for, with their platform and
 * addressing, and each entry's platform ({@link ProviderLinks}). An entry whose provider this
 * classpath does not carry is linked, in memory and before its spec is built, to a provider here
 * of the same addressing; an entry nothing here can serve (no provider here of its addressing
 * fits, or it states no platform and no artifact here declares its spec type) is left out of the
 * loaded catalog, with any pin
 * naming it. Either way, and whenever the recorded providers differ from this classpath's, the
 * runtime's own load (the no-argument and named-file constructors) warns once per source, saying
 * what differs and what was done, and once more for the pins it dropped; a view over the same
 * file opened by a tool or a status page does not repeat it. The file
 * is never rewritten by a load, only by the discovery. An entry that states no platform, in a catalog that records none for its
 * provider, loads as written.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class JsonModelsBackend implements ModelsBackend {
    private static final Logger log = LoggerFactory.getLogger(JsonModelsBackend.class);
    /** System property naming the deployment's catalog file, taking precedence over the classpath. */
    public static final String PROPERTY = "nucleo.models";
    /** The deployment's catalog as a classpath resource, enumerated across the whole classpath, nearest first. */
    public static final String CLASSPATH_RESOURCE = "models.json";
    /** The fragment each provider artifact ships; every copy on the classpath loads when the deployment has no file of its own. */
    public static final String FRAGMENT_RESOURCE = "META-INF/nucleo/seed_models.json";
    /** The one spec type the core defines; every other {@code spec_type} is declared by the provider artifact that serves it. */
    private static final String STANDARD_SPEC_TYPE = "standard";

    /** One catalog source: where it came from (for messages), its text, and whether it is a fragment. */
    public record Layer(String name, String json, boolean fragment) {}

    private final Map<String, ModelSpec> byId = new LinkedHashMap<>();
    private final Map<String, ModelSpec> byWireId = new HashMap<>();
    /** Each entry exactly as its source wrote it, before the provider defaults were merged under it; the discovery rewrites from these. */
    private final Map<String, ObjectNode> rawById = new LinkedHashMap<>();
    /** The provider defaults of every source, merged per provider key. */
    private final ObjectNode rawProviderDefaults = NucleoJsonSerializer.createObjectNode();
    private ObjectNode rawPins;
    /** The record of the providers the catalog was written for, as written, or null when it records none. */
    private JsonNode rawProviders;
    private CatalogPins pins;
    /** Where the catalog came from, as the layers named themselves: a path, a resource, a jar entry. */
    private String source;

    /** Where this catalog came from, as its layers named themselves. */
    public String source() {
        return source;
    }

    /**
     * The runtime's catalog, as the {@link Models} facade loads it: the only load that warns about
     * entries linked or left out for this classpath, so a process says it once per load and not
     * once per view a tool or a status page opens on the same file.
     */
    public JsonModelsBackend() {
        load(discover(), ClientProviders.all(), true);
    }

    /** The shipped fragments alone, whatever the deployment has: the discovery's seed. */
    public static JsonModelsBackend shipped() {
        return new JsonModelsBackend(fragments());
    }

    /** The deployment's own file alone, or null when it has none: the discovery's previous result. */
    public static JsonModelsBackend deploymentFile() {
        Layer own = deploymentLayer();
        return own != null ? new JsonModelsBackend(List.of(own)) : null;
    }

    /** The file {@code -Dnucleo.models} names, absolute, or null when the property is not set and the classpath is read. */
    public static Path namedFile() {
        String path = System.getProperty(PROPERTY);
        return path != null && !path.isBlank() ? Path.of(path).toAbsolutePath() : null;
    }

    /** For tests / overlays: exactly this classpath resource as the deployment layer, no fragments; a runtime catalog, so it warns as one. */
    protected JsonModelsBackend(String resource) {
        load(List.of(resource(resource)), ClientProviders.all(), true);
    }

    /** For tests and the discovery's sources: explicit sources, either one deployment file or any number of fragments; a view, so it does not warn. */
    public JsonModelsBackend(List<Layer> layers) {
        load(layers, ClientProviders.all(), false);
    }

    /**
     * Explicit sources linked against explicit providers in place of the classpath's: the
     * discovery validates the catalog it writes by loading it against the providers it wrote it
     * for, so every entry is validated under the provider it names. Spec types are still the
     * classpath's, the only place a spec class can be instantiated from.
     */
    public JsonModelsBackend(List<Layer> layers, Map<String, ClientProvider<?>> providers) {
        load(layers, providers, false);
    }

    /**
     * The catalog discovery: the deployment's file alone when there is one (the property, else
     * the whole classpath nearest first), else every fragment on the classpath.
     */
    static List<Layer> discover() {
        Layer own = deploymentLayer();
        if (own != null) {
            return List.of(own);
        }
        List<Layer> fragments = fragments();
        log.warn("No model catalog of this deployment's own; running on the {} shipped fragment(s) with the providers'"
                + " published entry-tier limits. Put a {} on the application's classpath (src/main/resources), or name"
                + " a file with -D{}, to declare this account's models, limits and pins; the catalog discovery writes one.",
                fragments.size(), CLASSPATH_RESOURCE, PROPERTY);
        return fragments;
    }

    /** The deployment's file: the property, else the classpath; null when neither holds one. */
    private static Layer deploymentLayer() {
        return deploymentLayer(System.getProperty(PROPERTY), catalogClassLoader());
    }

    /**
     * The classloader the catalog and its fragments are enumerated through: the thread-context
     * loader when the running thread carries one, else this class's own. A host that isolates the
     * application's classpath from the framework's (Quarkus in dev mode loads this class in a base
     * loader that cannot see the application's dependencies) sets the context loader to the one that
     * can, so the account's models.json and the providers' fragments are found the way they are
     * everywhere the two loaders are the same.
     */
    private static ClassLoader catalogClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : JsonModelsBackend.class.getClassLoader();
    }

    /**
     * Over explicit inputs, so the order is testable without changing the process's classpath. A
     * file the property names that cannot be read is refused rather than passed over: the
     * deployment named it, so reading the classpath instead would run on a catalog nobody chose.
     */
    static Layer deploymentLayer(String property, ClassLoader loader) {
        if (property != null && !property.isBlank()) {
            try {
                return new Layer(property, Files.readString(Path.of(property), StandardCharsets.UTF_8), false);
            }
            catch (IOException e) {
                throw new UncorrectableRuntimeLLMException("Model catalog named by -D" + PROPERTY + " is not readable: " + property, e);
            }
        }
        return classpathLayer(loader);
    }

    /**
     * The catalog anywhere on the classpath, found the way logging configuration is: every
     * {@code models.json} enumerated, the first taken. Launchers order the application's own
     * classes before its dependencies (and test classes before both), so the nearest file to
     * the class being run wins; the ones it shadows are named in a warning.
     */
    private static Layer classpathLayer(ClassLoader loader) {
        List<URL> found;
        try {
            found = Collections.list(loader.getResources(CLASSPATH_RESOURCE));
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException("Failed to enumerate " + CLASSPATH_RESOURCE + " on the classpath", e);
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            log.warn("{} copies of {} on the classpath; reading the nearest to the application, {}, and ignoring {}",
                    found.size(), CLASSPATH_RESOURCE, found.get(0), found.subList(1, found.size()));
        }
        try (InputStream is = found.get(0).openStream()) {
            return new Layer(found.get(0).toString(), new String(is.readAllBytes(), StandardCharsets.UTF_8), false);
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException("Model catalog on the classpath is not readable: " + found.get(0), e);
        }
    }

    /** Every fragment on the classpath; a classpath without any has no catalog at all. */
    private static List<Layer> fragments() {
        List<Layer> fragments = new ArrayList<>();
        try {
            ClassLoader loader = catalogClassLoader();
            for (URL url : Collections.list(loader.getResources(FRAGMENT_RESOURCE))) {
                try (InputStream is = url.openStream()) {
                    fragments.add(new Layer(url.toString(), new String(is.readAllBytes(), StandardCharsets.UTF_8), true));
                }
            }
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException("Failed to enumerate catalog fragments " + FRAGMENT_RESOURCE, e);
        }
        if (fragments.isEmpty()) {
            throw new UncorrectableRuntimeLLMException("No model catalog found: -D" + PROPERTY + " is not set, there is no "
                    + CLASSPATH_RESOURCE + " anywhere on the classpath, and no " + FRAGMENT_RESOURCE
                    + " either (every provider artifact ships one)");
        }
        return fragments;
    }

    /** A classpath resource as a deployment layer; the fragments come from the enumeration above, never from here. */
    private static Layer resource(String resource) {
        try (InputStream is = JsonModelsBackend.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new UncorrectableRuntimeLLMException("Model catalog resource not found: " + resource);
            }
            return new Layer(resource, new String(is.readAllBytes(), StandardCharsets.UTF_8), false);
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException("Failed to read model catalog resource: " + resource, e);
        }
    }

    /**
     * Each source is self-contained: its own provider defaults apply to its own entries and to
     * nothing else. An entry whose provider is not on this classpath is linked in memory to one
     * of its addressing that is ({@link ProviderLinks}) before its spec is built, so the provider
     * defaults it loads under are its new provider's; the source keeps it as written.
     */
    private void load(List<Layer> layers, Map<String, ClientProvider<?>> loaded, boolean announce) {
        Map<String, String> origin = new HashMap<>();
        Set<String> unserved = new LinkedHashSet<>();
        source = layers.stream().map(Layer::name).collect(Collectors.joining(", "));
        for (Layer layer : layers) {
            JsonNode root;
            try {
                root = NucleoJsonSerializer.readTree(layer.json());
            }
            catch (IOException e) {
                throw new UncorrectableRuntimeLLMException("Model catalog is not valid JSON: " + layer.name(), e);
            }
            JsonNode record = root.get(ProviderLinks.PROVIDERS);
            if (record != null) {
                rawProviders = record.deepCopy();
            }
            List<String> linked = new ArrayList<>();
            List<String> left = new ArrayList<>();
            JsonNode providerDefaults = root.get("provider_defaults");
            if (providerDefaults != null && providerDefaults.isObject()) {
                providerDefaults.properties().forEach(field -> {
                    ObjectNode merged = rawProviderDefaults.has(field.getKey())
                            ? (ObjectNode) rawProviderDefaults.get(field.getKey())
                            : rawProviderDefaults.putObject(field.getKey());
                    merged.setAll((ObjectNode) field.getValue());
                });
            }
            JsonNode models = root.get("models");
            if (models == null || !models.isArray()) {
                throw new UncorrectableRuntimeLLMException("Model catalog has no 'models' array: " + layer.name());
            }
            for (JsonNode modelNode : models) {
                JsonNode linkedNode = link(modelNode, record, providerDefaults, loaded, linked, left);
                if (linkedNode == null) {
                    // nothing here can serve it: this process leaves it out, and the
                    // discovery still carries it through as written
                    String id = modelNode.path("id").asText();
                    claimId(origin, id, layer);
                    unserved.add(id);
                    rawById.put(id, modelNode.deepCopy());
                    continue;
                }
                ModelSpec spec = buildSpec(linkedNode, providerDefaults);
                claimId(origin, spec.getId(), layer);
                index(spec);
                rawById.put(spec.getId(), modelNode.deepCopy());
            }
            if (announce) {
                warnOnMismatch(layer, record, loaded, linked, left);
            }
            JsonNode pinsNode = root.get("pins");
            if (pinsNode != null) {
                if (layer.fragment()) {
                    throw new UncorrectableRuntimeLLMException("Catalog fragment " + layer.name()
                            + " declares 'pins'; pins are the deployment's and live in its own file only");
                }
                pins = readPins(pinsNode, layer.name());
                rawPins = pinsNode.deepCopy();
            }
        }
        pins = withoutUnserved(pins, unserved, announce);
        validatePins(layers);
        log.info("Loaded {} model specs from {}", byId.size(), layers.stream().map(Layer::name).toList());
    }

    private static void claimId(Map<String, String> origin, String id, Layer layer) {
        String previous = origin.put(id, layer.name());
        if (previous != null) {
            throw new UncorrectableRuntimeLLMException("Duplicate model id '" + id + "' in catalog "
                    + previous + (previous.equals(layer.name()) ? "" : " and " + layer.name()));
        }
    }

    /**
     * The entry as this process loads it: itself when its provider is here, or when it states
     * no platform, its catalog records none for its provider, and its spec type is one this
     * classpath can build (it loads as written, no picker lands on it, and a call to it names
     * the missing provider); a copy naming the provider of its addressing that
     * {@link ProviderLinks#resolve} picks, its own {@code spec_type} dropped so the new
     * provider's defaults shape it; null when nothing here can serve it: no provider here of its
     * addressing fits, or, with no platform on record, its spec type is declared by no artifact
     * on this classpath.
     */
    private static JsonNode link(JsonNode entry, JsonNode record, JsonNode providerDefaults, Map<String, ClientProvider<?>> loaded,
                                 List<String> linked, List<String> left) {
        JsonNode keyNode = entry.get("provider_key");
        JsonNode wireNode = entry.get("wire_model_id");
        if (keyNode == null || keyNode.asText().isBlank() || wireNode == null || loaded.containsKey(keyNode.asText())) {
            return entry;
        }
        String key = keyNode.asText();
        String id = entry.path("id").asText();
        String platform = ProviderLinks.recordedPlatform(entry, key, record);
        if (platform == null) {
            String specType = specTypeOf(entry, providerDefaults != null ? providerDefaults.get(key) : null);
            if (STANDARD_SPEC_TYPE.equals(specType) || ClientProviders.specClass(specType) != null) {
                return entry;
            }
            left.add(id + " (" + key + ", spec type " + specType + " declared by no artifact here)");
            return null;
        }
        String addressing = ProviderLinks.recordedAddressing(entry, key, record);
        ClientProvider<?> target = ProviderLinks.resolve(loaded, addressing, ModelKind.ofProviderKey(key), wireNode.asText());
        if (target == null) {
            left.add(id + " (" + key + ", " + addressing + ")");
            return null;
        }
        ObjectNode relinked = ((ObjectNode) entry).deepCopy();
        relinked.put("provider_key", target.key());
        relinked.remove("spec_type");
        linked.add(id + " (" + key + " -> " + target.key() + ")");
        return relinked;
    }

    /**
     * One warning per source whose record of providers differs from this classpath's, or
     * whose entries this process linked or left out: what differs, what was done in memory,
     * and that the file is untouched until the discovery writes it for these providers.
     */
    private static void warnOnMismatch(Layer layer, JsonNode record, Map<String, ClientProvider<?>> loaded,
                                       List<String> linked, List<String> left) {
        Set<String> recorded = ProviderLinks.recordedKeys(record);
        Set<String> present = new TreeSet<>(loaded.keySet());
        boolean differs = recorded != null && !recorded.equals(present);
        if (!differs && linked.isEmpty() && left.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("Model catalog ").append(layer.name());
        if (recorded != null) {
            message.append(" was written for providers ").append(recorded).append("; this classpath carries ").append(present);
        }
        else {
            message.append(" names providers this classpath does not carry (it carries ").append(present).append(')');
        }
        message.append(". In memory only, the file unchanged:");
        message.append(linked.isEmpty() ? " no entry needed a new provider" : " linked " + linked.size() + " entries to a provider here that addresses their models " + linked);
        if (!left.isEmpty()) {
            message.append("; left out ").append(left.size()).append(" entries nothing here can serve, now unavailable ").append(left);
        }
        message.append(". Run the catalog discovery from this classpath to write the catalog for these providers.");
        log.warn(message.toString());
    }

    /**
     * The pins without the ones naming an entry this process cannot serve: a pin to it would
     * resolve a seat to a model no provider here calls, so the seat falls to the picker's
     * choice instead, with a warning naming the pin.
     */
    private static CatalogPins withoutUnserved(CatalogPins pins, Set<String> unserved, boolean announce) {
        if (pins == null || unserved.isEmpty()) {
            return pins;
        }
        List<String> dropped = new ArrayList<>();
        Map<Grade, List<String>> grades = new EnumMap<>(Grade.class);
        pins.grades().forEach((grade, ids) -> {
            List<String> kept = new ArrayList<>();
            for (String id : ids) {
                if (unserved.contains(id)) {
                    dropped.add(grade + " -> " + id);
                }
                else {
                    kept.add(id);
                }
            }
            if (!kept.isEmpty()) {
                grades.put(grade, Collections.unmodifiableList(kept));
            }
        });
        String embeddings = pins.embeddings();
        if (embeddings != null && unserved.contains(embeddings)) {
            dropped.add("embeddings -> " + embeddings);
            embeddings = null;
        }
        String decision = pins.decision();
        if (decision != null && unserved.contains(decision)) {
            dropped.add("decision -> " + decision);
            decision = null;
        }
        if (dropped.isEmpty()) {
            return pins;
        }
        if (announce) {
            log.warn("Dropped the catalog pins naming entries no provider on this classpath serves, in memory only: {};"
                    + " those seats fall to the picker's choice", dropped);
        }
        return new CatalogPins(Collections.unmodifiableMap(grades), embeddings, decision);
    }

    /**
     * The spec an entry would load as under this catalog's provider defaults, without indexing
     * it: for the discovery, which rewrites an entry's wire id to the profile the region lists
     * and must ping what it is about to write.
     */
    public ModelSpec specOf(ObjectNode entry) {
        return buildSpec(entry, rawProviderDefaults);
    }

    /** The spec type an entry names: its own, else its provider defaults', else the standard one. */
    private static String specTypeOf(JsonNode entry, JsonNode defaults) {
        if (entry.has("spec_type")) {
            return entry.get("spec_type").asText();
        }
        return defaults != null && defaults.has("spec_type") ? defaults.get("spec_type").asText() : STANDARD_SPEC_TYPE;
    }

    private ModelSpec buildSpec(JsonNode modelNode, JsonNode providerDefaults) {
        JsonNode providerKeyNode = modelNode.get("provider_key");
        if (providerKeyNode == null || providerKeyNode.asText().isBlank()) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry missing provider_key: " + modelNode);
        }
        String providerKey = providerKeyNode.asText();
        ObjectNode merged = NucleoJsonSerializer.createObjectNode();
        JsonNode defaults = providerDefaults != null ? providerDefaults.get(providerKey) : null;
        if (defaults != null && defaults.isObject()) {
            merged.setAll((ObjectNode) defaults);
        }
        merged.setAll((ObjectNode) modelNode);
        String specType = specTypeOf(modelNode, defaults);
        Class<? extends AbstractModelSpec> specClass = STANDARD_SPEC_TYPE.equals(specType)
                ? StandardModelSpec.class
                : ClientProviders.specClass(specType);
        if (specClass == null) {
            throw new UncorrectableRuntimeLLMException("Unknown spec_type '" + specType + "' for model " + merged.get("id")
                    + ": no provider artifact on the classpath declares it - add the provider that serves this model");
        }
        AbstractModelSpec spec = NucleoJsonSerializer.convert(merged, specClass);
        validate(spec, merged);
        return spec;
    }

    private CatalogPins readPins(JsonNode pinsNode, String origin) {
        if (!pinsNode.isObject()) {
            throw new UncorrectableRuntimeLLMException("Catalog 'pins' must be an object in " + origin + ": " + pinsNode);
        }
        Map<Grade, List<String>> grades = new EnumMap<>(Grade.class);
        String embeddings = null;
        String decision = null;
        for (Map.Entry<String, JsonNode> field : pinsNode.properties()) {
            String key = field.getKey();
            if ("embeddings".equals(key) || "decision".equals(key)) {
                String value = field.getValue().isTextual() ? field.getValue().asText() : null;
                if (value == null || value.isBlank()) {
                    throw new UncorrectableRuntimeLLMException("Catalog pin '" + key + "' must be one catalog id in " + origin + ": " + field.getValue());
                }
                if ("embeddings".equals(key)) {
                    embeddings = value;
                }
                else {
                    decision = value;
                }
                continue;
            }
            Grade grade = gradeOf(key, key, origin);
            if (!grade.isRung()) {
                throw new UncorrectableRuntimeLLMException("Catalog pin names " + grade
                        + ", which is a seat's word for the strongest rung and never a pin, in " + origin);
            }
            // a grade's entries in the deployment's order, the first its default: an array, and an empty one declares none
            if (!field.getValue().isArray()) {
                throw new UncorrectableRuntimeLLMException("Catalog pin '" + key + "' must be an array of catalog ids in the deployment's"
                        + " order, the first the grade's default, in " + origin + ": " + field.getValue());
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode id : field.getValue()) {
                if (!id.isTextual() || id.asText().isBlank()) {
                    throw new UncorrectableRuntimeLLMException("Catalog pin '" + key + "' carries an element that is not a catalog id in " + origin + ": " + id);
                }
                if (ids.contains(id.asText())) {
                    throw new UncorrectableRuntimeLLMException("Catalog pin '" + key + "' names '" + id.asText() + "' twice in " + origin
                            + "; an entry has one place in its grade's order");
                }
                ids.add(id.asText());
            }
            if (!ids.isEmpty()) {
                grades.put(grade, Collections.unmodifiableList(ids));
            }
        }
        return new CatalogPins(Collections.unmodifiableMap(grades), embeddings, decision);
    }

    private static Grade gradeOf(String name, String key, String origin) {
        try {
            return Grade.valueOf(name);
        }
        catch (IllegalArgumentException e) {
            throw new UncorrectableRuntimeLLMException("Catalog pin '" + key + "' names no grade: '" + name
                    + "' (rungs are " + Grade.rungs() + ") in " + origin);
        }
    }

    /** Every pinned id must be an entry of the layered catalog, and of the right kind. */
    private void validatePins(List<Layer> layers) {
        if (pins == null) {
            return;
        }
        for (Map.Entry<Grade, List<String>> pin : pins.grades().entrySet()) {
            for (String id : pin.getValue()) {
                ModelSpec spec = byId.get(id);
                if (spec == null) {
                    throw new UncorrectableRuntimeLLMException("Catalog pin " + pin.getKey() + " names '" + id
                            + "', which is not an entry of the catalog (" + layers.stream().map(Layer::name).toList() + ")");
                }
                if (spec.kind() != ModelKind.LLM || spec.getGrade() == null) {
                    throw new UncorrectableRuntimeLLMException("Catalog pin " + pin.getKey() + " names " + spec.kind().name().toLowerCase(Locale.ROOT)
                            + " entry '" + id + "'; a grade pin names graded LLM entries");
                }
                if (!spec.getGrade().atLeast(pin.getKey())) {
                    throw new UncorrectableRuntimeLLMException("Catalog pin " + pin.getKey() + " names '" + id + "', of grade " + spec.getGrade()
                            + "; a grade is served by entries of its grade or above, never below");
                }
            }
        }
        if (pins.embeddings() != null) {
            ModelSpec spec = byId.get(pins.embeddings());
            if (spec == null) {
                throw new UncorrectableRuntimeLLMException("Catalog pin 'embeddings' names '" + pins.embeddings()
                        + "', which is not an entry of the catalog");
            }
            if (!spec.isEmbeddings()) {
                throw new UncorrectableRuntimeLLMException("Catalog pin 'embeddings' names '" + pins.embeddings()
                        + "', which is not an embeddings entry");
            }
        }
        if (pins.decision() != null) {
            ModelSpec spec = byId.get(pins.decision());
            if (spec == null) {
                throw new UncorrectableRuntimeLLMException("Catalog pin 'decision' names '" + pins.decision()
                        + "', which is not an entry of the catalog");
            }
            if (!spec.isDecision()) {
                throw new UncorrectableRuntimeLLMException("Catalog pin 'decision' names '" + pins.decision()
                        + "', which is not a decision entry");
            }
        }
    }

    /**
     * Whether an entry states modalities that fit neither an LLM seat (text in, text out) nor
     * an embeddings one: the discovery's seatless entry for an image, speech or video model. An
     * entry that states no modalities is an LLM or embeddings entry, as every hand-written one is.
     */
    static boolean otherModality(ModelSpec spec) {
        List<String> outputs = spec.getOutputModalities();
        if (outputs == null || outputs.isEmpty() || spec.isEmbeddings() || outputs.contains("EMBEDDING")) {
            return false;
        }
        List<String> inputs = spec.getInputModalities();
        return !(outputs.contains("TEXT") && (inputs == null || inputs.contains("TEXT")));
    }

    private void validate(AbstractModelSpec spec, JsonNode source) {
        requireText(spec.getId(), "id", source);
        requireText(spec.getIdentity(), "identity", source);
        requireText(spec.getProviderKey(), "provider_key", source);
        requireText(spec.getWireModelId(), "wire_model_id", source);
        if (otherModality(spec)) {
            // A model of another modality states what the account offers and the runtime has no
            // client for; nothing in it is ever sent on the wire, so the limits and ceilings a
            // call would need are not required, and a grade would put it on a rung it cannot serve.
            if (spec.getGrade() != null) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry produces no text (" + spec.getOutputModalities()
                        + ") yet carries a grade; a rung is a seat for text work, which this model cannot do: " + source);
            }
        }
        else if (spec.isDecision()) {
            // A decision entry generates nothing, so it has no output ceiling; its context
            // ceiling is the state it reads. Its bound is a quota window or a concurrency,
            // never both: the two are different accounts in admission.
            requirePositive(spec.getMaxContextTokens(), "max_context_tokens", source);
            if (spec.getGrade() != null) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry is a decision model yet carries a grade;"
                        + " decision models are not on the capability ladder: " + source);
            }
            boolean windowed = spec.getTpm() > 0;
            boolean concurrent = spec.getMaxConcurrent() != null;
            if (windowed == concurrent) {
                throw new UncorrectableRuntimeLLMException("Model catalog decision entry declares "
                        + (windowed ? "both 'tpm' and 'max_concurrent'" : "neither 'tpm' nor 'max_concurrent'")
                        + "; a hosted endpoint is bounded by its quota window (tpm), a server the deployment runs by"
                        + " how many requests it takes at once (max_concurrent), and an entry states exactly one: " + source);
            }
        }
        else {
            requirePositive(spec.getTpm(), "tpm", source);
            requirePositive(spec.getMaxContextTokens(), "max_context_tokens", source);
            requirePositive(spec.getMaxOutputTokens(), "max_output_tokens", source);
        }
        if (spec.getMaxConcurrent() != null) {
            if (spec.getMaxConcurrent() <= 0) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry has non-positive 'max_concurrent': " + source);
            }
            if (!spec.isDecision()) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry declares 'max_concurrent', which only a"
                        + " decision entry served from the deployment's own machine declares; an LLM or embeddings entry"
                        + " is bounded by its quota window (tpm, rpm): " + source);
            }
        }
        Integer embeddingDimensions = spec.getEmbeddingDimensions();
        if (embeddingDimensions != null && embeddingDimensions <= 0) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry has non-positive 'embedding_dimensions': " + source);
        }
        if (spec.getGrade() != null && !spec.getGrade().isRung()) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry declares grade CEILING, which is a seat's word for"
                    + " the deployment's strongest rung, never a rung an entry can carry: " + source);
        }
        validatePrices(spec, source);
        validateTranslations(spec, source);
    }

    /**
     * A price is a number in a currency: an entry that carries any price carries a currency
     * (its own or its provider defaults'), and a currency is an ISO 4217 code. A number with no
     * currency would be summed with numbers in another and read as one figure.
     */
    private void validatePrices(AbstractModelSpec spec, JsonNode source) {
        boolean priced = spec.getInputPricePerMillion() != null || spec.getOutputPricePerMillion() != null
                || spec.getCacheReadPricePerMillion() != null || spec.getCacheWritePricePerMillion() != null;
        String currency = spec.getCurrency();
        if (priced && currency == null) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry carries a price and no 'currency' (its own or its"
                    + " provider_defaults'); a price is a number in a currency: " + source);
        }
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry 'currency' must be an ISO 4217 code such as USD, got '"
                    + currency + "': " + source);
        }
    }

    /**
     * The optional per-entry translations are all-or-none: a partial table would silently mix
     * the entry's numbers with the framework's, and nobody would see which rung came from where.
     * The depths a thinking table names follow the mode: the Anthropic modes attach nothing at
     * IMMEDIATE, so the table names the other four; a reasoning-effort model reasons at every
     * depth, so its table names all five.
     */
    private void validateTranslations(AbstractModelSpec spec, JsonNode source) {
        Map<Depth, Integer> thinking = spec.getThinkingBudgets();
        if (thinking != null) {
            if (spec.getThinkingMode() == ThinkingMode.NONE) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry declares 'thinking_budgets' with thinking_mode NONE: " + source);
            }
            Set<Depth> required = spec.getThinkingMode() == ThinkingMode.REASONING_EFFORT
                    ? EnumSet.allOf(Depth.class)
                    : EnumSet.of(Depth.QUICK, Depth.STANDARD, Depth.THOROUGH, Depth.ULTRA_THOROUGH);
            if (!thinking.keySet().equals(required)) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry 'thinking_budgets' must name exactly " + required + ": " + source);
            }
            for (Map.Entry<Depth, Integer> entry : thinking.entrySet()) {
                requireWithin(entry.getValue(), AbstractModelSpec.MIN_THINKING_BUDGET, spec.getMaxOutputTokens(), "thinking_budgets." + entry.getKey(), source);
            }
        }
        Map<OutputSize, Integer> output = spec.getOutputBudgets();
        if (output != null) {
            Set<OutputSize> required = EnumSet.of(OutputSize.VERDICT, OutputSize.COMPACT, OutputSize.STANDARD, OutputSize.EXTENDED);
            if (!output.keySet().equals(required)) {
                throw new UncorrectableRuntimeLLMException("Model catalog entry 'output_budgets' must name exactly " + required + " (MAX is the ceiling): " + source);
            }
            for (Map.Entry<OutputSize, Integer> entry : output.entrySet()) {
                requireWithin(entry.getValue(), 1, spec.getMaxOutputTokens(), "output_budgets." + entry.getKey(), source);
            }
        }
        Integer comfort = spec.getComfortContextTokens();
        if (comfort != null) {
            requireWithin(comfort, 1, spec.getMaxContextTokens(), "comfort_context_tokens", source);
        }
    }

    private void requireWithin(Integer value, int min, int max, String field, JsonNode source) {
        if (value == null || value < min || value > max) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry '" + field + "' must be within [" + min + ", " + max + "], got " + value + ": " + source);
        }
    }

    private void requireText(String value, String field, JsonNode source) {
        if (value == null || value.isBlank()) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry missing '" + field + "': " + source);
        }
    }

    private void requirePositive(int value, String field, JsonNode source) {
        if (value <= 0) {
            throw new UncorrectableRuntimeLLMException("Model catalog entry has non-positive '" + field + "': " + source);
        }
    }

    private void index(ModelSpec spec) {
        byId.put(spec.getId(), spec);
        byWireId.put(spec.getWireModelId(), spec);
    }

    @Override
    public ModelSpec spec(String id) {
        ModelSpec s = byId.get(id);
        return s != null ? s : byWireId.get(id);
    }

    @Override
    public Collection<ModelSpec> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    @Override
    public CatalogPins pins() {
        return pins;
    }

    /** Each entry as written in its source, by id, in catalog order. Copies: the caller edits freely. The discovery's raw view. */
    public Map<String, ObjectNode> rawEntries() {
        Map<String, ObjectNode> copies = new LinkedHashMap<>();
        rawById.forEach((id, node) -> copies.put(id, node.deepCopy()));
        return copies;
    }

    /** The merged provider defaults as written. A copy. */
    public ObjectNode rawProviderDefaults() {
        return rawProviderDefaults.deepCopy();
    }

    /** The pins object as written, or null. A copy. */
    public ObjectNode rawPins() {
        return rawPins != null ? rawPins.deepCopy() : null;
    }

    /** The record of the providers the catalog was written for ({@link ProviderLinks#PROVIDERS}) as written, or null. A copy. */
    public JsonNode rawProviders() {
        return rawProviders != null ? rawProviders.deepCopy() : null;
    }
}
