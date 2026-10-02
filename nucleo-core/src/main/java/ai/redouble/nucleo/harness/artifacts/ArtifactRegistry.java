/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/**
 * Registry for storing and managing artifacts within a conversation context.
 *
 * <p>Artifacts are domain objects (citations, code, people, etc.) that need to be
 * preserved across agent boundaries without corruption. The registry stores the
 * full objects and provides access via unique references.
 *
 * <p>Reference format: «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»)
 *
 * <p>The registry is conversation-wide and survives message compaction, ensuring
 * artifacts remain available throughout the entire conversation.
 *
 * <p>The registry holds two tiers. Top-level artifacts ({@link #getAllArtifacts()})
 * are what renderers and persistence iterate. Reachable artifacts are everything
 * in custody through a top-level owner - iterands of a registered
 * {@link ListArtifact}, artifacts nested in another artifact's fields, artifacts
 * conveyed from a worker's output. Registering an artifact walks its object graph
 * and indexes every reachable artifact with its own ref, so each is resolvable via
 * {@link #get} and addressable by tools, while rendering cost stays bounded by the
 * top-level owner's prompt-form (e.g. a 200-iterand list contributes one
 * digest-form entry, never 200).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public class ArtifactRegistry {
    /**
     * The serialized name of an artifact's ref under the framework mapper's SNAKE_CASE
     * naming strategy. Every reader that rebuilds an artifact from its own payload keys on
     * this, so it lives once here rather than as a literal in each of them.
     */
    public static final String REF_FIELD = "artifact_ref";
    /** The wire shape of an artifact mention inside prose: {@code «artifact:...»} up to the closing guillemet. */
    private static final Pattern MENTION = Pattern.compile("«artifact:([^»]+)»");
    private final Map<String, Artifact> artifacts = new HashMap<>();
    private final Map<String, Artifact> reachableArtifacts = new HashMap<>();
    private final Random random = new Random();

    /**
     * Registers an artifact as a top-level entry and indexes every artifact
     * reachable from it. If the artifact doesn't have a reference, one is generated.
     *
     * @param artifact the artifact to register
     * @return the artifact's reference ID
     */
    public String register(Artifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("Cannot register null artifact");
        }
        String ref = mintReferenceIfMissing(artifact);
        String key = normalizeToKey(ref);
        // Artifacts are immutable once registered, so re-registering the same
        // instance (ensureReference fires on every serialization pass) skips the
        // reachable walk instead of re-traversing the object graph each time.
        if (artifacts.get(key) == artifact) {
            return ref;
        }
        artifacts.put(key, artifact);
        walkReachable(artifact, Collections.newSetFromMap(new IdentityHashMap<>()));
        return ref;
    }

    /**
     * Indexes an artifact into the reachable tier: in custody and resolvable via
     * {@link #get}, but not a top-level entry, so it renders and persists only
     * through whatever top-level owner reaches it. Used by conveyance (e.g. a
     * fan-out doer registering artifacts a worker passed on) and by the reachable
     * walk of {@link #register}.
     */
    public void indexReachable(Artifact artifact) {
        if (artifact == null) {
            return;
        }
        reachableArtifacts.put(normalizeToKey(mintReferenceIfMissing(artifact)), artifact);
        walkReachable(artifact, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Mints a reference on every artifact reachable from an arbitrary object, indexing
     * each into the reachable tier. Unlike {@link #register}, the root need not itself be
     * an artifact - a plain result POJO carrying artifacts in its fields is the usual case.
     *
     * <p>Exists for the graph that must carry artifact identity without being rendered for
     * a model: a result crossing a process boundary, where the ref is what lets the
     * receiving side rebuild typed artifacts. A ref is minted only at registration, so an
     * artifact that never passed through a registry would otherwise serialize with a null
     * ref and arrive as anonymous data.
     */
    public void indexReachableFrom(Object root) {
        if (root == null) {
            return;
        }
        if (root instanceof Artifact artifact) {
            indexReachable(artifact);
            return;
        }
        walkReachable(root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Walks the object graph of a root artifact and indexes every {@link Artifact}
     * found below it into the reachable tier. Recursion descends through composite
     * POJOs, collections, maps and arrays; leaf JDK types are skipped via
     * {@link NucleoJsonSerializer#isComposite}. An identity-visited set guards
     * against cycles.
     */
    private void walkReachable(Object node, Set<Object> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node instanceof Artifact || NucleoJsonSerializer.isComposite(node.getClass())) {
            walkFields(node, visited);
            return;
        }
        if (node instanceof Collection<?> collection) {
            for (Object element : collection) {
                indexIfArtifact(element, visited);
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                indexIfArtifact(value, visited);
            }
            return;
        }
        if (node.getClass().isArray() && !node.getClass().getComponentType().isPrimitive()) {
            for (Object element : (Object[]) node) {
                indexIfArtifact(element, visited);
            }
        }
    }

    private void walkFields(Object node, Set<Object> visited) {
        Class<?> clazz = node.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type.isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(node);
                }
                catch (IllegalAccessException e) {
                    throw new ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException(
                            "Custody walk cannot read field " + field.getName() + " on " + clazz.getName()
                                    + " - reachable artifacts below it would escape custody", e);
                }
                indexIfArtifact(value, visited);
            }
            clazz = clazz.getSuperclass();
        }
    }

    private void indexIfArtifact(Object value, Set<Object> visited) {
        if (value == null) {
            return;
        }
        if (value instanceof Artifact artifact) {
            if (!visited.add(artifact)) {
                return;
            }
            reachableArtifacts.put(normalizeToKey(mintReferenceIfMissing(artifact)), artifact);
            walkFields(artifact, visited);
            return;
        }
        walkReachable(value, visited);
    }

    /**
     * Ensures an artifact has a reference and is registered.
     * If the artifact doesn't have a reference, one is generated.
     * This is the preferred method for registering artifacts during discovery.
     *
     * @param artifact the artifact to ensure is registered
     */
    public void ensureReference(Artifact artifact) {
        if (artifact == null) {
            return;
        }
        register(artifact);
    }

    /**
     * Generates and assigns a reference if the artifact has none.
     *
     * @param artifact the artifact to check
     * @return the artifact's reference (existing or freshly minted)
     */
    private String mintReferenceIfMissing(Artifact artifact) {
        String ref = artifact.getArtifactRef();
        if (ref == null || ref.trim().isEmpty()) {
            ref = generateReference(artifact);
            artifact.setArtifactRef(ref);
        }
        return ref;
    }

    /**
     * Retrieves an artifact by its reference.
     * Accepts any format: «artifact:type~uuid», artifact:type~uuid, or bare type~uuid.
     *
     * @param ref the artifact reference in any format
     * @return the artifact, or null if not found
     */
    public Artifact get(String ref) {
        if (ref == null) {
            return null;
        }
        String key = normalizeToKey(ref);
        Artifact artifact = artifacts.get(key);
        if (artifact != null) {
            return artifact;
        }
        return reachableArtifacts.get(key);
    }

    /**
     * Checks if an artifact exists in the registry.
     * Accepts any format: «artifact:type~uuid», artifact:type~uuid, or bare type~uuid.
     *
     * @param ref the artifact reference in any format
     * @return true if the artifact exists
     */
    public boolean contains(String ref) {
        if (ref == null) {
            return false;
        }
        String key = normalizeToKey(ref);
        return artifacts.containsKey(key) || reachableArtifacts.containsKey(key);
    }

    /**
     * Gets all top-level artifacts in the registry. Reachable artifacts (list
     * iterands, nested artifacts, conveyed worker artifacts) are NOT included -
     * they resolve individually via {@link #get} but render and persist only
     * through their top-level owner.
     *
     * @return unmodifiable map of all top-level artifacts
     */
    public Map<String, Artifact> getAllArtifacts() {
        return Collections.unmodifiableMap(artifacts);
    }

    /**
     * Gets all artifacts including the reachable tier, keyed by normalized ref.
     * Use for exhaustive content operations (e.g. text search across every
     * artifact). Rendering and persistence use {@link #getAllArtifacts()}
     * instead, so reachable artifacts stay behind their owner's bounded
     * prompt-form.
     *
     * @return unmodifiable map of top-level and reachable artifacts
     */
    public Map<String, Artifact> getAllArtifactsIncludingReachable() {
        Map<String, Artifact> combined = new LinkedHashMap<>(artifacts);
        reachableArtifacts.forEach(combined::putIfAbsent);
        return Collections.unmodifiableMap(combined);
    }

    /**
     * Gets the number of top-level artifacts in the registry. The reachable tier is
     * not counted, matching what {@link #getAllArtifacts()} iterates.
     *
     * @return the top-level artifact count
     */
    public int size() {
        return artifacts.size();
    }

    /**
     * Clears all artifacts from the registry, including the reachable tier.
     */
    public void clear() {
        artifacts.clear();
        reachableArtifacts.clear();
    }

    /**
     * Generates a unique reference for an artifact.
     * Format: «artifact:type~uuid»
     *
     * @param artifact the artifact to generate a reference for
     * @return the generated reference
     */
    private String generateReference(Artifact artifact) {
        String type = extractType(artifact.getClass());
        String uuid = generateShortUuid();
        return String.format("«artifact:%s~%s»", type, uuid);
    }

    /**
     * Extracts the type alias from an artifact class via {@link TypeAliasRegistry}.
     *
     * @param clazz the artifact class
     * @return the type alias
     * @throws MissingTypeAliasException if the class has no {@code @TypeAlias} annotation
     */
    private String extractType(Class<?> clazz) {
        String alias = TypeAliasRegistry.getAlias(clazz);
        if (alias != null) {
            return alias;
        }
        throw new MissingTypeAliasException(Set.of(clazz));
    }

    /**
     * Extracts the alias portion from an artifact ref string.
     * Strips guillemets, the {@code artifact:} prefix, and the trailing {@code ~uuid}.
     *
     * <p>Example: {@code "«artifact:link:cite:pubmed~x1y2z3»"} returns {@code "link:cite:pubmed"}.
     *
     * @param ref the full artifact reference
     * @return the alias portion, or null if the ref is malformed
     */
    public static String extractAliasFromRef(String ref) {
        if (ref == null) {
            return null;
        }
        String s = ref.trim();
        // Strip guillemets
        if (s.startsWith("\u00AB")) s = s.substring(1);
        if (s.endsWith("\u00BB")) s = s.substring(0, s.length() - 1);
        // Strip artifact: prefix
        if (s.startsWith("artifact:")) s = s.substring("artifact:".length());
        // Split on ~: left = alias, right = uuid
        int hash = s.indexOf('~');
        if (hash < 0) {
            return null;
        }
        return s.substring(0, hash);
    }

    /**
     * Generates a short unique identifier.
     *
     * @return a 6-character alphanumeric string
     */
    private String generateShortUuid() {
        StringBuilder sb = new StringBuilder(6);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Normalizes any artifact reference format to the canonical key format: «artifact:type~uuid».
     * This is the SINGLE normalization point for all artifact reference handling.
     *
     * <p>Handles all variants:
     * <ul>
     *   <li>{@code «artifact:type~uuid»} - already canonical, returned as-is</li>
     *   <li>{@code artifact:type~uuid} - missing guillemets</li>
     *   <li>{@code type~uuid} - bare ID</li>
     *   <li>{@code «type~uuid»} - guillemets without prefix</li>
     * </ul>
     *
     * @param ref the artifact reference in any format
     * @return the canonical reference with guillemets, or null if input is null
     */
    public static String normalizeToKey(String ref) {
        if (ref == null) return null;
        ref = ref.trim();
        // Strip guillemets if present (we re-add them at the end)
        if (ref.startsWith("\u00AB")) ref = ref.substring(1);
        if (ref.endsWith("\u00BB")) ref = ref.substring(0, ref.length() - 1);
        // Ensure artifact: prefix
        if (!ref.startsWith("artifact:")) ref = "artifact:" + ref;
        // Canonical format includes guillemets
        return "\u00AB" + ref + "\u00BB";
    }

    /**
     * Every artifact reference mentioned in a text, normalized to the canonical key,
     * deduplicated, in first-mention order. The single authority for recognizing a ref in
     * prose: the response gate ({@code ArtifactResponse.canonicalizeArtifactRefs}) and the
     * thinker's resolution both read mentions through here, so the wire shape lives once.
     * A text with no mentions yields an empty list - that is an answer, not an error.
     */
    public static List<String> refsMentionedIn(String text) {
        List<String> refs = new ArrayList<>();
        if (text == null) {
            return refs;
        }
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find()) {
            String normalized = normalizeToKey(matcher.group(1));
            if (!refs.contains(normalized)) {
                refs.add(normalized);
            }
        }
        return refs;
    }

    /**
     * GHSA-m8pq-5898-5q8j. Makes an object a model wrote carry this registry's artifacts and no others. A model
     * never authors an artifact: wherever its answer or a tool call's input holds one, in a
     * field, a list, a map or as the object itself, what the model wrote is a choice of
     * reference, and the artifact put in its place is the one held here under that
     * reference. Whatever content the model wrote beside the reference is dropped with the
     * object that carried it.
     *
     * <p>A written artifact that carries no reference, names one this registry does not
     * hold, or names an artifact of another type than the place declares is a violation:
     * it is described in {@code violations}, addressed to the model that can correct it,
     * and left where it was. A caller that finds violations does not use the object.
     *
     * @param written    the object as parsed from the model's reply, or null
     * @param violations receives one line per artifact that could not be taken from here
     * @return {@code written} with its artifacts replaced, or the held artifact itself when
     *         {@code written} is one
     */
    @SuppressWarnings("unchecked")
    public <T> T held(T written, List<String> violations) {
        return (T) heldValue(written, Artifact.class, "", violations, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private Object heldValue(Object value, Type declared, String path, List<String> violations, Set<Object> visited) {
        if (value == null) {
            return null;
        }
        if (value instanceof Artifact written) {
            return heldFor(written, declared, path, violations);
        }
        replaceWithin(value, declared, path, violations, visited);
        return value;
    }

    private Artifact heldFor(Artifact written, Type declared, String path, List<String> violations) {
        String where = path.isEmpty() ? "The reply" : "'" + path + "'";
        String ref = written.getArtifactRef();
        if (ref == null || ref.isBlank()) {
            violations.add(where + " must refer to an artifact the registry lists, by its " + REF_FIELD + ": an artifact is referred to, never written");
            return written;
        }
        Artifact held = get(ref);
        if (held == null) {
            violations.add(where + " carries an " + REF_FIELD + " that names no artifact the registry lists");
            return written;
        }
        Class<?> expected = rawClass(declared);
        if (expected != null && Artifact.class.isAssignableFrom(expected) && !expected.isInstance(held)) {
            violations.add(where + " refers to an artifact of type " + held.getClass().getSimpleName()
                    + ", where " + expected.getSimpleName() + " is expected");
            return written;
        }
        return held;
    }

    private static String child(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /**
     * Replaces the artifacts below a node that is not itself one. Descends through composite
     * POJOs, lists, other collections, maps and arrays, as {@link #walkReachable} does, and
     * never into an artifact: a held one is taken whole.
     */
    private void replaceWithin(Object node, Type declared, String path, List<String> violations, Set<Object> visited) {
        // a raw JSON tree is input no provider parsed into objects, so it holds no artifact
        if (node instanceof com.fasterxml.jackson.databind.JsonNode || !visited.add(node)) {
            return;
        }
        if (node instanceof List<?>) {
            @SuppressWarnings("unchecked")
            ListIterator<Object> elements = ((List<Object>) node).listIterator();
            Type elementType = typeArgument(declared, 0);
            for (int i = 0; elements.hasNext(); i++) {
                Object element = elements.next();
                Object held = heldValue(element, elementType, path + "[" + i + "]", violations, visited);
                if (held != element) {
                    elements.set(held);
                }
            }
            return;
        }
        if (node instanceof Collection<?>) {
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) node;
            Type elementType = typeArgument(declared, 0);
            List<Object> replaced = new ArrayList<>(collection.size());
            boolean changed = false;
            for (Object element : collection) {
                Object held = heldValue(element, elementType, path + "[]", violations, visited);
                changed |= held != element;
                replaced.add(held);
            }
            if (changed) {
                collection.clear();
                collection.addAll(replaced);
            }
            return;
        }
        if (node instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) node;
            Type valueType = typeArgument(declared, 1);
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object held = heldValue(entry.getValue(), valueType, child(path, String.valueOf(entry.getKey())), violations, visited);
                if (held != entry.getValue()) {
                    entry.setValue(held);
                }
            }
            return;
        }
        if (node.getClass().isArray()) {
            if (node.getClass().getComponentType().isPrimitive()) {
                return;
            }
            Object[] array = (Object[]) node;
            for (int i = 0; i < array.length; i++) {
                array[i] = heldValue(array[i], node.getClass().getComponentType(), path + "[" + i + "]", violations, visited);
            }
            return;
        }
        if (!NucleoJsonSerializer.isComposite(node.getClass())) {
            return;
        }
        // up the hierarchy as far as it is ours: a JDK superclass holds no artifact and its
        // fields are not open to reflection
        for (Class<?> clazz = node.getClass(); NucleoJsonSerializer.isComposite(clazz); clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(node);
                    Object held = heldValue(value, field.getGenericType(), child(path, wireName(field.getName())), violations, visited);
                    if (held != value) {
                        field.set(node, held);
                    }
                }
                catch (IllegalAccessException e) {
                    throw new ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException(
                            "Cannot replace a model-written artifact in field " + field.getName() + " of " + clazz.getName()
                                    + " - what the model wrote there would reach the caller", e);
                }
            }
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    private static Type typeArgument(Type declared, int index) {
        if (declared instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length > index) {
            return parameterized.getActualTypeArguments()[index];
        }
        return Artifact.class;
    }

    /** A Java field name as the framework mapper's SNAKE_CASE strategy spells it on the wire. */
    private static String wireName(String fieldName) {
        return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /**
     * Filters the registry to only include specified artifacts.
     * Used for propagation to parent contexts.
     *
     * <p>A ref that resolves to a list iterand promotes that iterand to a top-level
     * entry in the filtered registry: filtering is explicit curation, so the caller
     * deliberately chose to propagate that iterand on its own. If the owning list
     * is also filtered, the destination registry ends up holding the iterand both
     * as a top-level entry and inside the list - {@link #get} resolves top-level
     * first, so lookups stay deterministic.
     *
     * @param refs list of artifact references to include
     * @return a new registry containing only the specified artifacts
     */
    public ArtifactRegistry filter(List<String> refs) {
        ArtifactRegistry filtered = new ArtifactRegistry();
        if (refs != null) {
            for (String ref : refs) {
                Artifact artifact = get(ref);
                if (artifact != null) {
                    filtered.register(artifact);
                }
            }
        }
        return filtered;
    }
}