/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * What the canonicalizer answers: the names grouped by the product they name, each group
 * under one canonical name.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class ProductGroups {
    /** One product and every name the documents used for it. */
    public static class Group {
        @LLMRequired
        @LLMDescription("The product's proper name, the fullest form the documents used, e.g. 'Kestrel 1 gravel' over 'Kestrel 1'")
        private String canonical;
        @LLMRequired
        @LLMDescription("Every input name that means this product, verbatim, the canonical one included")
        private List<String> aliases;

        public String getCanonical() {return canonical;}

        public void setCanonical(String canonical) {this.canonical = canonical;}

        public List<String> getAliases() {return aliases;}

        public void setAliases(List<String> aliases) {this.aliases = aliases;}
    }

    @LLMRequired
    @LLMDescription("Every input name in exactly one group; a name that is its own product is a group of one")
    private List<Group> groups;

    public List<Group> getGroups() {return groups;}

    public void setGroups(List<Group> groups) {this.groups = groups;}
}
