/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.tool;

import java.util.*;
import java.util.regex.*;

/**
 * The examples' stand-in for an application's own data: five orders of two customers, held
 * in memory. In an application this is the repository, the service call or the query the
 * tool wraps; nothing about it is Nucleo's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public final class Orders {
    /** An order number is the letter A, a dash and four digits. */
    public static final Pattern NUMBER = Pattern.compile("A-\\d{4}");
    private static final Map<String, OrderStatus> BY_NUMBER = new LinkedHashMap<>();

    static {
        add("A-1001", "C-100", "DELIVERED", "2026-09-20", "delivered on 2026-09-19");
        add("A-1002", "C-100", "DELAYED", "2026-09-22", "held at the carrier's depot");
        add("A-1003", "C-200", "PROCESSING", "2026-10-02", "waiting for one item from the supplier");
        add("A-1004", "C-200", "SHIPPED", "2026-09-29", "handed to the carrier on 2026-09-26");
        add("A-1005", "C-100", "DELAYED", "2026-09-24", "address could not be verified");
    }

    private Orders() {
    }

    private static void add(String number, String customerId, String status, String promisedFor, String note) {
        OrderStatus order = new OrderStatus();
        order.setOrderNumber(number);
        order.setCustomerId(customerId);
        order.setStatus(status);
        order.setPromisedFor(promisedFor);
        order.setNote(note);
        BY_NUMBER.put(number, order);
    }

    /** The order under that number; null when there is none. */
    public static OrderStatus find(String number) {
        return BY_NUMBER.get(number);
    }

    /** Every order of one customer, in order-number order. */
    public static List<OrderStatus> ofCustomer(String customerId) {
        List<OrderStatus> orders = new ArrayList<>();
        for (OrderStatus order : BY_NUMBER.values()) {
            if (order.getCustomerId().equals(customerId)) {
                orders.add(order);
            }
        }
        return orders;
    }
}
