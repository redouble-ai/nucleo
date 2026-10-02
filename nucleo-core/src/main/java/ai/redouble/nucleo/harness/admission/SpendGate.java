/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import java.util.*;

/**
 * The money wall of admission. The dispatcher consults every registered gate after an
 * attempt's bindings are resolved and priced and before any resource is acquired, with
 * the reservation each binding would commit; a gate that refuses stops the job there, and
 * an orchestrator that sees the refusal stops submitting. A cap is therefore a cap on new
 * work: nothing mid-flight is killed, and the money a running call spends is the money its
 * reservation already committed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public interface SpendGate {
    /**
     * Admits or refuses the attempt whose bindings these are. {@code context} names the
     * workflow the spend belongs to.
     *
     * @throws SpendCapExceededException to refuse, naming the cap, the spend so far and the reservation
     */
    void admit(JobContext<?> context, List<ModelBinding> priced) throws SpendCapExceededException;
}
