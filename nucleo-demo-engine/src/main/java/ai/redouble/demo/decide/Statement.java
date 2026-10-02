/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * One statement of a document: a sentence or a bullet, with the document it came from and
 * its place in it. The decision agent's answer is a list of these, selected one by one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@TypeAlias("statement")
public class Statement extends AbstractArtifact {
    @LLMDescription("The statement's text")
    private String text;
    @LLMDescription("The name of the document the statement comes from")
    private String source;
    @LLMDescription("The statement's place in its document, from 1")
    private int ordinal;

    public Statement() {}

    public Statement(String text, String source, int ordinal) {
        this.text = text;
        this.source = source;
        this.ordinal = ordinal;
    }

    public String getText() {return text;}

    public void setText(String text) {this.text = text;}

    public String getSource() {return source;}

    public void setSource(String source) {this.source = source;}

    public int getOrdinal() {return ordinal;}

    public void setOrdinal(int ordinal) {this.ordinal = ordinal;}
}
