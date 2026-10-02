/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

/**
 * The client under test for the official conformance suite's client-credentials scenarios:
 * <pre>
 * npx @modelcontextprotocol/conformance client --command "java -cp ... ai.redouble.nucleo.mcp.auth.ConformanceClientMain" --scenario auth/client-credentials-jwt
 * </pre>
 * The runner appends the MCP server URL as the last argument and hands the scenario's
 * context in {@code MCP_CONFORMANCE_CONTEXT}: {@code client_id} with {@code private_key_pem}
 * (PKCS#8, ES256 by default) for the jwt scenario, {@code client_id} with
 * {@code client_secret} for the basic one. Pass is the scenario's checks plus exit code 0.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class ConformanceClientMain {
    private static final Logger log = LoggerFactory.getLogger(ConformanceClientMain.class);
    private ConformanceClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String url = args[args.length - 1];
        JsonNode context = NucleoJsonSerializer.readTree(System.getenv("MCP_CONFORMANCE_CONTEXT"));
        String clientId = context.get("client_id").textValue();
        McpClientCredential credential = context.has("private_key_pem")
                ? AgentSigningKey.fromPem(clientId, context.get("private_key_pem").textValue(), null)
                : new AgentSecret(clientId, context.get("client_secret").textValue());
        HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
        endpoint.setUrl(url);
        endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
        endpoint.setCredential(credential);
        GenericMCPClient client = GenericMCPClient.connect(endpoint);
        log.info("Conformance client listed {} tools as {}", client.listTools().size(), clientId);
        client.close();
        System.exit(0);
    }
}
