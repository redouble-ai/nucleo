/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * Where the demo writes its catalog. The runtime reads a catalog off the classpath, the way logback
 * reads its configuration, and an application carries it in {@code src/main/resources}; the demo
 * writes it there, in its own application module, so the file is visible in the project and every
 * build carries it into the jar, the Quarkus application and the native image.
 *
 * <p>The module is found from where the host's own code runs, by Maven's standard layout: the
 * nearest {@code target} directory above it whose parent holds a {@code pom.xml} is the module's
 * build directory, and the module's resources are beside it - from the IDE's {@code target/classes},
 * a Spring Boot jar, a Quarkus application or a native executable alike, whatever directory the
 * process was launched from. A process running with no module around it (a jar or an executable
 * copied to a server) has nowhere to write: it runs on the catalog its build carried, which the
 * page shows as read-only.
 *
 * <p>A running process reads the copy its build put on the classpath, so an edit made since would
 * not reach it; the demo therefore names the file it writes to its own runtime through
 * {@code nucleo.models}, set in code, so what the discovery or the page writes is what this
 * process runs on, and the next build packages it. A {@code -Dnucleo.models} the operator set is
 * left as it is, and is the file the page edits.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
public final class DemoHome {
    private static final Logger log = LoggerFactory.getLogger(DemoHome.class);

    private DemoHome() {}

    /** Maven's default build directory, under which every launch mode's code sits. */
    static final String BUILD_DIRECTORY = "target";
    /** The module's resources, which the build carries onto the classpath. */
    static final String RESOURCES = "src/main/resources";

    /** The file this process writes its catalog to, once {@link #configure} found one; null outside a module. */
    private static volatile Path catalogFile;

    /**
     * Finds the file the host writes its catalog to - its module's
     * {@code src/main/resources/models.json}, or the one the operator named - and, when the file
     * exists, names it to the runtime, which adopts it in the same call ({@link Models#reload()}).
     * A file that does not exist yet is not created and not named: the file is the deployment's
     * record, so it appears only from a person's action - the discovery writing the account's
     * catalog, or a first edit in the models table ({@link #materialize()}) - and until then the
     * runtime serves the providers' shipped fragments and says so. Idempotent. Outside a module
     * nothing is named and the runtime keeps the catalog its build carried.
     *
     * @param host     a class of the host application, whose location identifies its module
     * @param resource a resource the host module carries in its own {@code src/main/resources}
     *                 ({@code application.properties}, {@code application.yaml}), by which the
     *                 module is found where a class loader hides its class files
     */
    public static synchronized void configure(Class<?> host, String resource) {
        Path named = JsonModelsBackend.namedFile();
        if (named != null) {
            catalogFile = named;
            return;
        }
        List<Path> locations = codeLocations(host, resource);
        Path target = null;
        for (Path location : locations) {
            target = moduleCatalog(location);
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            log.info("The demo runs outside its module (its code is at {}): the catalog is the copy its build carried, and the page"
                    + " cannot write one", locations);
            return;
        }
        catalogFile = target;
        if (Files.isRegularFile(target)) {
            System.setProperty(JsonModelsBackend.PROPERTY, target.toString());
            Models.reload();
        }
        else {
            log.info("No catalog file at {} yet: the demo runs on the providers' shipped defaults until Discover models,"
                    + " or a first edit in the models table, writes one", target);
        }
    }

    /** The file this process writes its catalog to, or null when it runs outside its module and edits nothing. */
    public static Path catalogFile() {
        return catalogFile;
    }

    /** The test seam: points the demo at a home of the test's choosing; production code never calls it. */
    static synchronized void homeForTests(Path target) {
        catalogFile = target;
    }

    /**
     * Names the file to the runtime and adopts it ({@link Models#reload()}): the closing step of
     * whatever wrote it - the discovery, or the first edit's materialized defaults. A no-op
     * outside a module, where there is no file to adopt.
     */
    public static synchronized void adopt() {
        Path target = catalogFile;
        if (target != null) {
            System.setProperty(JsonModelsBackend.PROPERTY, target.toString());
            Models.reload();
        }
    }

    /**
     * The first edit's seat: an edit is a person's action, so it may create the deployment's
     * file - the shipped defaults written into the module's {@code models.json} for the edit to
     * land in, adopted by the runtime. A file already there is the person's, left as it is and
     * adopted. Null outside a module, where an edit has nowhere to land.
     */
    public static synchronized Path materialize() {
        Path target = catalogFile;
        if (target == null) {
            return null;
        }
        materializeInto(target);
        adopt();
        return target;
    }

