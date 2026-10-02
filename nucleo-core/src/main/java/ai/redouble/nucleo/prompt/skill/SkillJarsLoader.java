/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/**
 * Discovers skill bundles packaged inside classpath JARs ("skilljars") and registers them
 * in {@link SkillRegistry}.
 *
 * <h2>Bundle layout</h2>
 *
 * Matches the <a href="https://www.skillsjars.com/">SkillsJars</a> and Anthropic Agent
 * Skills conventions: bundles live under {@code META-INF/skills/} at arbitrary depth,
 * each with a {@code SKILL.md} entry file and sibling resources.
 *
 * <pre>
 * META-INF/skills/
 *   claim-triage/                              &lt;- flat layout
 *     SKILL.md
 *     templates/worked-example.md
 *   anthropics/skills/mcp-builder/             &lt;- org/repo/skill nesting (SkillsJars-style)
 *     SKILL.md
 *     reference/tool-design.md
 * </pre>
 *
 * <h2>SKILL.md format</h2>
 *
 * YAML frontmatter delimited by {@code ---} lines, then a markdown body. Fields follow the
 * Agent Skills specification:
 *
 * <pre>
 * ---
 * name: claim-triage                           &lt;- required
 * description: Triages hospital-indemnity...   &lt;- required, &le; 1024 chars
 * license: Apache-2.0                          &lt;- optional, top-level
 * allowed-tools:                               &lt;- optional, list of tool names
 *   - get_claim_fax_data
 *   - search_supporting_documents
 * metadata:                                    &lt;- optional, nested author extensions
 *   author: Redouble AI
 *   origin: skillsjars
 *   bundle_id: ai.redouble.skills.claim-triage
 *   trigger_keyword: triage
 * compatibility: ">=1.0.0"                     &lt;- optional, currently parsed-and-ignored
 * ---
 * You are a claim triage specialist...
 * </pre>
 *
 * {@code description} is required (at most 1024 characters); a bundle without one - which
 * includes any SKILL.md with no frontmatter block at all - fails fast with
 * {@link SkillLoadException}. {@code name} defaults to the last path segment of the
 * bundle directory when the frontmatter does not declare it.
 *
 * <h2>Scanning</h2>
 *
 * {@link #scan()} is idempotent and fires lazily on the first {@link SkillRegistry}
 * access. Explicit bootstrap calls are cheap.
 *
 * <h2>Provenance</h2>
 *
 * Skills registered by this loader get {@code origin = "skillsjars"} on their
 * {@link SkillMetadata} unless the bundle specifies its own origin inside
 * {@code metadata.origin}. Because the bundle sets that field, origin is a label rather than
 * a finding, and nothing in the framework reads it.
 *
 * <p>Everything this loader registers is build-time content: it shipped inside an artifact and
 * was therefore fixed when the deployment was assembled, which is the standing of a prompt
 * written as a string literal. Which jar carried the file - the framework's own
 * {@code nucleo-skills} or any other dependency - does not change that. So the loader
 * logs one line per bundle at INFO, naming the skill and the resource that carried it, as an
 * inventory of what is live rather than as an alarm.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-22)
 */
public final class SkillJarsLoader {
    private static final Logger log = LoggerFactory.getLogger(SkillJarsLoader.class);
    private static final String RESOURCE_ROOT = "META-INF/skills/";
    private static final String ALLOWED_TOOLS = "allowed-tools";
    /** The Agent Skills specification's ceiling for a skill description. */
    static final int MAX_DESCRIPTION_CHARS = 1024;
    private static final Pattern SKILL_MD_DIR = Pattern.compile(
        Pattern.quote(RESOURCE_ROOT) + "(.+)/SKILL\\.md");
    private static final Pattern SKILL_MD_NAME = Pattern.compile("SKILL\\.md");
    private static final Pattern ANY_NAME = Pattern.compile(".+");
    private static final Pattern FRONTMATTER = Pattern.compile(
        "(?s)\\A---\\s*\\R(.*?)\\R---\\s*\\R?(.*)");

    private static final Object SCAN_LOCK = new Object();
    private static volatile boolean scanned = false;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private SkillJarsLoader() {
    }

    /**
     * Walk the classpath for {@code META-INF/skills/**\/SKILL.md} bundles and register each
     * into {@link SkillRegistry}. Idempotent and blocking: the first caller runs the scan
     * under {@link #SCAN_LOCK} while every concurrent caller waits, so a lookup that races
     * the very first scan still sees a fully populated registry rather than an empty one. A
     * plain compare-and-set would let the losing threads fall through to an empty registry
     * while the winner is mid-scan - the bug that left declared skills "not registered".
     *
     * @throws SkillLoadException if any bundle fails to parse or register
     */
    public static void scan() {
        if (scanned) {
            return;
        }
        synchronized (SCAN_LOCK) {
            if (scanned) {
                return;
            }
            doScan();
            scanned = true;
        }
    }

