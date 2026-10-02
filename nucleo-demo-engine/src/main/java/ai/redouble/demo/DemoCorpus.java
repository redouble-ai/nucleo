/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import org.slf4j.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * Locates the shipped demo corpus, the folder the extract and decide runs read and the page
 * shows, wherever and on whatever OS the process runs. A checkout serves it straight from the
 * source tree; anywhere else the corpus rides the classpath ({@code src/main/resources/corpus}
 * in the build) and is extracted once per process into a temporary directory, because the
 * extractor reads files on a disk, not resources. Either way the page shows a real absolute
 * path in this machine's own spelling - the server resolves it, so separators and roots are
 * never the page's guess. No request names a folder: a different one is read by changing
 * {@link #locate()} here, in the engine both hosts share.
 *
 * <p>The extraction copies the files a manifest names ({@code corpus.manifest}, one relative path
 * per line) rather than walking the {@code corpus} classpath directory, because a native image
 * carries the files but cannot list the directory. Each file is read by its exact resource path,
 * which works the same from a build's output, a jar, or a native image. {@code DemoCorpusTest}
 * pins the manifest to the files actually shipped, so a corpus file added without a manifest line
 * fails the build rather than going missing at runtime.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class DemoCorpus {
    private static final Logger log = LoggerFactory.getLogger(DemoCorpus.class);
    /** The corpus's root on the classpath, as the build carries {@code src/main/resources/corpus}. */
    static final String CLASSPATH_ROOT = "corpus";
    /** The manifest of corpus files, one relative path per line, beside the corpus on the classpath. */
    static final String MANIFEST = "corpus.manifest";
    /**
     * The day the shipped corpus's price story is answered for: 1 June 2026, the day the last
     * price the documents decide takes effect (the twenty percent rise the May minutes decided).
     * On that day every list in the corpus is current or superseded and every later figure is
     * a proposal, so the pricing steps open on it rather than on whatever day the demo runs;
     * a day before it shows the rise as scheduled instead.
     */
    public static final String AS_OF = "2026-06-01";
    private volatile String path;

    /**
     * The corpus's absolute path: a {@code corpus} directory under the working directory when
     * one exists (a person's own takes the shipped one's place), else the checkout's
     * {@code src/main/resources/corpus}, else the classpath copy extracted into a fresh
     * temporary directory. Null when the classpath carries no corpus, or when the extraction
     * failed - this feeds a status surface, so the failure is logged in full and the page
     * says there is no folder.
     */
    public synchronized String path() {
        if (path == null) {
            path = locate();
        }
        return path;
    }

    /**
     * The corpus as the directory a run reads. A classpath that carries no corpus, or whose
     * extraction failed, has nothing to read, and a run on it is a deployment's defect rather
     * than a caller's: it throws instead of refusing.
     */
    public Path directory() {
        String located = path();
        if (located == null) {
            throw new IllegalStateException("The shipped corpus is not on this classpath, so there is no folder to read");
        }
        return Path.of(located);
    }

    private String locate() {
        Path workingDirectory = Path.of("corpus");
        if (Files.isDirectory(workingDirectory)) {
            return workingDirectory.toAbsolutePath().toString();
        }
        Path checkout = Path.of("src/main/resources/corpus");
        if (Files.isDirectory(checkout)) {
            return checkout.toAbsolutePath().toString();
        }
        try {
            return extract();
        }
        catch (IOException e) {
            log.warn("The shipped corpus could not be extracted; the page opens without a prefilled directory", e);
            return null;
        }
    }

    /**
     * The classpath corpus copied into a per-process temporary directory: the manifest names the
     * files, and each is read by its exact resource path, so the copy works the same from a build's
     * output, a jar, or a native image, where the directory cannot be listed.
     */
    private String extract() throws IOException {
        List<String> names = manifest();
        if (names.isEmpty()) {
            return null;
        }
        Path directory = Files.createTempDirectory("nucleo-demo-corpus-");
        int files = 0;
        for (String name : names) {
            String resource = CLASSPATH_ROOT + "/" + name;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("The corpus manifest names " + resource + " but the classpath does not carry it");
                }
                Path target = directory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            files++;
        }
        log.info("Shipped corpus: {} files extracted to {}", files, directory);
        return directory.toAbsolutePath().toString();
    }

    /** The manifest's lines, blank lines skipped; empty when the classpath carries no manifest. */
    private List<String> manifest() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            if (in == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String name = line.strip();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        }
    }
}
