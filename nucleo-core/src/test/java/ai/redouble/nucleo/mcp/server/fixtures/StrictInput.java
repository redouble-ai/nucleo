/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.schema.*;
import java.time.*;
import java.util.*;

/**
 * One input carrying every JSON type the gate judges, so the boundary's acceptance rules
 * can be exercised against a real published schema rather than a hand-written one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class StrictInput {
    @LLMRequired
    @LLMDescription("Free text")
    private String text;

    @LLMDescription("How many")
    private Integer count;

    @LLMDescription("Whether to shout")
    private Boolean loud;

    @LLMDescription("Labels")
    private List<String> tags;

    @LLMDescription("A nested shape")
    private StrictNested nested;

    /**
     * Publishes as a string carrying {@code format: "date"}, and the gate refuses an
     * unparsable one from that published format. Without the format it would reach the date
     * parser below, whose complaint quotes the text it could not read; this field is where
     * that seam is pinned.
     */
    @LLMDescription("When it happened, as YYYY-MM-DD")
    private LocalDate when;

    @LLMDescription("How hard to look")
    private StrictMode mode;

    @LLMDescription("The moment, as an ISO-8601 date-time")
    private LocalDateTime at;

    @LLMDescription("A fraction")
    private Double ratio;

    public StrictMode getMode() { return mode; }
    public void setMode(StrictMode mode) { this.mode = mode; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
    public Double getRatio() { return ratio; }
    public void setRatio(Double ratio) { this.ratio = ratio; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    public Boolean getLoud() { return loud; }
    public void setLoud(Boolean loud) { this.loud = loud; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public StrictNested getNested() { return nested; }
    public void setNested(StrictNested nested) { this.nested = nested; }
    public LocalDate getWhen() { return when; }
    public void setWhen(LocalDate when) { this.when = when; }
}
