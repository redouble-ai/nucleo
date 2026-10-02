/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * A folder on this machine, the decision agent's input: the one artifact a run starts from.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@TypeAlias("folder")
public class Folder extends AbstractArtifact {
    @LLMDescription("The folder's absolute path")
    private String path;

    public Folder() {}

    public Folder(String path) {
        this.path = path;
    }

    public String getPath() {return path;}

    public void setPath(String path) {this.path = path;}
}