    /** Writes the shipped defaults into the file when none exists; no provider is pinged, so it spends nothing. */
    static void materializeInto(Path target) {
        if (Files.isRegularFile(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, NucleoJsonSerializer.write(shippedCatalog()), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not write the demo's catalog at " + target, e);
        }
    }

    /**
     * Where the host's code may be, every candidate in order: each copy of the host module's own
     * resource as its class loader serves it (under the module's {@code target/classes} from the
     * IDE and in Quarkus dev mode, inside a Spring Boot nested jar, inside a Quarkus application's
     * jar), each copy of the host class's own class file the same way, and the executable, which
     * is where a native image's code is, as it carries no files. The resource comes first because
     * Quarkus's dev-mode class loader serves class files under a scheme of its own that names no
     * file, and plain resources as the files they are. Every candidate is offered, since a resource
     * of that name may also sit in a dependency, which {@link #moduleCatalog} then rejects.
     */
    static List<Path> codeLocations(Class<?> host, String resource) {
        List<Path> locations = new ArrayList<>();
        for (String name : List.of(resource, host.getName().replace('.', '/') + ".class")) {
            try {
                for (URL url : Collections.list(host.getClassLoader().getResources(name))) {
                    Path code = pathOf(url.toString());
                    if (code != null) {
                        locations.add(code);
                    }
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException("Could not enumerate " + name + " on the classpath", e);
            }
        }
        ProcessHandle.current().info().command().map(Path::of).ifPresent(locations::add);
        return locations;
    }

    /** The file a resource URL points at, or the jar holding it; null for a form that names no file. */
    static Path pathOf(String url) {
        if (url.startsWith("file:")) {
            return Path.of(URI.create(url));
        }
        // Spring Boot's nested jar: jar:nested:/path/app.jar/!BOOT-INF/classes/!/application.yaml
        if (url.startsWith("jar:nested:")) {
            String inner = url.substring("jar:nested:".length());
            int end = inner.indexOf("/!");
            return Path.of(URI.create("file:" + (end >= 0 ? inner.substring(0, end) : inner)));
        }
        // a plain jar: jar:file:/path/app.jar!/application.properties
        if (url.startsWith("jar:file:")) {
            int end = url.indexOf("!/");
            return Path.of(URI.create(url.substring("jar:".length(), end >= 0 ? end : url.length())));
        }
        return null;
    }

    /**
     * The module's catalog for code at this location: the nearest {@code target} directory at or
     * above it whose parent holds a {@code pom.xml}, and the parent's
     * {@code src/main/resources/models.json}; null when the code sits in no module.
     */
    static Path moduleCatalog(Path location) {
        if (location == null) {
            return null;
        }
        for (Path dir = location.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path name = dir.getFileName();
            Path module = dir.getParent();
            if (name != null && name.toString().equals(BUILD_DIRECTORY) && module != null && Files.isRegularFile(module.resolve("pom.xml"))) {
                return module.resolve(RESOURCES).resolve(JsonModelsBackend.CLASSPATH_RESOURCE);
            }
        }
        return null;
    }

    /**
     * The providers' shipped fragments merged into one catalog: every fragment's models one after
     * another, and their provider defaults folded together, with the record of the providers it
     * was written for ({@link ProviderLinks#stamp}). It is what the runtime loads off the
     * classpath when a deployment has no file of its own, written out as a file the page can edit.
     */
    static ObjectNode shippedCatalog() throws IOException {
        ObjectNode catalog = NucleoJsonSerializer.createObjectNode();
        ArrayNode models = catalog.putArray("models");
        ObjectNode providerDefaults = catalog.putObject("provider_defaults");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DemoHome.class.getClassLoader();
        }
        for (URL url : Collections.list(loader.getResources(JsonModelsBackend.FRAGMENT_RESOURCE))) {
            JsonNode fragment;
            try (InputStream in = url.openStream()) {
                fragment = NucleoJsonSerializer.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            JsonNode fragmentModels = fragment.get("models");
            if (fragmentModels != null && fragmentModels.isArray()) {
                fragmentModels.forEach(models::add);
            }
            JsonNode defaults = fragment.get("provider_defaults");
            if (defaults != null && defaults.isObject()) {
                defaults.properties().forEach(entry -> providerDefaults.set(entry.getKey(), entry.getValue()));
            }
        }
        // written for this classpath's providers, and saying so, as a discovered catalog does
        ProviderLinks.stamp(catalog, ClientProviders.all());
        return catalog;
    }
}