    /**
     * A build may emit a manifest of the skill resource paths so discovery needs no classpath
     * scan: {@code META-INF/nucleo/skills.idx}, one resource path per line, covering both the
     * {@code SKILL.md} files and their companion resources. A GraalVM native image has no
     * classpath to scan, so the manifest is how skills are found there; the Quarkus extension
     * writes it from the Jandex-indexed archives. When no manifest is on the classpath the
     * loader falls back to the Reflections scan, so the JVM without a build step is unchanged.
     */
    static final String MANIFEST = "META-INF/nucleo/skills.idx";

    private static void doScan() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = SkillJarsLoader.class.getClassLoader();
        }
        Set<String> allPaths = manifestPaths(loader);
        if (allPaths == null) {
            // URL sources, combined:
            //   - forClassLoader: URLClassLoader URLs when available (mostly Java 8 / shaded launchers)
            //   - forJavaClassPath: java.class.path system property (Maven surefire, app launchers)
            //   - forResource: classpath entries that actually contain the META-INF/skills/ tree
            // The union covers modern Java runtimes where forClassLoader returns nothing.
            ConfigurationBuilder config = new ConfigurationBuilder()
                .addClassLoaders(loader)
                .addUrls(ClasspathHelper.forClassLoader(loader))
                .addUrls(ClasspathHelper.forJavaClassPath())
                .addUrls(ClasspathHelper.forResource(RESOURCE_ROOT, loader))
                .setScanners(Scanners.Resources);
            Reflections reflections = new Reflections(config);
            // Reflections' getResources(Pattern) matches against file basenames, not full paths.
            allPaths = reflections.getResources(ANY_NAME);
        }
        Set<String> skillMdPaths = new HashSet<>();
        for (String path : allPaths) {
            if (SKILL_MD_NAME.matcher(path.substring(path.lastIndexOf('/') + 1)).matches()) {
                skillMdPaths.add(path);
            }
        }
        int loaded = 0;
        for (String skillMdPath : skillMdPaths) {
            Matcher m = SKILL_MD_DIR.matcher(skillMdPath);
            if (!m.matches()) {
                continue;
            }
            String dirPath = m.group(1);
            Skill skill = loadSkill(loader, dirPath, skillMdPath, allPaths);
            SkillRegistry.register(skill);
            // An inventory line, at INFO: which skill is live and which classpath resource carried
            // it, for the day a prompt in production is not the one anyone expected. Not a warning
            // - everything here shipped inside an artifact, so it was fixed and reviewed at the
            // same moment as a prompt written as a string literal, and content arriving exactly
            // when the deployment was assembled is not an event to raise an alarm about.
            log.info("SkillJarsLoader: skill '{}' from {} (declared origin: {})", skill.name(), skillMdPath, skill.metadata().getOrigin());
            loaded++;
        }
        log.info("SkillJarsLoader: registered {} skill(s) from {}", loaded, RESOURCE_ROOT);
    }

    /**
     * The skill resource paths listed by every {@link #MANIFEST} on the classpath, unioned, or
     * null when no manifest is present (the JVM without a build step, which then scans). A build
     * that emits the manifest makes discovery a resource read, which is all a native image can do.
     */
    private static Set<String> manifestPaths(ClassLoader loader) {
        Set<String> paths = new HashSet<>();
        try {
            Enumeration<URL> manifests = loader.getResources(MANIFEST);
            if (!manifests.hasMoreElements()) {
                return null;
            }
            while (manifests.hasMoreElements()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(manifests.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        String path = line.strip();
                        if (!path.isEmpty()) {
                            paths.add(path);
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            throw new SkillLoadException("Could not read the skill manifest " + MANIFEST, e);
        }
        return paths;
    }

    /** Reset the scanned flag. Test-only. */
    static void resetScanned() {
        synchronized (SCAN_LOCK) {
            scanned = false;
        }
    }

    /** One bundle from its SKILL.md; package-private so tests can pin the parsing contract on fixture files. */
    static Skill loadSkill(ClassLoader loader, String dirPath, String skillMdPath, Set<String> allPaths) {
        String raw = readResource(loader, skillMdPath);
        Matcher fm = FRONTMATTER.matcher(raw);
        Map<String, Object> frontmatter;
        String body;
        if (fm.matches()) {
            frontmatter = parseFrontmatter(fm.group(1), skillMdPath);
            body = fm.group(2);
        }
        else {
            frontmatter = Map.of();
            body = raw;
        }

        String leafSegment = dirPath.substring(dirPath.lastIndexOf('/') + 1);
        String name = stringField(frontmatter, "name", leafSegment);
        String description = stringField(frontmatter, "description", null);
        if (description == null) {
            throw new SkillLoadException("SKILL.md missing required 'description' frontmatter at " + skillMdPath);
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new SkillLoadException("SKILL.md 'description' exceeds " + MAX_DESCRIPTION_CHARS
                + " characters (" + description.length() + ") at " + skillMdPath);
        }
        List<String> allowedTools = allowedTools(frontmatter);
        SkillMetadata metadata = buildMetadata(frontmatter);

        Prompt bodyPrompt = toPrompt("skillsjars:" + name + ":body", body, skillMdPath);
        Prompt descriptionPrompt = toPrompt("skillsjars:" + name + ":description", description, skillMdPath);
        Map<String, Prompt> resources = loadResources(loader, dirPath, name, allPaths);

        return new TextSkill(name, descriptionPrompt, bodyPrompt, resources, allowedTools, metadata);
    }

    private static Map<String, Object> parseFrontmatter(String yamlText, String pathForError) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = YAML.readValue(yamlText, Map.class);
            return parsed == null ? Map.of() : parsed;
        }
        catch (IOException e) {
            throw new SkillLoadException("Failed to parse YAML frontmatter in " + pathForError, e);
        }
    }

    private static String stringField(Map<String, Object> fm, String key, String fallback) {
        Object v = fm.get(key);
        return v == null ? fallback : v.toString();
    }

    /** The {@code allowed-tools} list of the front matter; absent means the skill names no tools. */
    private static List<String> allowedTools(Map<String, Object> fm) {
        Object v = fm.get(ALLOWED_TOOLS);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new SkillLoadException("Frontmatter field '" + ALLOWED_TOOLS + "' must be a list, got " + v.getClass().getSimpleName());
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(item.toString());
        }
        return out;
    }

    /**
     * Builds {@link SkillMetadata} from top-level {@code license} and a nested
     * {@code metadata} map. Nested keys override top-level when both are present (authors
     * can use the nested block for everything, or mix top-level canonical fields with
     * extensions).
     */
    private static SkillMetadata buildMetadata(Map<String, Object> fm) {
        SkillMetadata md = new SkillMetadata();
        md.setOrigin("skillsjars");
        if (fm.containsKey("license")) {
            md.setLicense(fm.get("license").toString());
        }
        Object nested = fm.get("metadata");
        if (nested instanceof Map<?, ?> nestedMap) {
            applyNestedMetadata(md, nestedMap);
        }
        else if (nested != null) {
            throw new SkillLoadException("Frontmatter field 'metadata' must be a map, got " + nested.getClass().getSimpleName());
        }
        return md;
    }

    private static void applyNestedMetadata(SkillMetadata md, Map<?, ?> nested) {
        Object author = nested.get("author");
        if (author != null) md.setAuthor(author.toString());
        Object license = nested.get("license");
        if (license != null) md.setLicense(license.toString());
        Object origin = nested.get("origin");
        if (origin != null) md.setOrigin(origin.toString());
        Object bundleId = nested.get("bundle_id");
        if (bundleId != null) md.setBundleId(bundleId.toString());
        Object triggerKeyword = nested.get("trigger_keyword");
        if (triggerKeyword != null) md.setTriggerKeyword(triggerKeyword.toString());
    }

    private static Map<String, Prompt> loadResources(ClassLoader loader, String dirPath, String skillName, Set<String> allPaths) {
        String prefix = RESOURCE_ROOT + dirPath + "/";
        Map<String, Prompt> resources = new LinkedHashMap<>();
        for (String path : allPaths) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String relative = path.substring(prefix.length());
            // Skip SKILL.md for the current skill, and any nested SKILL.md which belongs
            // to a separate skill bundle and will be registered on its own.
            if ("SKILL.md".equals(relative) || relative.endsWith("/SKILL.md")) {
                continue;
            }
            String content = readResource(loader, path);
            Prompt resourcePrompt = toPrompt("skillsjars:" + skillName + ":resource:" + relative, content, path);
            resources.put(relative, resourcePrompt);
        }
        return resources;
    }

    /**
     * Wraps {@link Prompts#of(String, String)} to translate the checked
     * {@link PromptNotFoundException} / {@link GuardrailException} into an unchecked
     * {@link SkillLoadException}. These exceptions at bundle-load time are authoring
     * errors, not runtime LLM correctables.
     */
    private static Prompt toPrompt(String key, String text, String pathForError) {
        try {
            return Prompts.of(key, text);
        }
        catch (PromptNotFoundException | GuardrailException e) {
            throw new SkillLoadException("Failed to register prompt under key '" + key +
                "' from " + pathForError, e);
        }
    }

    private static String readResource(ClassLoader loader, String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new SkillLoadException("Resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new SkillLoadException("Failed to read resource: " + path, e);
        }
    }
}
