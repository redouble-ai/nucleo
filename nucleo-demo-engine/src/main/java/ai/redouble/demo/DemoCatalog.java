/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * Which model catalog this process runs on, and what to do when it is not this account's.
 * The runtime reads {@code models.json} the way logback reads its configuration: the file
 * {@code -Dnucleo.models} names, else the classpath, nearest first. The demo keeps it in its
 * module's {@code src/main/resources} ({@link DemoHome}), from where every build carries it onto
 * the classpath. Without one, the runtime runs on the providers' shipped defaults, which know
 * nothing of this account's models, limits or pins; the demo then says so at startup and at the
 * top of its page, with the steps that fix it. The discovery itself is the person's to run, through the page's
 * Discover button, their coding agent or {@code AGENTS.md}: it spends on their account and
 * writes a file they own, so the demo never runs it unasked. Once that file exists, the page
 * edits it here too - a grade, a status, a pin - each edit validated by loading before it is
 * written and adopted by the runtime live.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class DemoCatalog {
    private static final Logger log = LoggerFactory.getLogger(DemoCatalog.class);
    /** The hosting demo's module directory, where a person opens the terminal - each host names its own. */
    private final String module;
    /**
     * The discovery command as a person runs it from that directory, after building the host -
     * each host names its own, and a host that runs the discovery from its page alone names null.
     */
    private final String discoverCommand;

    public DemoCatalog(String module, String discoverCommand) {
        this.module = module;
        this.discoverCommand = discoverCommand;
    }

    /**
     * What the page and the startup log say about the catalog: the file the demo keeps it in
     * ({@code catalogFile}, null when the process runs outside its module), where it
     * was read from (null when nowhere, and the runtime is on the shipped defaults), the file
     * the page's edits land in (null when the catalog in use is not a file of the deployment's
     * own: the shipped defaults, or a copy packaged on the classpath), how many entries it has,
     * each grade's order (its entries in the deployment's order of preference, the first the
     * grade's default), the single pins (the embeddings and the decision entry), and the steps a
     * person takes when something is missing.
     */
    public record Status(String workingDirectory, String catalogFile, String source, String editableFile, boolean found, int entries,
                         Map<String, List<String>> orders, Map<String, String> pins, List<String> instructions) {}

    /** The catalog as this process reads it now, and the file the demo writes it to ({@link DemoHome#catalogFile()}, null outside its module). */
    public Status status() {
        return status(Path.of("").toAbsolutePath(), DemoHome.catalogFile(), JsonModelsBackend.deploymentFile());
    }

    /** Over an explicit directory, file written to and loaded catalog, so the wording is testable without moving the process. */
    Status status(Path workingDirectory, Path written, JsonModelsBackend found) {
        if (found == null) {
            List<String> steps = new ArrayList<>();
            steps.add("There is no models.json on this process's classpath, and -Dnucleo.models names none. The demo runs on"
                    + " the providers' shipped defaults: their published models and entry-tier limits, none of this"
                    + " account's, and no pins.");
            steps.add(written != null
                    ? "The demo keeps its catalog in " + written + "; connect a provider and press Discover models to write it,"
                            + " or edit the models table, which starts the file from the shipped defaults."
                    : "This process runs outside the demo's module, " + module + ", so it has no file to keep a catalog in."
                            + " Run it from its checkout, where the catalog lives in " + module + "/src/main/resources/models.json"
                            + " and every build carries it, or name a file with -Dnucleo.models=<path>.");
            steps.add("Or ask your coding agent to follow AGENTS.md steps 3 to 5.");
            if (discoverCommand != null) {
                steps.add("Or run the discovery yourself from the module: " + discoverCommand + ".");
            }
            return new Status(workingDirectory.toString(), written != null ? written.toString() : null, null, null, false, 0, null, null,
                    List.copyOf(steps));
        }
        CatalogPins pins = found.pins();
        Map<String, List<String>> orders = new LinkedHashMap<>();
        Map<String, String> pinned = new LinkedHashMap<>();
        List<String> instructions = new ArrayList<>();
        if (pins == null) {
            instructions.add("Order the models of each grade in the models table: the first one a grade can call serves it.");
        }
        else {
            for (Grade grade : Grade.rungs()) {
                if (pins.grades().containsKey(grade)) {
                    orders.put(grade.name(), pins.grades().get(grade));
                }
            }
            if (pins.embeddings() != null) {
                pinned.put("embeddings", pins.embeddings());
            }
            if (pins.decision() != null) {
                pinned.put("decision", pins.decision());
            }
        }
        Path editable = editableFile(found);
        return new Status(workingDirectory.toString(), written != null ? written.toString() : null, describeSource(found.source()),
                editable != null ? editable.toString() : null, true, found.all().size(), orders, pinned, instructions);
    }

    /**
     * The file the loaded catalog came from, when it is a file of the deployment's own, else
     * null: a catalog read from a classpath resource or a jar is the build's copy, and an edit
     * to it would be lost on the next build, so the page edits only what a discovery wrote.
     */
    static Path editableFile(JsonModelsBackend found) {
        if (found == null || found.source() == null) {
            return null;
        }
        try {
            Path file = Path.of(found.source());
            return Files.isRegularFile(file) ? file : null;
        }
        catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * An edit from the page to one entry of the deployment's own catalog file: its grade (a rung
     * of the ladder; only an entry that carries one, since an embeddings entry, a decision entry
     * or a model of another modality is on no rung) and its status, where a person's words are {@code OPEN}
     * and {@code DISABLED} alone - {@code DEPRECATED} is the vendor's, {@code UNLISTED} the
     * account's and {@code UNREACHABLE} this deployment's reach, all written by the discovery;
     * its prices per million tokens in the entry's currency
     * (an input price on any entry, an output price only on an LLM entry, since an embeddings or
     * a decision call has no output the provider bills; never negative); and a confirmation. A
     * null leaves the field as it is. An entry moved to another grade leaves the order of the
     * grade it left. The embeddings or decision entry cannot be disabled; the pin comes off
     * first. An entry the discovery inferred ({@code unverified}) stops being so when a person
     * edits its grade or a price, or confirms it as it is. The file is validated by loading it
     * before it is written, so a price with no currency to be in is refused by the loader's own
     * rule, and the runtime adopts it live.
     *
     * @throws IllegalArgumentException when the entry, the grade, the status or a price is not one the edit can apply to
     * @throws IllegalStateException    when the process has no file to edit or create: outside its module, on the build's copy
     */
    public Status editEntry(String id, String grade, String status, Double inputPricePerMillion, Double outputPricePerMillion, Boolean confirmed) {
        return edit(catalog -> {
            ObjectNode entry = entryOf(catalog, id);
            if (inputPricePerMillion != null) {
                price(entry, id, "input_price_per_million", inputPricePerMillion);
            }
            if (outputPricePerMillion != null) {
                if (!entry.hasNonNull("grade")) {
                    throw new IllegalArgumentException(id + " has no output price: an embeddings or a decision call has no output the provider bills");
                }
                price(entry, id, "output_price_per_million", outputPricePerMillion);
            }
            if (grade != null || inputPricePerMillion != null || outputPricePerMillion != null || Boolean.TRUE.equals(confirmed)) {
                // a person's word replaces the discovery's inference, on the field edited or on the whole entry
                entry.remove("unverified");
            }
            if (grade != null) {
                Grade rung = rung(grade);
                if (!entry.hasNonNull("grade")) {
                    throw new IllegalArgumentException(id + " carries no grade: an embeddings entry, a decision entry, or a model of another modality, is on no rung");
                }
                if (!rung.name().equals(entry.get("grade").asText())) {
                    // its place was in the order of the grade it leaves; in the new grade it starts unplaced
                    leaveOrders(catalog, id);
                }
                entry.put("grade", rung.name());
            }
            if (status != null) {
                ModelStatus chosen;
                try {
                    chosen = ModelStatus.valueOf(status);
                }
                catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("'" + status + "' is not a status; OPEN and DISABLED are the ones a person sets");
                }
                if (chosen != ModelStatus.OPEN && chosen != ModelStatus.DISABLED) {
                    throw new IllegalArgumentException(chosen + " is not a person's word: DEPRECATED is the vendor's, UNLISTED the account's"
                            + " and UNREACHABLE this deployment's reach, all written by the discovery; a person sets OPEN or DISABLED");
                }
                List<String> pinnedFor = pinnedFor(catalog, id);
                if (chosen == ModelStatus.DISABLED && !pinnedFor.isEmpty()) {
                    throw new IllegalArgumentException(id + " is the default for " + String.join(", ", pinnedFor) + "; choose another default before disabling it");
                }
                if (chosen == ModelStatus.OPEN) {
                    entry.remove("status");
                }
                else {
                    entry.put("status", chosen.name());
                }
            }
        });
    }

    /** A list price per million tokens is a non-negative number; the currency it is in is the entry's, checked by the loader. */
    private static void price(ObjectNode entry, String id, String field, double price) {
        if (price < 0 || Double.isNaN(price) || Double.isInfinite(price)) {
            throw new IllegalArgumentException(id + ": a price per million tokens is a non-negative number, got " + price);
        }
        entry.put(field, price);
    }

    /**
     * A single pin from the page: {@code slot} is {@code embeddings} (the embeddings entry) or
     * {@code decision} (the entry that answers decisions); a null id clears it. A grade is no
     * pin slot: its entries are ordered ({@link #order}). Only an open entry can be pinned, and
     * the loader's own rules apply on top: the embeddings pin names an embeddings entry, the
     * decision pin a decision entry. Validated by loading before it is written, and adopted live.
     *
     * @throws IllegalArgumentException when the slot, the entry or the pairing is not one the loader accepts
     * @throws IllegalStateException    when the process has no file to edit or create: outside its module, on the build's copy
     */
    public Status pin(String slot, String id) {
        if (!"embeddings".equals(slot) && !"decision".equals(slot)) {
            throw new IllegalArgumentException("'" + slot + "' is not a pin; the pins are embeddings and decision, and a grade's"
                    + " entries are ordered instead");
        }
        return edit(catalog -> {
            ObjectNode pins = pinsOf(catalog);
            if (id == null) {
                pins.remove(slot);
            }
            else {
                ObjectNode entry = entryOf(catalog, id);
                if (entry.hasNonNull("status") && !ModelStatus.OPEN.name().equals(entry.get("status").asText())) {
                    throw new IllegalArgumentException(id + " is " + entry.get("status").asText() + "; only an open entry can be pinned");
                }
                pins.put(slot, id);
            }
            if (pins.isEmpty()) {
                catalog.remove("pins");
            }
        });
    }

    /**
     * A grade's order from the page: its entries in the deployment's order of preference, the
     * first the grade's default, replacing the order it had; an empty list clears it, leaving the
     * grade to its cheapest entries. The runtime serves a request of the grade from the first
     * entry of the order it can call that accepts what the request sends. The loader's rules
     * apply on top: every id an entry of the catalog, a graded LLM entry of the grade or above,
     * each once. Validated by loading before it is written, and adopted live.
     *
     * @throws IllegalArgumentException when the grade or an entry is not one the loader accepts
     * @throws IllegalStateException    when the process has no file to edit or create: outside its module, on the build's copy
     */
    public Status order(String grade, List<String> ids) {
        Grade rung = rung(grade);
        return edit(catalog -> {
            ObjectNode pins = pinsOf(catalog);
            if (ids.isEmpty()) {
                pins.remove(rung.name());
            }
            else {
                ArrayNode order = pins.putArray(rung.name());
                for (String id : ids) {
                    entryOf(catalog, id);
                    order.add(id);
                }
            }
            if (pins.isEmpty()) {
                catalog.remove("pins");
            }
        });
    }

    /** Takes the entry out of every grade's order, dropping an order it leaves empty and the pins object when nothing is left. */
    private static void leaveOrders(ObjectNode catalog, String id) {
        if (!catalog.path("pins").isObject()) {
            return;
        }
        ObjectNode pins = (ObjectNode) catalog.get("pins");
        List<String> emptied = new ArrayList<>();
        pins.properties().forEach(pin -> {
            if (pin.getValue().isArray()) {
                ArrayNode order = (ArrayNode) pin.getValue();
                for (int i = order.size() - 1; i >= 0; i--) {
                    if (id.equals(order.get(i).asText())) {
                        order.remove(i);
                    }
                }
                if (order.isEmpty()) {
                    emptied.add(pin.getKey());
                }
            }
        });
        emptied.forEach(pins::remove);
        if (pins.isEmpty()) {
            catalog.remove("pins");
        }
    }

    private static ObjectNode pinsOf(ObjectNode catalog) {
        return catalog.hasNonNull("pins") && catalog.get("pins").isObject() ? (ObjectNode) catalog.get("pins") : catalog.putObject("pins");
    }

    /**
     * One edit to the deployment's own file: read, change, validate by loading (the loader's
     * rules are the catalog's rules, and a file that would not load is never written), write,
     * reload the runtime's catalog, and answer the status the page renders from. A first edit
     * with no file yet is the person's action that creates it ({@link DemoHome#materialize()}),
     * started from the shipped defaults so the edit has a file to land in.
     */
    private Status edit(Consumer<ObjectNode> change) {
        JsonModelsBackend found = JsonModelsBackend.deploymentFile();
        Path file = editableFile(found);
        if (DemoHome.catalogFile() != null && !DemoHome.catalogFile().equals(file)) {
            // an edit is a person's action, so it may create the deployment's file: the shipped
            // defaults are materialized into the demo's home for the edit to land in, and a
            // build's stale copy is never edited when the demo has a home of its own
            DemoHome.materialize();
            found = JsonModelsBackend.deploymentFile();
            file = editableFile(found);
        }
        if (file == null) {
            throw new IllegalStateException(found == null
                    ? "There is no catalog of this deployment's own to edit, and this process runs outside the demo's module,"
                            + " so it has no file to write one to. Run the demo from its checkout, or name a file with -D"
                            + JsonModelsBackend.PROPERTY + "."
                    : "The catalog in use, " + describeSource(found.source()) + ", is the build's copy, and an edit to it would be lost"
                            + " on the next build. Press Discover models to write a file of the deployment's own.");
        }
        try {
            ObjectNode catalog = (ObjectNode) NucleoJsonSerializer.readTree(Files.readString(file, StandardCharsets.UTF_8));
            change.accept(catalog);
            String json = NucleoJsonSerializer.write(catalog);
            try {
                new JsonModelsBackend(List.of(new JsonModelsBackend.Layer(file.toString(), json, false)));
            }
            catch (UncorrectableRuntimeLLMException refused) {
                throw new IllegalArgumentException(refused.getMessage(), refused);
            }
            Files.writeString(file, json, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("The catalog " + file + " could not be read or written", e);
        }
        Models.reload();
        return status();
    }

    private static ObjectNode entryOf(ObjectNode catalog, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("an edit names the entry it applies to");
        }
        for (JsonNode model : catalog.path("models")) {
            if (id.equals(model.path("id").asText()) && model.isObject()) {
                return (ObjectNode) model;
            }
        }
        throw new IllegalArgumentException("No entry '" + id + "' in the catalog");
    }

    private static Grade rung(String name) {
        Grade grade;
        try {
            grade = Grade.valueOf(name);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a grade; the rungs are " + Grade.rungs());
        }
        if (!grade.isRung()) {
            throw new IllegalArgumentException(name + " is not a rung of the ladder; the rungs are " + Grade.rungs());
        }
        return grade;
    }

    /**
     * The single pins naming an entry, so a pinned embeddings or decision entry is never disabled
     * under its pin. A grade's order is no such pin: a disabled entry keeps its place and the
     * runtime passes over it until it is enabled again.
     */
    private static List<String> pinnedFor(ObjectNode catalog, String id) {
        List<String> slots = new ArrayList<>();
        JsonNode pins = catalog.path("pins");
        pins.properties().forEach(pin -> {
            if (pin.getValue().isTextual() && id.equals(pin.getValue().asText())) {
                slots.add(pin.getKey());
            }
        });
        return slots;
    }

    /**
     * The catalog's source in words a person reads. The backend names a filesystem layer by
     * its path already; a classpath hit arrives as the class loader's URL, and a jar-borne
     * catalog comes in Boot's nested-jar spelling
     * ({@code jar:nested:/x/app.jar/!BOOT-INF/classes/!/models.json}) - internal addressing
     * nobody should have to parse on a first-contact page. A file URL becomes its path; a jar
     * URL becomes "packaged inside <jar> at build time", keeping the jar's own path for
     * finding it; anything unrecognized shows verbatim, since a truthful odd spelling beats a
     * wrong translation.
     */
    static String describeSource(String source) {
        if (source == null) {
            return null;
        }
        if (source.startsWith("file:")) {
            try {
                return Path.of(java.net.URI.create(source)).toString();
            }
            catch (RuntimeException e) {
                return source;
            }
        }
        int jarEnd = source.indexOf(".jar");
        if (source.startsWith("jar:") && jarEnd > 0) {
            String jarPath = source.substring(source.indexOf(":/", 4) + 1, jarEnd + ".jar".length());
            String jarName = jarPath.substring(jarPath.lastIndexOf('/') + 1);
            return "models.json packaged inside " + jarName + " at build time (" + jarPath + ")";
        }
        return source;
    }

    /**
     * Once the application is up: the catalog it runs on, and the steps to fix it when it is not
     * this account's. Each host calls this when it is ready to serve, and stays quiet under its
     * {@code discover} command, which is the fix running.
     */
    public void logReport() {
        Status status = status();
        if (status.found()) {
            log.info("Model catalog: {} ({} entries, orders {}, pins {})", status.source(), status.entries(),
                    status.orders().isEmpty() ? "none" : status.orders(), status.pins().isEmpty() ? "none" : status.pins());
        }
        if (!status.instructions().isEmpty()) {
            log.warn("The demo's model catalog needs attention:\n  - {}", String.join("\n  - ", status.instructions()));
        }
    }
}
