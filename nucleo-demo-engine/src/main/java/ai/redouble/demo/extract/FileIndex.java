/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import java.util.*;
import java.util.concurrent.*;

/**
 * The rudimentary index: every extracted text with its vector, in memory, searched by
 * cosine. What a deployment replaces with pgvector or any vector store; the shape a search
 * needs is all that is here. Vectors are comparable only with vectors from the model that
 * produced them, so the index records its model and refuses a query embedded by another.
 * The index is also what the extraction leaves behind for the next workflow: every
 * document's whole text, by path, which the pricing demo reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class FileIndex {
    /** How much of a text is embedded: the head, which is what a search question is usually about. The whole text is kept. */
    public static final int INDEXED_CHARS = 6_000;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile String modelId;

    public record Entry(String path, String text, float[] vector, String modelId) {}

    public record Hit(String path, double score, String excerpt) {}

    public void put(String path, String text, Embedding embedding) {
        if (modelId != null && !modelId.equals(embedding.getModelId())) {
            throw new IllegalStateException("The index holds vectors from " + modelId + " and cannot take one from "
                    + embedding.getModelId() + "; vectors compare only with their own model's");
        }
        modelId = embedding.getModelId();
        entries.put(path, new Entry(path, text, embedding.getVector(), embedding.getModelId()));
    }

    public int size() {
        return entries.size();
    }

    /** Every indexed document's whole text by path, in path order: what a workflow over the extraction reads. */
    public Map<String, String> texts() {
        Map<String, String> texts = new TreeMap<>();
        for (Entry entry : entries.values()) {
            texts.put(entry.path(), entry.text());
        }
        return texts;
    }

    public String getModelId() {
        return modelId;
    }

    public void clear() {
        entries.clear();
        modelId = null;
    }

    /** The closest entries to a query vector, best first. */
    public List<Hit> search(Embedding query, int k) {
        if (modelId != null && !modelId.equals(query.getModelId())) {
            throw new IllegalStateException("The index holds vectors from " + modelId + " and the query was embedded by "
                    + query.getModelId());
        }
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : entries.values()) {
            hits.add(new Hit(entry.path(), cosine(query.getVector(), entry.vector()), excerpt(entry.text())));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors of " + a.length + " and " + b.length + " dimensions do not compare");
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static String excerpt(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
