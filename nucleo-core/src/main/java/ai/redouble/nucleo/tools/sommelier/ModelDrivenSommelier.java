/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.registry.*;

import java.io.*;
import java.util.*;

/**
 * The sommelier the runtime starts with: a model reads every tool of the catalog in full
 * and names the ones that fit. Nothing is filtered before a model has read it, so no index
 * or ranking stands between the need and a tool.
 *
 * <p>The model is the deployment's {@code SMALL} grade. A deployment that has measured a
 * better choice, or whose {@code SMALL} is served by a model too weak for the work, names
 * the entry in a {@link ModelRole}:
 *
 * <pre>
 * ModelDrivenSommelier sommelier = new ModelDrivenSommelier();
 * sommelier.setRole(new ModelRole("tool-lookup", "&lt;catalog id&gt;", Grade.SMALL));
 * ToolHub.getInstance().setSommelier(sommelier);
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class ModelDrivenSommelier implements Sommelier {
    private ModelRole role;

    public ModelRole getRole() {
        return role;
    }

    /** The role whose catalog entry serves every lookup, its grade standing in when the entry cannot. */
    public void setRole(ModelRole role) {
        this.role = role;
    }

    @Override
    public Job<SommelierAnswer> pair(Identifiable parent, String need, Collection<ToolProvider> catalog) throws LLMReadableCheckedException {
        Map<String, String> pages = new LinkedHashMap<>();
        for (ToolProvider provider : catalog) {
            try {
                pages.put(provider.name(), ToolPage.of(provider));
            }
            catch (IOException e) {
                throw new SystemException("sommelier", "the schema of tool " + provider.name() + " is not readable JSON", e);
            }
        }
        SommelierQuestion question = new SommelierQuestion();
        question.setNeed(need);
        question.setTools(pages);
        SommelierLookup lookup = new SommelierLookup(parent);
        lookup.setInput(question);
        if (role != null) {
            lookup.setRole(role);
        }
        return lookup;
    }
}
