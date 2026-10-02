/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Puts the generated site into a checkout of the documentation repository as one version
 * of the published documentation. The repository holds every published version, already
 * generated, one directory each under {@code site/nucleo}; what it holds is what the
 * documentation host serves. Publishing a version that is not there adds it; publishing
 * one that is there replaces its directory whole, so no page of the earlier publication
 * survives. Then what depends on the set of versions is written again from the
 * directories themselves: {@code site/nucleo/versions.json}, the versions newest first,
 * which every page's version badge reads; {@code site/_redirects}, which sends the
 * address without a version to the newest one; and {@code site/robots.txt}, which has
 * search engines index the newest version alone. The page the host shows for an address
 * that leads nowhere, {@code site/404.html}, is written with them, and the repository's
 * own {@code LICENSE} and {@code NOTICE} are taken from the newest version.
 *
 * Nothing here commits or pushes. The checkout is left changed for a person to review,
 * commit and push, and the push is the publication.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-01)
 */
public class PublishDocs {
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-([A-Za-z0-9.]+))?");

    /** Arguments: the directory of the generated site, the checkout of the documentation repository, the version. */
    public static void main(String[] args) throws IOException {
        publish(Path.of(args[0]), Path.of(args[1]), args[2]);
    }

    /** Publishes the site under {@code site} as {@code version} into the checkout at {@code repository}. */
    public static void publish(Path site, Path repository, String version) throws IOException {
        site = site.toAbsolutePath().normalize();
        repository = repository.toAbsolutePath().normalize();
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("'" + version + "' cannot be published: a version is numbers separated by dots, then optionally a dash and a qualifier, like 0.1.1 or 1.0.0-RC1");
        }
        if (!Files.isRegularFile(site.resolve("index.html"))) {
            throw new IllegalStateException("no generated site at " + site + ": it has no index.html");
        }
        Path product = repository.resolve("site/nucleo");
        if (!Files.isRegularFile(product.resolve("versions.json"))) {
            throw new IllegalStateException(repository + " is not a checkout of the documentation repository: it has no site/nucleo/versions.json");
        }
        Path target = product.resolve(version);
        boolean replaced = Files.isDirectory(target);
        if (replaced) {
            delete(target);
        }
        copy(site, target);
        List<String> versions = versions(product);
        String newest = versions.get(0);
        Path served = repository.resolve("site");
        Files.writeString(product.resolve("versions.json"), versions.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", ", "[", "]\n")));
        Files.writeString(served.resolve("_redirects"), redirects(newest));
        Files.writeString(served.resolve("robots.txt"), robots(versions));
        GenerateDocs.copyResource("/404.html", served.resolve("404.html"));
        // The repository's own license and notice are those of the newest version it holds
        if (version.equals(newest)) {
            Files.copy(target.resolve("LICENSE.txt"), repository.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(target.resolve("NOTICE.txt"), repository.resolve("NOTICE"), StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println((replaced ? "Replaced " : "Added ") + version + " in " + target);
        System.out.println("Published versions, newest first: " + String.join(", ", versions));
        System.out.println("Nothing is committed: review the checkout, then commit and push it to publish.");
    }

    /**
     * The published versions, newest first: the directories beside {@code versions.json}.
     * A directory there that is not named as a version is refused, since the list would
     * otherwise offer it to readers as one.
     */
    static List<String> versions(Path product) throws IOException {
        List<String> versions = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(product, Files::isDirectory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!VERSION.matcher(name).matches()) {
                    throw new IllegalStateException(entry + " is not a published version: every directory under " + product + " is named as a version, like 0.1.1");
                }
                versions.add(name);
            }
        }
        versions.sort(PublishDocs::compare);
        Collections.reverse(versions);
        return versions;
    }

    /**
     * Orders two versions, older first: number by number, a missing number counting as
     * zero; with the same numbers, a version with a qualifier comes before the one without
     * (1.0.0-RC1 before 1.0.0), and two qualifiers compare as text.
     */
    static int compare(String a, String b) {
        Matcher left = VERSION.matcher(a);
        Matcher right = VERSION.matcher(b);
        if (!left.matches() || !right.matches()) {
            throw new IllegalArgumentException("not versions: '" + a + "', '" + b + "'");
        }
        String[] leftNumbers = left.group(1).split("\\.");
        String[] rightNumbers = right.group(1).split("\\.");
        for (int i = 0; i < Math.max(leftNumbers.length, rightNumbers.length); i++) {
            long leftNumber = i < leftNumbers.length ? Long.parseLong(leftNumbers[i]) : 0;
            long rightNumber = i < rightNumbers.length ? Long.parseLong(rightNumbers[i]) : 0;
            if (leftNumber != rightNumber) {
                return Long.compare(leftNumber, rightNumber);
            }
        }
        String leftQualifier = left.group(2);
        String rightQualifier = right.group(2);
        if (leftQualifier == null || rightQualifier == null) {
            return Boolean.compare(leftQualifier == null, rightQualifier == null);
        }
        return leftQualifier.compareTo(rightQualifier);
    }

    /**
     * The host's routing file: the site's root and the product's root lead to
     * {@code /nucleo/current/}, and every address under it is served, unchanged in the
     * reader's address bar, by the newest version. The two files a coding agent looks for
     * at the root of a site, {@code llms.txt} and {@code llms-full.txt}, lead to the newest
     * version's; as redirects, so the links inside them resolve against that version.
     */
    static String redirects(String newest) {
        return """
                # Written by the publish step of nucleo from the versions under nucleo/; an edit here is overwritten by the next publication.
                /                   /nucleo/current/                302
                /nucleo             /nucleo/current/                302
                /llms.txt           /nucleo/current/llms.txt        302
                /llms-full.txt      /nucleo/current/llms-full.txt   302
                /nucleo/current/*   /nucleo/%s/:splat   200
                """.formatted(newest);
    }

    /**
     * What a search engine may index: the documentation under {@code /nucleo/current/},
     * which is every page of the newest version. The same pages under their version
     * numbers are kept out, so that a page is indexed once and an older version never
     * competes with the current one.
     */
    static String robots(List<String> versions) {
        StringBuilder out = new StringBuilder("""
                # Written by the publish step of nucleo from the versions under nucleo/; an edit here is overwritten by the next publication.
                # The documentation is indexed under /nucleo/current/, always the newest version; the numbered copies stay out.
                User-agent: *
                """);
        for (String version : versions) {
            out.append("Disallow: /nucleo/").append(version).append("/\n");
        }
        return out.toString();
    }

    /** Copies the site's tree; the site's own {@code .gitignore}, which hides generated files from the source repository, stays behind. */
    private static void copy(Path site, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(site)) {
            for (Path source : (Iterable<Path>) files::iterator) {
                Path relative = site.relativize(source);
                if (relative.toString().equals(".gitignore")) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
    }

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        }
    }
}
