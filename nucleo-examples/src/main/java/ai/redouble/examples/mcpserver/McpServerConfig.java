/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpserver;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.server.*;
import io.modelcontextprotocol.common.*;
import io.modelcontextprotocol.server.transport.*;
import org.springframework.boot.web.servlet.*;
import org.springframework.context.annotation.*;

import java.time.*;
import java.util.*;

/**
 * The MCP endpoint in three beans: the SDK's stateless HTTP servlet mounted at {@code /mcp}
 * like any servlet, and an {@link McpToolServer} over it.
 *
 * <p>The server scans the named packages for {@code @MCP} tools, turns each caller into a
 * consumer through the resolver, and answers one question through the access policy:
 * may this consumer use this tool. The same answer decides what the consumer sees listed
 * and what it may call, so grants cannot be probed. What a caller receives is the tool as
 * this application declared it - the input schema generated from its input class, the
 * description from its annotation - and everything a caller sends is checked against that
 * schema before the tool runs. Every call runs as a job under the caller's name, recorded
 * like any other work, and a call that outlives {@code setCallTimeout} has its whole
 * workflow cancelled.
 *
 * <p>This example admits every caller as one consumer, {@code local-agent}; a real host
 * authenticates the request first, the way it authenticates any request, and records the
 * principal it admitted in the transport's context extractor.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
@Configuration
public class McpServerConfig {
    /**
     * The runtime's lifecycle, wired by hand: a real application puts
     * {@code nucleo-spring-boot-starter} on its classpath, which starts the dispatcher with
     * the application and binds credentials from properties. The other examples of this
     * module are plain mains, so the starter stays off its classpath and this bean does the
     * starter's one job here. Beans are destroyed in reverse creation order, so the server
     * below closes before the dispatcher drains.
     */
    @Bean(destroyMethod = "shutdown")
    public NucleoRuntime nucleoRuntime() {
        return new NucleoRuntime();
    }

    public static class NucleoRuntime {
        NucleoRuntime() {
            JobDispatcher.getInstance().start();
        }

        public void shutdown() {
            JobDispatcher.getInstance().shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
    }

    // region server
    @Bean
    public HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                // Refuses duplicate keys and the other things a lenient JSON reader lets through
                .jsonMapper(McpBoundaryJson.strictMapper())
                // A request carrying an Origin outside this list is refused: a browser page
                // cannot call the server. The example's own clients send no Origin.
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(List.of("http://localhost:8901"))
                        .build())
                // This example admits every caller as one local consumer. A real host
                // authenticates the request first and records the principal it admitted.
                .contextExtractor(request -> McpTransportContext.create(Map.of("consumer", "local-agent")))
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
        return new ServletRegistrationBean<>(transport, "/mcp");
    }

    @Bean(destroyMethod = "close")
    public McpToolServer mcpToolServer(HttpServletStatelessServerTransport transport, NucleoRuntime runtime) {
        McpToolServer server = new McpToolServer();
        server.setScanPackages(List.of("ai.redouble.examples"));   // every @MCP tool of the examples
        server.setProviders(List.of());
        server.setConsumerResolver(context -> context.get("consumer") instanceof String name
                ? new McpConsumer(name, Set.of())
                : null);
        server.setAccessPolicy(new StaticGrantsAccessPolicy(Map.of("local-agent", List.of("order_status"))));
        server.setServerName("orders");
        server.setServerVersion("1");
        server.setCallTimeout(Duration.ofMinutes(1));
        server.setTransport(transport);
        server.start();
        return server;
    }
    // endregion
}
