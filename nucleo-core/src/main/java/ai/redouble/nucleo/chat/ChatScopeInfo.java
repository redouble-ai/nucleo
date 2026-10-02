/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat;

/**
* One chat scope as the chat frontend sees it: the entity a conversation is
* bound to, projected into a shape that is the same in every application. A
* project and a support ticket differ in almost every field they store,
* so the frontend is given this projection instead of the entity - that is what
* lets a single compiled chat bundle serve every module, with only a deployed
* config file naming which controller produces the projection.
* <p>
* Only {@link #id} and {@link #title} are guaranteed. Everything else is
* whatever the source application actually has; a null field means the
* application has no such value, and the frontend renders accordingly.
*
 * @author Andrey Santrosyan
* @since 0.1 (2026-08-17)
*/
public class ChatScopeInfo {

    private Long id;
    private String title;
    private String subtitle;
    private String description;
    private String status;
    private Long lastUpdated;
    private String accessMode;

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * One line of context under the title in the picker - the caller decides what
     * identifies a scope at a glance (a ticket: company and status; a project: owner).
     */
    public String getSubtitle() {
        return this.subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    /**
     * Prose shown on the empty-chat welcome screen, describing what this scope
     * contains so a user knows what they can ask about.
     */
    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Epoch milliseconds of the last change to the scope, used to order the picker.
     */
    public Long getLastUpdated() {
        return this.lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    /**
     * What this user may do with this scope. An application with per-scope sharing maps its mode here;
     * applications without per-scope sharing report their fixed level.
     */
    public String getAccessMode() {
        return this.accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }
}
