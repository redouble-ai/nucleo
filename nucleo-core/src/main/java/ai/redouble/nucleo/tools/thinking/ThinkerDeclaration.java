/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.models.*;


/**
 * What a thinker declares about itself, in one value the base constructors require: the
 * seat's vocabulary - "MEDIUM model, COMPACT answer" - stated where the compiler can see it.
 * A thinker that names no grade and no answer size does not compile; a chat that wants the
 * strongest model the deployment serves says so with {@link Grade#CEILING}, never by leaving
 * the grade out.
 *
 * <p>The required words are constructor arguments. The optional knobs - the comfort context
 * window and the compaction trigger, see {@code ContextWindowManager} - are void setters, so
 * a seat that tunes one changes nothing else. A future required word joins the constructor,
 * which is a change at every seat because that is what required means; a future optional
 * knob joins the setters and no seat moves.
 *
 * <p>The thinker copies the values into its own fields at construction; the setters on the
 * thinker remain for refinement after it (a parent raising a child's grade, a run re-sized
 * with {@code setOutputBudget}).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public final class ThinkerDeclaration {
    private final Grade grade;
    private final OutputDeclaration output;
    private Integer comfortContextTokens;
    private Double compactionTrigger;

    /** The ordinary seat: a capability floor and an answer rung sized to the largest turn. */
    public ThinkerDeclaration(Grade grade, OutputSize output) {
        this(grade, OutputDeclaration.of(output));
    }

    /** The documented exception: a seat whose answer fits no rung declares its tokens. */
    public ThinkerDeclaration(Grade grade, int outputTokens) {
        this(grade, OutputDeclaration.of(outputTokens));
    }

    private ThinkerDeclaration(Grade grade, OutputDeclaration output) {
        if (grade == null) {
            throw new IllegalArgumentException("A thinker declares its grade; there is no default, silent or otherwise");
        }
        // a null answer size cannot reach here: OutputDeclaration.of refuses it first
        this.grade = grade;
        this.output = output;
    }

    public Grade getGrade() {
        return grade;
    }

    public OutputDeclaration getOutput() {
        return output;
    }

    public Integer getComfortContextTokens() {
        return comfortContextTokens;
    }

    /** Overrides the comfort context window for this seat; see {@code ContextWindowManager}. */
    public void setComfortContextTokens(int comfortContextTokens) {
        this.comfortContextTokens = comfortContextTokens;
    }

    public Double getCompactionTrigger() {
        return compactionTrigger;
    }

    /** Overrides the compaction trigger for this seat; see {@code ContextWindowManager}. */
    public void setCompactionTrigger(double compactionTrigger) {
        this.compactionTrigger = compactionTrigger;
    }
}
