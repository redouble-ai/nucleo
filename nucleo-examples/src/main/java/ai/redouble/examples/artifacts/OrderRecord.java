/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * When a model passes data along, it retypes it, and now and then a digit changes or a date
 * is rounded with nothing in the answer showing it. An artifact removes that chance: a
 * tool's result the runtime keeps in a registry under a short reference. The model reads the
 * record in full and decides which records matter, but it answers with references, and the
 * caller gets these objects back from the registry - exactly what the tool built, however
 * many agents stood in between.
 *
 * <p>An artifact is a plain data class extending {@link AbstractArtifact}.
 * {@code @TypeAlias("order")} names its kind, the first part of every reference to one:
 * {@code «artifact:order~...»}. The tool that returns these does nothing else about
 * artifacts; the runtime registers each record as the result reaches the model.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region artifact
@TypeAlias("order")
public class OrderRecord extends AbstractArtifact {
    @LLMDescription("The order number")
    private String orderNumber;
    @LLMDescription("PROCESSING, SHIPPED, DELIVERED or DELAYED")
    private String status;
    @LLMDescription("The date the order was promised for, as YYYY-MM-DD")
    private String promisedFor;
    @LLMDescription("What the warehouse or the carrier says about it")
    private String note;
    // endregion

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPromisedFor() {
        return promisedFor;
    }

    public void setPromisedFor(String promisedFor) {
        this.promisedFor = promisedFor;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    @Override
    public String toString() {
        return orderNumber + ": " + status + ", promised for " + promisedFor + ", " + note;
    }
}
