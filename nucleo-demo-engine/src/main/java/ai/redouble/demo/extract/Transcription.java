/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.schema.*;

/**
 * What a vision-capable model answers when shown a scan or an image: the text it reads,
 * and whether the pages carried any.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class Transcription {
    @LLMRequired
    @LLMDescription("One plain string holding every word of text visible in the image(s), in reading order, page after page, pages separated by a blank line. Tables as rows of cells separated by ' | '. Nothing paraphrased, nothing added. A single string: never an object per page or a list of lines.")
    private String text;
    @LLMDescription("True when the image(s) carry no readable text at all: a photograph, a drawing, a blank page.")
    private boolean noText;

    public String getText() {return text;}

    public void setText(String text) {this.text = text;}

    public boolean isNoText() {return noText;}

    public void setNoText(boolean noText) {this.noText = noText;}
}
