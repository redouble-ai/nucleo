/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import java.util.*;

/**
 * An artifact whose filterable fields are a dynamic, data-defined name->value set
 * rather than static Java fields, so a list-processing predicate can address them
 * by name (e.g. a predicate field {@code sender_type}), the same way a caller
 * refers to fields it asked an extractor to produce.
 *
 * <p>This is the bridge for record-shaped artifacts whose columns are decided at run
 * time and therefore cannot be reflected off the class. A consumer resolves a field
 * reference reflectively first ({@link ReflectiveFields#find}); a name with no static
 * field is resolved through {@link #fieldValue(String)} when the iterand is a
 * {@code KeyedRecord}. Name matching is lenient across case and separators (see
 * {@link ReflectiveFields#normalize}), so {@code sender_type}, {@code "Sender Type"}
 * and {@code senderType} all address the same field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-15)
 */
public interface KeyedRecord {
    /** Value of the named field, or null when the record has no such field or it is unset. */
    String fieldValue(String name);

    /** The addressable field names, in record order - the filterable surface shown to the translator. */
    List<String> fieldNames();
}
