/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * The test suite's deployment picker: a flat pin per grade matching the specs the suite
 * has always exercised, so dispatcher-driven tests resolve deterministically.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public class TestModelPicker implements ModelPicker {
    @Override
    public ModelSpec embeddingsSpec() {
        return Models.spec("cohere-embed-v4-bedrock");
    }

    @Override
    public ModelSpec decisionSpec() {
        return Models.spec("fake-decider");
    }

    @Override
    public Grade ceiling() {
        return Grade.MEGA;
    }

    @Override
    public ModelSpec provide(Seat seat, Situation situation) {
        return switch (seat.grade()) {
            case MICRO -> Models.spec("nova-micro");
            case SMALL -> Models.spec("claude-haiku-4-5-mantle");
            case MEDIUM -> Models.spec("claude-sonnet-5-mantle");
            case LARGE -> Models.spec("gpt-5");
            case XL -> Models.spec("claude-opus-5-mantle");
            case MEGA -> Models.spec("claude-fable-5-mantle");
            case CEILING -> throw new IllegalArgumentException("Grade.CEILING reached the picker unresolved");
        };
    }
}
