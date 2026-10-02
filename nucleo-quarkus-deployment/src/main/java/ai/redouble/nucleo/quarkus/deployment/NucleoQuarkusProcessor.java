/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus.deployment;

import io.quarkus.deployment.*;
import io.quarkus.deployment.annotations.*;
import io.quarkus.deployment.builditem.*;
import io.quarkus.deployment.builditem.nativeimage.*;
import io.quarkus.deployment.pkg.builditem.*;
import org.jboss.jandex.*;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * The build-time steps that make the Nucleo runtime work in a GraalVM native image. They run
 * inside the Quarkus build of an application that depends on nucleo-quarkus; nothing here runs
 * at application runtime, and nucleo-core carries no Quarkus dependency.
 *
 * <p>What native image needs, and which step supplies it: the runtime reaches its tool, artifact
 * and model POJOs reflectively (Jackson and the hand-rolled schema walker), so {@link #reflection}
 * registers the transitive closure of those types; the runtime starts thread pools in singletons
 * that must not be initialized at build time, so {@link #runtimeInit} defers them; provider,
 * settings and configurator discovery is ServiceLoader, so {@link #services} honors the service
 * files; skills and catalog fragments are resources a scan cannot find, so {@link #resources}
 * includes them; and {@link #typeAliasCheck} fails the build if a concrete Artifact lacks the
 * {@code @TypeAlias} the serializer needs, the invariant the runtime scan used to enforce.
 *
 * <p>These steps register Nucleo's OWN types ({@code ai.redouble.*}). They never register a
 * third-party dependency's classes. Native support for a third-party dependency is never
 * hand-rolled here or anywhere: no hand-written reflection or resource config for a dependency,
 * and no extra jars added to make one link. A dependency the native image needs is carried by
 * its own Quarkus or Quarkiverse extension, or it stays out of the native build (JVM-only behind
 * a maven profile). Do not add a dependency's classes to {@link #reflection} to get past a native
 * failure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class NucleoQuarkusProcessor {
    private static final String FEATURE = "nucleo";
    private static final String PACKAGE_PREFIX = "ai.redouble.";

    private static final DotName TOOL = DotName.createSimple("ai.redouble.nucleo.tools.Tool");
    private static final DotName THINKER_INPUT = DotName.createSimple("ai.redouble.nucleo.tools.thinking.ThinkerInput");
    private static final DotName THINKER_OUTPUT = DotName.createSimple("ai.redouble.nucleo.tools.thinking.ThinkerOutput");
    private static final DotName REASONING = DotName.createSimple("ai.redouble.nucleo.harness.schema.Reasoning");
    private static final DotName ARTIFACT = DotName.createSimple("ai.redouble.nucleo.harness.artifacts.Artifact");
    private static final DotName MODEL_SPEC = DotName.createSimple("ai.redouble.nucleo.harness.models.AbstractModelSpec");
    // reflectively instantiated by Reflection.newInstance from a settings Class field or a factory,
    // so their concrete types need a registered no-arg (or Identifiable) constructor under native
    private static final DotName MODELS_BACKEND = DotName.createSimple("ai.redouble.nucleo.harness.models.ModelsBackend");
    private static final DotName MODEL_PICKER = DotName.createSimple("ai.redouble.nucleo.harness.models.ModelPicker");
    private static final DotName SECRETS = DotName.createSimple("ai.redouble.nucleo.secrets.Secrets");
    private static final DotName BLOCK_ENCODER = DotName.createSimple("ai.redouble.nucleo.harness.llm.encode.BlockEncoder");
    private static final DotName RATE_LIMITER = DotName.createSimple("ai.redouble.nucleo.harness.admission.RateLimiter");
    private static final DotName SCHEMA_REFINER = DotName.createSimple("ai.redouble.nucleo.tools.SchemaRefiner");
    private static final DotName TOOL_NAME = DotName.createSimple("ai.redouble.nucleo.tools.ToolName");
    private static final DotName TYPE_ALIAS = DotName.createSimple("ai.redouble.nucleo.harness.schema.TypeAlias");
    private static final DotName STATIC_PROMPT = DotName.createSimple("ai.redouble.nucleo.prompt.StaticPrompt");
    private static final DotName DYNAMIC_PROMPT = DotName.createSimple("ai.redouble.nucleo.prompt.DynamicPrompt");
    private static final DotName MCP = DotName.createSimple("ai.redouble.nucleo.mcp.MCP");
    private static final DotName JSON_DESERIALIZE = DotName.createSimple("com.fasterxml.jackson.databind.annotation.JsonDeserialize");
    private static final DotName JSON_SERIALIZE = DotName.createSimple("com.fasterxml.jackson.databind.annotation.JsonSerialize");
    // A structured LLM output/input POJO marks its members with these; the schema walker reads the
    // shape and Jackson deserializes the model's answer into it, both reflectively. Such a POJO is
    // often an ad-hoc parse target a tool names in code, reached from no type-hierarchy root.
    private static final DotName LLM_DESCRIPTION = DotName.createSimple("ai.redouble.nucleo.harness.schema.LLMDescription");
    private static final DotName LLM_REQUIRED = DotName.createSimple("ai.redouble.nucleo.harness.schema.LLMRequired");
    // The holder a thinker puts its prompt and input in, rendered as JSON into the head of every
    // conversation it runs. It is in no hierarchy above and carries none of the annotations, and the
    // serializer writes an unregistered bean as {} rather than failing, so without it every thinker's
    // model would receive an empty objective under native.
    private static final DotName THINKER_OBJECTIVE = DotName.createSimple("ai.redouble.nucleo.tools.thinking.ThinkerObjective");

    /**
     * The runtime's singletons that start a thread or pool in their initializer. Initialized at
     * run time so no live thread is captured in the image heap. This list is grown from the
     * "started Thread in the image heap" errors an actual native build reports, not guessed.
     */
    private static final String[] THREADING_SINGLETONS = {
        "ai.redouble.nucleo.harness.conversation.ConversationService",
        "ai.redouble.nucleo.harness.JobDispatcher",
        "ai.redouble.nucleo.harness.Heart",
        "ai.redouble.nucleo.harness.admission.Admission",
        "ai.redouble.nucleo.harness.LinkedQueueMessageBus",
        "ai.redouble.nucleo.mcp.STDIOEndpointReaper",
        "ai.redouble.nucleo.jdbc.JdbcResourceProvider",
    };

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Indexes nucleo-core, the provider modules and the demo engine so the reflection closure can
     * see their classes. Quarkus's combined index covers the application and jandex-bearing jars
     * only; these carry no index, so their Tool, Artifact and model-spec types would be invisible
     * to {@link #reflection} without this.
     */
    @BuildStep
    void indexDependencies(BuildProducer<IndexDependencyBuildItem> index) {
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-core"));
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-demo-engine"));
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-provider-openai"));
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-provider-anthropic"));
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-provider-bedrock"));
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-provider-systemone"));
        // a skilljar: indexing it makes it an application archive the skill manifest step walks
        index.produce(new IndexDependencyBuildItem("ai.redouble", "nucleo-skills"));
    }

    /**
     * Packages that must be class-initialized at run time, not build time: the provider client
     * packages, whose classes set up SDK clients and read credentials in static initializers that
     * belong at application start; the shared HTTP client package, whose Apache HttpClient holds a
     * live SSL context that cannot be captured in the image heap; and org.reflections, whose
     * scanners capture live state in their initializers. The runtime uses org.reflections only for
     * the classpath scans a native application does not run - it discovers through ServiceLoader and
     * the generated manifests instead. Its one native-hostile class, the JBoss VFS handler that
     * references an absent optional dependency, is deleted from the image by the substitution in the
     * nucleo-quarkus runtime module, not handled here.
     */
    private static final String[] RUNTIME_INIT_PACKAGES = {
        "ai.redouble.nucleo.providers.openai",
        "ai.redouble.nucleo.providers.anthropic",
        "ai.redouble.nucleo.providers.bedrock",
        "ai.redouble.nucleo.providers.systemone",
        "ai.redouble.nucleo.http",
        "org.reflections",
    };

    @BuildStep
    void runtimeInit(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        for (String singleton : THREADING_SINGLETONS) {
            runtimeInit.produce(new RuntimeInitializedClassBuildItem(singleton));
        }
    }

    @BuildStep
    void runtimeInitPackages(BuildProducer<RuntimeInitializedPackageBuildItem> runtimeInit) {
        for (String pkg : RUNTIME_INIT_PACKAGES) {
            runtimeInit.produce(new RuntimeInitializedPackageBuildItem(pkg));
        }
    }

    @BuildStep
    void services(BuildProducer<ServiceProviderBuildItem> services) {
        services.produce(ServiceProviderBuildItem.allProvidersFromClassPath("ai.redouble.nucleo.harness.llm.ClientProvider"));
        services.produce(ServiceProviderBuildItem.allProvidersFromClassPath("ai.redouble.nucleo.Settings"));
        services.produce(ServiceProviderBuildItem.allProvidersFromClassPath("ai.redouble.nucleo.NucleoConfigurator"));
    }

    @BuildStep
    void resources(BuildProducer<NativeImageResourcePatternsBuildItem> resources) {
        resources.produce(NativeImageResourcePatternsBuildItem.builder()
                .includeGlobs("META-INF/skills/**", "META-INF/nucleo/seed_models.json", "META-INF/nucleo/skills.idx")
                .build());
    }

    /**
     * Writes the skill manifest {@code META-INF/nucleo/skills.idx} - every resource path under
     * {@code META-INF/skills/} across the application's archives, one per line - so the runtime
     * finds skills by reading the manifest instead of scanning the classpath, which a native
     * image cannot do. The manifest is generated from the build's own view of the archives, so
     * it lists exactly what ships.
     */
    @BuildStep
    void skillsManifest(ApplicationArchivesBuildItem archives, BuildProducer<GeneratedResourceBuildItem> generated) {
        Set<String> paths = new TreeSet<>();
        for (ApplicationArchive archive : archives.getAllArchives()) {
            for (Path root : archive.getRootDirectories()) {
                Path skills = root.resolve("META-INF/skills");
                if (!Files.isDirectory(skills)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(skills)) {
                    walk.filter(Files::isRegularFile)
                            .forEach(file -> paths.add(root.relativize(file).toString().replace(File.separatorChar, '/')));
                }
                catch (IOException e) {
                    throw new IllegalStateException("Could not list skill resources under " + skills, e);
                }
            }
        }
        String manifest = String.join("\n", paths);
        generated.produce(new GeneratedResourceBuildItem("META-INF/nucleo/skills.idx",
                manifest.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Registers for reflection the transitive closure of the runtime's serialized types: every
     * Tool, and every subtype of the artifact, thinker-output, reasoning, model-spec and
     * thinker-input hierarchies, plus the thinker objective that carries a thinker's prompt and
     * input into its conversation, plus everything annotated with a Nucleo runtime annotation, plus
     * every class that declares an {@code @LLMDescription} or {@code @LLMRequired} member (a
     * structured LLM output or input POJO a tool names as a parse target, reached from no type
     * hierarchy), closed over their field, array, collection-element, map-value, enum and superclass
     * types within the ai.redouble packages, plus every class named by a {@code @JsonDeserialize} or
     * {@code @JsonSerialize} annotation. Constructors, methods and fields are registered because
     * Jackson and the schema walker read all three.
     */
    @BuildStep
    void reflection(CombinedIndexBuildItem combined, BuildProducer<ReflectiveClassBuildItem> reflective) {
        IndexView index = combined.getIndex();
        Set<DotName> roots = new HashSet<>();
        roots.add(THINKER_OBJECTIVE);
        for (DotName base : List.of(TOOL, THINKER_INPUT, THINKER_OUTPUT, REASONING, ARTIFACT, MODEL_SPEC,
                MODELS_BACKEND, MODEL_PICKER, SECRETS, BLOCK_ENCODER, RATE_LIMITER, SCHEMA_REFINER)) {
            for (ClassInfo impl : index.getAllKnownImplementors(base)) {
                roots.add(impl.name());
            }
            for (ClassInfo sub : index.getAllKnownSubclasses(base)) {
                roots.add(sub.name());
            }
        }
        for (DotName annotation : List.of(TOOL_NAME, TYPE_ALIAS, STATIC_PROMPT, DYNAMIC_PROMPT, MCP)) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                if (instance.target().kind() == AnnotationTarget.Kind.CLASS) {
                    roots.add(instance.target().asClass().name());
                }
            }
        }
        for (DotName annotation : List.of(JSON_DESERIALIZE, JSON_SERIALIZE)) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                for (String member : List.of("using", "as", "contentUsing", "keyUsing")) {
                    AnnotationValue value = instance.value(member);
                    if (value != null) {
                        roots.add(value.asClass().name());
                    }
                }
            }
        }
        for (DotName annotation : List.of(LLM_DESCRIPTION, LLM_REQUIRED)) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                ClassInfo declaring = declaringClass(instance.target());
                if (declaring != null) {
                    roots.add(declaring.name());
                }
            }
        }
        Set<DotName> closure = new HashSet<>();
        Deque<DotName> worklist = new ArrayDeque<>(roots);
        while (!worklist.isEmpty()) {
            DotName name = worklist.poll();
            if (!closure.add(name)) {
                continue;
            }
            ClassInfo info = index.getClassByName(name);
            if (info == null) {
                continue;
            }
            for (FieldInfo field : info.fields()) {
                collectTypes(field.type(), worklist);
            }
            if (info.superName() != null && info.superName().toString().startsWith(PACKAGE_PREFIX)) {
                worklist.add(info.superName());
            }
        }
        String[] names = closure.stream().map(DotName::toString).toArray(String[]::new);
        reflective.produce(ReflectiveClassBuildItem.builder(names).constructors().methods().fields().build());
    }

    /** The class that declares an annotated element, whether the annotation sits on the class, a field, a method, or a method parameter. */
    private static ClassInfo declaringClass(AnnotationTarget target) {
        return switch (target.kind()) {
            case CLASS -> target.asClass();
            case FIELD -> target.asField().declaringClass();
            case METHOD -> target.asMethod().declaringClass();
            case METHOD_PARAMETER -> target.asMethodParameter().method().declaringClass();
            case RECORD_COMPONENT -> target.asRecordComponent().declaringClass();
            default -> null;
        };
    }

    /** Adds to the worklist every ai.redouble class a field type reaches: the type itself, an array's component, and a parameterized type's arguments. */
    private static void collectTypes(Type type, Deque<DotName> worklist) {
        switch (type.kind()) {
            case CLASS -> add(type.name(), worklist);
            case ARRAY -> collectTypes(type.asArrayType().constituent(), worklist);
            case PARAMETERIZED_TYPE -> {
                add(type.name(), worklist);
                for (Type argument : type.asParameterizedType().arguments()) {
                    collectTypes(argument, worklist);
                }
            }
            default -> {
            }
        }
    }

    private static void add(DotName name, Deque<DotName> worklist) {
        if (name.toString().startsWith(PACKAGE_PREFIX)) {
            worklist.add(name);
        }
    }

    /**
     * Fails the build if a concrete Artifact subtype does not carry {@code @TypeAlias}: the
     * serializer resolves an artifact's wire name through that annotation, so a missing one is a
     * runtime deserialization fault the runtime scan used to catch at startup. Reproducing the
     * check here, at build time, keeps the invariant without the scan a native image cannot run.
     */
    @BuildStep
    @Produce(ArtifactResultBuildItem.class)
    void typeAliasCheck(CombinedIndexBuildItem combined) {
        IndexView index = combined.getIndex();
        List<String> missing = new ArrayList<>();
        for (ClassInfo artifact : index.getAllKnownImplementors(ARTIFACT)) {
            if (Modifier.isAbstract(artifact.flags()) || Modifier.isInterface(artifact.flags())) {
                continue;
            }
            if (artifact.declaredAnnotation(TYPE_ALIAS) == null) {
                missing.add(artifact.name().toString());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Every concrete Artifact must carry @TypeAlias; missing on: " + missing);
        }
    }
}
