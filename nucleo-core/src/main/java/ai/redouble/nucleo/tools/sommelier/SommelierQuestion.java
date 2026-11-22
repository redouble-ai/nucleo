/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import java.util.*;

/**
 * What {@link ModelDrivenSommelier} puts to a model: a need, and tools to judge it against. Each
 * tool is its name mapped to its page, the text {@link ToolPage} writes for it.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class SommelierQuestion {
    private String need;
    private Map<String, String> tools;

    public String getNeed() {
        return need;
    }

    public void setNeed(String need) {
        this.need = need;
    }

    public Map<String, String> getTools() {
        return tools;
    }

    public void setTools(Map<String, String> tools) {
        this.tools = tools;
    }
}
