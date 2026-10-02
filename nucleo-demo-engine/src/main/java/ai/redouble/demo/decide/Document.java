/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * A file read as text: its name and the whole text. What a splitting tool takes; a model sees
 * its name and first words, never the whole text, since judging a document's contents is a
 * tool's job.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@TypeAlias("document")
public class Document extends AbstractArtifact {
    @LLMDescription("The file's name")
    private String name;
    @LLMDescription("The whole text of the file")
    private String text;
    @LLMDescription("The file's absolute path")
    private String path;

    public String getName() {return name;}

    public void setName(String name) {this.name = name;}

    public String getText() {return text;}

    public void setText(String text) {this.text = text;}

    public String getPath() {return path;}

    public void setPath(String path) {this.path = path;}
}
