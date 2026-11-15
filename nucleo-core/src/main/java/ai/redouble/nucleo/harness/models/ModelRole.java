/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * The application's choice of model for one kind of work, wherever that work is done: a
 * constant declared once, naming the work, the catalog entry it prefers and the grade that
 * stands in when that entry cannot serve. Every call doing the work declares the role in
 * place of a grade - {@code JobRequirements.requireModel(ModelRole, ...)},
 * {@code ThinkerDeclaration(ModelRole, ...)}, {@code ConversationContext.setRole} - and a
 * class that is the only one doing the work declares the role in that class. Nothing is
 * registered: the role is the constant itself.
 *
 * <pre>{@code
 * public static final ModelRole TABLE_ROW = new ModelRole("table-row", "<catalog id of the measured entry>", Grade.MEDIUM);
 * }</pre>
 *
 * <p>A role is for work measured on one model: a sample of table rows parsed as well by a
 * small open-weight model as by the grade's default, at a seventh of the price. The
 * deployment's choice per grade stays in the catalog's {@code pins}; a role is the one other
 * place a model id is named in application code. The picker ({@link AbstractModelPicker})
 * serves a role from its entry when the entry can serve the request - in this deployment's
 * catalog, open, its provider on the classpath with a credential, permitted by the
 * compliance envelope, accepting the declared inputs, not turned off by the availability
 * probe - with overload failover as for any pick; otherwise it warns once per role and
 * reason and serves the request from the role's grade. An entry absent from the catalog is
 * passed over the same way, since an application ships to deployments whose catalogs it did
 * not write. The entry may be of any grade: the role chose it by measurement.
 *
 * @param name      what the log and the call record show for the work
 * @param catalogId the catalog id of the entry the role prefers
 * @param grade     the rung the picker starts from when the entry cannot serve, or {@link Grade#CEILING}
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-09)
 */
public record ModelRole(String name, String catalogId, Grade grade) {
    public ModelRole {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A model role names the work it is for");
        }
        if (catalogId == null || catalogId.isBlank()) {
            throw new IllegalArgumentException("Model role " + name + " names the catalog entry it prefers");
        }
        if (grade == null) {
            throw new IllegalArgumentException("Model role " + name + " names the grade that stands in when " + catalogId + " cannot serve");
        }
    }
}
