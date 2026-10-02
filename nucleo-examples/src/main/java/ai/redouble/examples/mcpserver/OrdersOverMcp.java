/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpserver;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;

/**
 * A Spring Boot application offering the order-status tool to outside MCP clients, on
 * {@code http://localhost:8901/mcp}. The two MCP client examples connect to it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
@SpringBootApplication
public class OrdersOverMcp {
    public static void main(String[] args) {
        SpringApplication.run(OrdersOverMcp.class, args);
    }
}
