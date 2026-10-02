/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;
import java.util.regex.*;

/**
 * A catalog identity read as a family and a version, so the discovery can tell that
 * {@code opus-5} is a newer {@code opus} than {@code opus-4.8} and that {@code nova-2-lite}
 * succeeds {@code nova-lite}: a listed model with an ancestor in the catalog inherits the
 * ancestor's shape and the ancestor's disposition.
 *
 * <p>An identity splits on hyphens. A token that is a number, dotted or not, is a version
 * token; every other token, a size like {@code 120b} included, is the family. The version
 * tokens in order, joined with dots, are the version; no version token means the first of
 * the family and sorts before any versioned one. So {@code gpt-5.6} is {@code gpt} at 5.6,
 * {@code gpt-5-mini} is {@code gpt-mini} at 5, {@code fable-5.1} is {@code fable} at 5.1.
 *
 * <p>The class also owns the reading of a wire id that every vendor decorates the same way:
 * Bedrock's geography or global prefix, a vendor prefix, a version or date suffix. The
 * default identity of a listed model is the wire id with those stripped; a provider whose
 * vendor spells names further from the catalog's overrides {@code identityOf}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public record ModelLineage(String family, String version) {
    /**
     * The geography prefixes of Bedrock's cross-region inference profiles. A region lists only
     * its own geography's profiles and the {@code global.} ones, and the same model appears under
     * each geography with the same bare id after the prefix. Explicit, because vendor prefixes
     * ({@code meta.}, {@code qwen.}, {@code xai.}) look the same and are not geographies; a
     * geography AWS adds later shows up as unknown, which is the safe failure.
     */
    public static final Set<String> GEOGRAPHIES = Set.of("us", "eu", "apac", "ca", "jp", "au", "sa", "us-gov");
    public static final String GLOBAL = "global";
    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+(\\.\\d+)*");
    private static final Pattern VENDOR_PREFIX = Pattern.compile("^[a-z]+\\.(?=[a-z])");
    /** {@code -v1:0}, {@code -1:0}, {@code :0}, and Bedrock's provisioned-context variants {@code -v1:0:24k}. */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("(-v\\d+(:\\d+k?)*|-\\d+(:\\d+k?)+|(:\\d+k?)+)$");
    private static final Pattern DATE_SUFFIX = Pattern.compile("-\\d{8}$");
    private static final Pattern INSTRUCT_SUFFIX = Pattern.compile("-instruct$");

    public static ModelLineage of(String identity) {
        List<String> family = new ArrayList<>();
        List<String> version = new ArrayList<>();
        for (String token : identity.split("-")) {
            if (VERSION_TOKEN.matcher(token).matches()) {
                version.add(token);
            }
            else {
                family.add(token);
            }
        }
        return new ModelLineage(String.join("-", family), String.join(".", version));
    }

    /** The geography or {@code global} prefix of a wire id, or null for a bare on-demand id. */
    public static String prefix(String wireId) {
        int dot = wireId.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String head = wireId.substring(0, dot);
        return GEOGRAPHIES.contains(head) || GLOBAL.equals(head) ? head : null;
    }

    /** The wire id without its geography or global prefix: what names the model itself. */
    public static String bare(String wireId) {
        return prefix(wireId) != null ? wireId.substring(wireId.indexOf('.') + 1) : wireId;
    }

    /**
     * The default identity of a listed model: the bare wire id without the vendor prefix
     * ({@code amazon.}, {@code openai.}), the version suffix ({@code -v1:0}, {@code -1:0}), the
     * date suffix ({@code -20251101}) and the {@code -instruct} suffix.
     */
    public static String identityOf(String wireId) {
        String id = VENDOR_PREFIX.matcher(bare(wireId)).replaceFirst("");
        id = VERSION_SUFFIX.matcher(id).replaceFirst("");
        id = DATE_SUFFIX.matcher(id).replaceFirst("");
        return INSTRUCT_SUFFIX.matcher(id).replaceFirst("");
    }

    public boolean sameFamily(ModelLineage other) {
        return family.equals(other.family);
    }

    /** Version order within a family: numeric per dotted component, a missing version first. */
    public int compareVersion(ModelLineage other) {
        String[] mine = version.isEmpty() ? new String[0] : version.split("\\.");
        String[] theirs = other.version.isEmpty() ? new String[0] : other.version.split("\\.");
        for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
            long a = i < mine.length ? Long.parseLong(mine[i]) : -1;
            long b = i < theirs.length ? Long.parseLong(theirs[i]) : -1;
            if (a != b) {
                return Long.compare(a, b);
            }
        }
        return 0;
    }

    public boolean newerThan(ModelLineage other) {
        return sameFamily(other) && compareVersion(other) > 0;
    }
}
