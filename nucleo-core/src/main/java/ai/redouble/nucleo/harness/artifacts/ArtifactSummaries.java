/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import java.util.*;
import java.util.concurrent.*;

/**
 * The summaries kept for one artifact: what a model is shown in place of each long field.
 *
 * <p>A summary is made once and kept, and a kept summary is what every later write of the
 * artifact shows. The key is the field's name as it is serialized; for a leaf of a tree
 * marked {@code preSummarized} it is that name followed by the leaf's JSON pointer. Only
 * summaries are held here: the full value stays in the field, which does not change once
 * the artifact is registered.
 *
 * <p>An artifact is shared between jobs, so two threads may need the same summary at once.
 * {@link #make} runs one making for a key; a thread that arrives while it runs waits for
 * it and takes what it kept.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
public class ArtifactSummaries {
    private final Map<String, String> kept = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> making = new ConcurrentHashMap<>();

    /**
     * The summary kept under a key.
     *
     * @param key the field's serialized name, with a leaf's JSON pointer after it
     * @return the kept summary, or null when none is kept
     */
    public String get(String key) {
        return kept.get(key);
    }

    /**
     * Keeps a summary under a key, replacing nothing: a summary already kept stays.
     *
     * @param key the field's serialized name, with a leaf's JSON pointer after it
     * @param summary the text a model is shown in place of the value
     * @return the summary that is kept under the key after the call
     */
    public String keep(String key, String summary) {
        String earlier = kept.putIfAbsent(key, summary);
        return earlier != null ? earlier : summary;
    }

    /**
     * The summary under a key, made by the given maker when none is kept and no other
     * thread is making it. The maker's answer is kept.
     *
     * <p>A thread that finds another making the same summary waits for it. When that
     * making fails, or its thread is cancelled, the waiter is given null: it neither
     * inherits the failure nor makes the summary itself, and nothing is kept. A waiter
     * that is itself interrupted stops waiting.
     *
     * @param key the field's serialized name, with a leaf's JSON pointer after it
     * @param maker makes the summary; whatever it throws reaches the thread that ran it
     * @return the kept summary, or null to a waiter whose making failed
     * @throws InterruptedException when the calling thread is interrupted while it waits
     * @throws Exception whatever the maker throws, to the thread that ran it
     */
    public String make(String key, Callable<String> maker) throws Exception {
        String summary = kept.get(key);
        if (summary != null) {
            return summary;
        }
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> theirs = making.putIfAbsent(key, mine);
        if (theirs != null) {
            try {
                return theirs.get();
            }
            catch (ExecutionException | CancellationException e) {
                return null;
            }
        }
        try {
            // A making that finished between the first look and the claim has kept its answer
            summary = kept.get(key);
            if (summary == null) {
                summary = keep(key, maker.call());
            }
            mine.complete(summary);
            return summary;
        }
        catch (Throwable e) {
            mine.completeExceptionally(e);
            throw e;
        }
        finally {
            making.remove(key, mine);
        }
    }

    /**
     * Every kept summary by its key, for a store that saves them with the artifact.
     *
     * @return a copy; empty when nothing is kept
     */
    public Map<String, String> all() {
        return new TreeMap<>(kept);
    }
}
