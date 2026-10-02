/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat;

/**
* Naming a stored conversation.
* <p>
* The title is rendered as plain text in a list, so it has to be plain text.
* Models asked for a short title routinely answer with markdown emphasis or
* wrap the phrase in quotes - which then shows up literally in the sidebar as
* {@code **Heathrow Modicon Switch Selective Connectivity Loss**}. Asking for
* plain text handles most of it; {@link #clean} handles the rest, because a
* prompt is a request and not a guarantee.
*
 * @author Andrey Santrosyan
* @since 0.1 (2026-08-17)
*/
public final class ConversationTitle {

    /**
     * What to ask a model for. Shared so every store names conversations alike.
     */
    public static final String PROMPT =
        "Generate a short, descriptive title (max 8 words) for this conversation. "
        + "Answer with the title only, as plain text: no markdown, no surrounding quotes, no trailing punctuation.\n\n";

    private ConversationTitle() {
    }

    /**
     * The model's answer as a title fit to display: emphasis markers and wrapping
     * quotes removed, whitespace collapsed. Returns null when nothing usable is
     * left, so a caller stores no title rather than an empty one.
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String title = raw.trim();
        // A model sometimes answers over several lines; the title is the first
        title = title.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
        title = title.replaceAll("^[*_`]+", "").replaceAll("[*_`]+$", "");
        title = title.replaceAll("^[\"']+", "").replaceAll("[\"']+$", "");
        title = title.replaceAll("\\s+", " ").trim();
        return title.isEmpty() ? null : title;
    }
}
