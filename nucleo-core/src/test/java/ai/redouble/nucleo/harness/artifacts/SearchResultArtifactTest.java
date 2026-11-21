/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers what a search result is to a model and to storage: shown as the total, the window's
 * limit and offset and the digest of its rows, with every row held under its own reference;
 * written in full for storage and read back with typed rows.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class SearchResultArtifactTest {

    @TypeAlias("test:order")
    public static class Order extends AbstractArtifact {
        private String number;

        public String getNumber() {
            return number;
        }

        public void setNumber(String number) {
            this.number = number;
        }
    }

    @BeforeAll
    static void aliases() {
        TypeAliasRegistry.register(SearchResultArtifact.class);
        TypeAliasRegistry.register(ListArtifact.class);
        TypeAliasRegistry.register(Order.class);
    }

    private static SearchResultArtifact<Order> resultOf(long total, int held) {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < held; i++) {
            Order order = new Order();
            order.setNumber("order-" + i);
            orders.add(order);
        }
        ListArtifact<Order> rows = new ListArtifact<>();
        rows.setIterands(orders);
        rows.setIterandTypeAlias("test:order");
        SearchResultArtifact<Order> result = new SearchResultArtifact<>();
        result.setTotalMatching(total);
        result.setOffset(0L);
        result.setLimit(50L);
        result.setRows(rows);
        return result;
    }

    @Test
    void aModelIsShownTheTotalTheWindowAndTheDigestOfTheRows() throws IOException {
        SearchResultArtifact<Order> result = resultOf(4012L, 50);
        new ArtifactRegistry().register(result);
        JsonNode shown = new ObjectMapper().readTree(NucleoJsonSerializer.writeSummarized(result));
        assertEquals(4012L, shown.get("total_matching").asLong(), "the total of matches is shown");
        assertEquals(0L, shown.get("offset").asLong(), "the window's offset is shown");
        assertEquals(50L, shown.get("limit").asLong(), "the window's limit is shown");
        JsonNode rows = shown.get("rows");
        assertEquals(50, rows.get("count").asInt(), "the rows are shown as a digest that counts the rows held");
        assertEquals("test:order", rows.get("iterand_type").asText());
        assertEquals(3, rows.get("sample").size(), "the digest shows the first rows only, whatever the limit");
        assertEquals("order-0", rows.get("sample").get(0).get("number").asText());
        assertFalse(shown.toString().contains("order-3"), "a row past the digest's sample is not shown");
    }

    @Test
    void everyRowIsHeldUnderItsOwnReference() {
        SearchResultArtifact<Order> result = resultOf(4012L, 50);
        ArtifactRegistry registry = new ArtifactRegistry();
        registry.register(result);
        assertSame(result.getRows(), registry.get(result.getRows().getArtifactRef()), "the list of rows is held");
        Order last = result.getRows().getIterands().get(49);
        assertNotNull(last.getArtifactRef(), "a row past the sample has a reference");
        assertSame(last, registry.get(last.getArtifactRef()), "a row past the sample is held under its reference");
        assertEquals(1, registry.getAllArtifacts().size(), "the result is the one top-level entry; rows are reached through it");
    }

    @Test
    void aSearchThatFindsNothingIsATotalOfZeroAndNoRows() throws IOException {
        SearchResultArtifact<Order> result = resultOf(0L, 0);
        new ArtifactRegistry().register(result);
        JsonNode shown = new ObjectMapper().readTree(NucleoJsonSerializer.writeSummarized(result));
        assertEquals(0L, shown.get("total_matching").asLong());
        assertEquals(0, shown.get("rows").get("count").asInt());
        assertNull(shown.get("rows").get("sample"), "an empty window has no sample");
    }

    @Test
    void aStoredResultIsWholeAndReadsBackWithTypedRows() throws IOException {
        SearchResultArtifact<Order> result = resultOf(4012L, 5);
        new ArtifactRegistry().register(result);
        String stored = NucleoJsonSerializer.write(result);
        assertTrue(stored.contains("order-4"), "storage keeps every row: " + stored);
        SearchResultArtifact<?> read = NucleoJsonSerializer.parse(stored, SearchResultArtifact.class);
        assertEquals(4012L, read.getTotalMatching());
        assertEquals(0L, read.getOffset());
        assertEquals(50L, read.getLimit());
        assertEquals(5, read.getRows().getIterands().size());
        Artifact first = read.getRows().getIterands().get(0);
        assertInstanceOf(Order.class, first, "a row reads back as its own type");
        assertEquals("order-0", ((Order) first).getNumber());
        assertEquals(result.getRows().getIterands().get(0).getArtifactRef(), first.getArtifactRef(), "a row keeps its reference");
    }
}
