/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import java.lang.annotation.*;

/**
 * Declares a hierarchical type alias for an artifact class.
 *
 * <p>The alias is embedded in the artifact reference string ({@code «artifact:alias~uuid»})
 * and used by {@link ai.redouble.nucleo.harness.artifacts.ArtifactMapDeserializer} to resolve the
 * concrete class during deserialization. No {@code @type} field in JSON is needed.
 *
 * <p>Alias naming convention:
 * <ul>
 *   <li>Colons = IS-A hierarchy: {@code "link:cite:pubmed"} means PubMedArticle IS-A link:cite IS-A link</li>
 *   <li>No colons = root type: {@code "compound"}, {@code "person"}</li>
 *   <li>Hyphens for multi-word names: {@code "clinical-trial"}, {@code "drug-interaction"}</li>
 *   <li>Aliases are unique globally</li>
 * </ul>
 *
 * <p>Required on every concrete class that implements
 * {@link ai.redouble.nucleo.harness.artifacts.Artifact}; {@link TypeAliasRegistry#init} refuses
 * a package where one lacks it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-19)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TypeAlias {
    /**
     * The hierarchical type alias, e.g. {@code "link:cite:pubmed"} or {@code "compound"}.
     */
    String value();
}
