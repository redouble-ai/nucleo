/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * The {@code pins} object of a deployment's {@code models.json}: which catalog entries serve
 * each grade, in the deployment's order of preference, which embeddings entry the corpus is
 * tied to, and which decision entry answers decision seats. Read by {@link DefaultModelPicker},
 * so a deployment states its model policy in the catalog file and writes no picker class.
 *
 * <p>A grade's value is an ordered list of catalog ids, the first being the grade's default:
 * a request is served by the first entry of its grade's list that can be called and accepts
 * every input the request declared it sends ({@link Input}), so a deployment whose default is
 * a text-only model names the seeing or document-reading model it prefers next, at the same
 * grade, and no request picks a model by what its history happens to hold. Entries of the
 * grade the list leaves out follow it, cheapest first, and a grade with nothing that
 * qualifies is served from the grade above. A grade the file leaves out is served the same way
 * from its entries alone. The strongest grade the deployment serves is never declared: the
 * picker derives it ({@link DefaultModelPicker#ceiling()}). A null embeddings or decision means
 * the file declares none.
 *
 * <pre>{@code
 * "pins": {
 *   "SMALL": ["<the grade's default>", "<its next choice, e.g. one that reads images>"],
 *   "MEDIUM": ["<catalog id>"],
 *   "embeddings": "<catalog id of the embeddings entry the corpus is tied to>",
 *   "decision": "<catalog id of the decision entry>"
 * }
 * }</pre>
 *
 * @param grades     per grade, its catalog ids in the deployment's order, the first the default
 * @param embeddings the embeddings entry, or null when the file declares none
 * @param decision   the decision entry, or null when the file declares none
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public record CatalogPins(Map<Grade, List<String>> grades, String embeddings, String decision) {
}
